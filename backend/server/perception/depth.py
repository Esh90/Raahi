"""Depth Anything V2 Small — metres per pixel.

Primary: HuggingFace metric indoor on MPS
  depth-anything/Depth-Anything-V2-Metric-Indoor-Small-hf
Fallback: Apple CoreML relative depth on the Neural Engine
  apple/coreml-depth-anything-v2-small  + pinhole scale from known object sizes

Contract: estimate(frame_bgr) -> HxW float32 metres
"""
from __future__ import annotations

import os
import threading
import time
from collections import deque
from pathlib import Path

import cv2
import numpy as np

from server.config import (
    DEPTH_BACKEND,
    DEPTH_COREML_PATH,
    DEPTH_EMA_ALPHA,
    DEPTH_MAX_M,
    DEPTH_MIN_M,
    DEPTH_MODEL_ID,
    DEPTH_SCALE,
    DEPTH_SHORT_SIDE,
    MOCK,
    ROOT,
)
from server.engine.geometry import box_truncated, median_box_depth, pinhole_z
from server.perception.detector import Detection

MODEL_DIR = ROOT / "models"
SCALE_FILE = ROOT / "server" / "depth_scale.txt"
SCALE_FILE_MODELS = MODEL_DIR / "depth_scale.txt"
COREML_REPO = "apple/coreml-depth-anything-v2-small"
COREML_PACKAGE = "DepthAnythingV2SmallF16.mlpackage"
DEPTH_FAST = os.getenv("RAAHI_DEPTH_FAST", "0").strip() in {"1", "true", "yes"}
SLOW_MS = 120.0  # if transformers is slower, switch to CoreML
SCALE_MIN, SCALE_MAX = 0.35, 2.5

_lock = threading.Lock()
_estimator: "DepthEstimator | None" = None

os.environ.setdefault("HF_HOME", str(MODEL_DIR / "hf"))
os.environ.setdefault("HF_HUB_CACHE", str(MODEL_DIR / "hf" / "hub"))
os.environ.setdefault("TRANSFORMERS_CACHE", str(MODEL_DIR / "hf" / "hub"))


def estimate(frame_bgr: np.ndarray) -> np.ndarray:
    return get_estimator().estimate(frame_bgr)


def get_estimator() -> "DepthEstimator":
    global _estimator
    with _lock:
        if _estimator is None:
            _estimator = DepthEstimator()
        return _estimator


def reset_estimator() -> None:
    global _estimator
    with _lock:
        _estimator = None


def status() -> dict:
    est = _estimator
    if est is None:
        return {"backend": "unloaded", "device": "", "scale": read_saved_scale()}
    return {
        "backend": est.backend,
        "device": est.device,
        "last_ms": round(est.last_ms, 1),
        "scale": round(est.user_scale, 4),
    }


def read_saved_scale() -> float:
    env = os.getenv("RAAHI_DEPTH_SCALE", os.getenv("BASIRAT_DEPTH_SCALE", "")).strip()
    if env:
        try:
            v = float(env)
            # 1.0 means "unset" so the tape file still wins
            if abs(v - 1.0) > 1e-6:
                return _clip_scale(v)
        except ValueError:
            pass
    if abs(DEPTH_SCALE - 1.0) > 1e-6:
        return _clip_scale(DEPTH_SCALE)
    for path in (SCALE_FILE, SCALE_FILE_MODELS):
        if path.exists():
            try:
                return _clip_scale(float(path.read_text().strip().split()[0]))
            except (ValueError, OSError):
                continue
    return 1.0


def write_saved_scale(scale: float) -> float:
    scale = _clip_scale(scale)
    SCALE_FILE.parent.mkdir(parents=True, exist_ok=True)
    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    body = (
        f"{scale:.5f}\n"
        "# tape / model  (webcam FOV). Reload: restart python -m server\n"
    )
    SCALE_FILE.write_text(body)
    SCALE_FILE_MODELS.write_text(body)
    return scale


def _clip_scale(scale: float) -> float:
    if not np.isfinite(scale) or scale <= 0:
        return 1.0
    return float(min(SCALE_MAX, max(SCALE_MIN, scale)))


def _local_model_src() -> tuple[str, bool]:
    """Prefer the already-downloaded snapshot so startup never hits the network."""
    snap_root = (
        MODEL_DIR
        / "hf"
        / "hub"
        / f"models--{DEPTH_MODEL_ID.replace('/', '--')}"
        / "snapshots"
    )
    if snap_root.exists():
        for folder in sorted(snap_root.iterdir(), reverse=True):
            weights = folder / "model.safetensors"
            if folder.is_dir() and (folder / "config.json").exists():
                try:
                    if weights.exists() and weights.resolve().stat().st_size > 1_000_000:
                        return str(folder), True
                except OSError:
                    continue
    return DEPTH_MODEL_ID, False


def warmup() -> str:
    est = get_estimator()
    dummy = np.zeros((240, 416, 3), dtype=np.uint8)
    _ = est.estimate(dummy)
    t0 = time.perf_counter()
    _ = est.estimate(dummy)
    ms = (time.perf_counter() - t0) * 1000
    est.last_ms = ms
    print(f"[depth] warmup backend={est.backend} device={est.device} {ms:.0f} ms", flush=True)
    if est.backend == "transformers" and ms > SLOW_MS:
        print(f"[depth] {ms:.0f} ms > {SLOW_MS:.0f} — trying CoreML Neural Engine", flush=True)
        if est.switch_to_coreml():
            t0 = time.perf_counter()
            _ = est.estimate(dummy)
            est.last_ms = (time.perf_counter() - t0) * 1000
            print(f"[depth] CoreML warmup {est.last_ms:.0f} ms", flush=True)
    est.reset_temporal()
    return est.backend


class ScaleFitter:
    """Relative depth → metres using known-size boxes (pinhole)."""

    def __init__(self, maxlen: int = 24) -> None:
        self._direct: deque[float] = deque(maxlen=maxlen)
        self._inverse: deque[float] = deque(maxlen=maxlen)

    def update(self, raw: np.ndarray, detections: list[Detection], hw: tuple[int, int]) -> None:
        h, w = hw
        for det in detections:
            if box_truncated(det.box, h, w):
                continue
            pz = pinhole_z(det.cls, det.box)
            if pz is None:
                continue
            med = median_box_depth(raw, det.box)
            if not np.isfinite(med) or med <= 1e-6:
                continue
            self._direct.append(float(pz / med))
            self._inverse.append(float(pz * med))

    def scale(self) -> tuple[float, bool]:
        if not self._direct:
            return 1.0, False
        direct = float(np.median(self._direct))
        inverse = float(np.median(self._inverse)) if self._inverse else 1.0
        if len(self._direct) < 3:
            return direct, False
        sd = float(np.std(self._direct))
        si = float(np.std(self._inverse))
        if si < sd:
            return inverse, True
        return direct, False

    def apply(self, raw: np.ndarray) -> np.ndarray:
        scale, inv = self.scale()
        raw_f = raw.astype(np.float32)
        metres = (scale / np.clip(raw_f, 1e-4, None)) if inv else (raw_f * scale)
        return np.clip(metres, DEPTH_MIN_M, DEPTH_MAX_M)


class DepthEstimator:
    def __init__(self) -> None:
        self.backend = "mock"
        self.device = "cpu"
        self.last_ms = 0.0
        self._model = None
        self._processor = None
        self._coreml = None
        self._coreml_in = "image"
        self._coreml_out = "depth"
        self._coreml_size = (518, 392)
        self._torch = None
        self._last_raw: np.ndarray | None = None
        self._ema: np.ndarray | None = None
        self.user_scale = read_saved_scale()
        self.scale = ScaleFitter()
        self._load()
        if abs(self.user_scale - 1.0) > 1e-6:
            print(f"[depth] tape scale={self.user_scale:.3f}", flush=True)

    def _load(self) -> None:
        want = DEPTH_BACKEND
        if MOCK or want == "mock":
            self.backend = "mock"
            print("[depth] MOCK=1 — synthetic metres (set RAAHI_MOCK=0 for the real model)", flush=True)
            return
        if want in {"auto", "transformers"}:
            if self._try_transformers():
                return
            if want == "transformers":
                print("[depth] transformers failed, using mock")
                self.backend = "mock"
                return
        if want in {"auto", "coreml"}:
            if self._try_coreml():
                return
        print("[depth] no model available — synthetic mock depth")
        self.backend = "mock"

    def _try_transformers(self) -> bool:
        try:
            import torch
            from transformers import AutoImageProcessor, AutoModelForDepthEstimation
        except Exception as exc:
            print(f"[depth] transformers import failed: {exc}")
            return False

        if hasattr(torch.backends, "mps") and torch.backends.mps.is_available():
            device = torch.device("mps")
        elif torch.cuda.is_available():
            device = torch.device("cuda")
        else:
            device = torch.device("cpu")
        try:
            src, offline = _local_model_src()
            if offline:
                print(f"[depth] loading from disk on {device} (not downloading) …", flush=True)
            else:
                print(f"[depth] loading {DEPTH_MODEL_ID} on {device} …", flush=True)
            kw = {"local_files_only": offline}
            self._processor = AutoImageProcessor.from_pretrained(src, **kw)
            self._model = AutoModelForDepthEstimation.from_pretrained(src, **kw)
            self._model.to(device)
            self._model.eval()
            self.device = str(device)
            self.backend = "transformers"
            self._torch = torch
            print("[depth] transformers metric indoor ready", flush=True)
            return True
        except Exception as exc:
            print(f"[depth] transformers load failed: {exc}")
            return False

    def _coreml_path(self) -> Path | None:
        if DEPTH_COREML_PATH:
            p = Path(DEPTH_COREML_PATH)
            return p if p.exists() else None
        local = MODEL_DIR / COREML_PACKAGE
        if local.exists():
            return local
        return None

    def _download_coreml(self) -> Path | None:
        try:
            from huggingface_hub import snapshot_download
        except Exception as exc:
            print(f"[depth] huggingface_hub missing: {exc}")
            return None
        MODEL_DIR.mkdir(parents=True, exist_ok=True)
        print(f"[depth] downloading {COREML_REPO} …")
        try:
            snapshot_download(
                repo_id=COREML_REPO,
                local_dir=str(MODEL_DIR),
                allow_patterns=[f"{COREML_PACKAGE}/*"],
            )
        except Exception as exc:
            print(f"[depth] CoreML download failed: {exc}")
            return None
        path = MODEL_DIR / COREML_PACKAGE
        return path if path.exists() else None

    def _try_coreml(self) -> bool:
        path = self._coreml_path() or self._download_coreml()
        if path is None:
            return False
        try:
            import coremltools as ct

            self._coreml = ct.models.MLModel(str(path))
            spec = self._coreml.get_spec()
            self._coreml_in = spec.description.input[0].name
            self._coreml_out = spec.description.output[0].name
            shape = spec.description.input[0].type.imageType
            if shape.width and shape.height:
                self._coreml_size = (int(shape.width), int(shape.height))
            self.backend = "coreml"
            self.device = "ane"
            print(f"[depth] CoreML {path.name} in={self._coreml_in} {self._coreml_size}")
            return True
        except Exception as exc:
            print(f"[depth] CoreML load failed: {exc}")
            return False

    def switch_to_coreml(self) -> bool:
        return self._try_coreml()

    def reset_temporal(self) -> None:
        self._ema = None

    def set_user_scale(self, scale: float) -> float:
        self.user_scale = write_saved_scale(scale)
        self.reset_temporal()
        print(f"[depth] tape scale={self.user_scale:.3f}", flush=True)
        return self.user_scale

    def estimate(self, frame_bgr: np.ndarray) -> np.ndarray:
        if frame_bgr is None or frame_bgr.size == 0:
            return np.zeros((1, 1), dtype=np.float32)
        t0 = time.perf_counter()
        if self.backend == "transformers":
            out = self._infer_transformers(frame_bgr)
        elif self.backend == "coreml":
            out = self._infer_coreml(frame_bgr)
        else:
            out = synthetic_depth(frame_bgr.shape[0], frame_bgr.shape[1])
        if abs(self.user_scale - 1.0) > 1e-6:
            out = (out * np.float32(self.user_scale)).astype(np.float32)
        out = self._stabilize(out)
        self.last_ms = (time.perf_counter() - t0) * 1000
        return out

    def _stabilize(self, metres: np.ndarray) -> np.ndarray:
        metres = np.squeeze(np.asarray(metres, dtype=np.float32))
        if metres.ndim != 2:
            return metres.astype(np.float32)
        metres = np.clip(metres, DEPTH_MIN_M, DEPTH_MAX_M)
        if min(metres.shape) >= 5:
            metres = cv2.medianBlur(metres, 3)
        if self._ema is not None and self._ema.shape == metres.shape:
            a = DEPTH_EMA_ALPHA
            metres = (a * metres + (1.0 - a) * self._ema).astype(np.float32)
        self._ema = metres
        return metres

    def _resize_for_model(self, rgb: np.ndarray) -> np.ndarray:
        h, w = rgb.shape[:2]
        short = min(h, w)
        scale = DEPTH_SHORT_SIDE / max(short, 1)
        nh, nw = int(round(h * scale)), int(round(w * scale))
        nh = max(14, (nh // 14) * 14)
        nw = max(14, (nw // 14) * 14)
        return cv2.resize(rgb, (nw, nh), interpolation=cv2.INTER_AREA)

    def _infer_transformers(self, frame_bgr: np.ndarray) -> np.ndarray:
        torch = self._torch
        h, w = frame_bgr.shape[:2]
        rgb = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2RGB)
        rgb = self._resize_for_model(rgb)
        from PIL import Image

        if DEPTH_FAST:
            # Really run at DEPTH_SHORT_SIDE: the HF processor otherwise resizes back up to 518 px,
            # so the short-side setting had no effect. Re-calibrate the tape scale when enabling.
            inputs = self._processor(images=Image.fromarray(rgb), return_tensors="pt", do_resize=False)
        else:
            inputs = self._processor(images=Image.fromarray(rgb), return_tensors="pt")
        inputs = {k: v.to(self._model.device) for k, v in inputs.items()}
        with torch.inference_mode():
            pred = self._model(**inputs).predicted_depth
        pred = pred.detach().float().cpu()
        if pred.ndim == 2:
            pred = pred.unsqueeze(0)
        pred = torch.nn.functional.interpolate(
            pred.unsqueeze(1),
            size=(h, w),
            mode="bilinear",
            align_corners=False,
        )
        depth = pred.squeeze().numpy().astype(np.float32)
        if float(np.nanmedian(depth)) > 25:
            depth = depth / 100.0
        return np.clip(depth, DEPTH_MIN_M, DEPTH_MAX_M)

    def _infer_coreml(self, frame_bgr: np.ndarray) -> np.ndarray:
        h, w = frame_bgr.shape[:2]
        rgb = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2RGB)
        from PIL import Image

        pil = Image.fromarray(rgb).resize(self._coreml_size, Image.Resampling.BILINEAR)
        out = self._coreml.predict({self._coreml_in: pil})
        raw = np.array(out[self._coreml_out])
        raw = np.squeeze(raw).astype(np.float32)
        raw = cv2.resize(raw, (w, h), interpolation=cv2.INTER_LINEAR)
        self._last_raw = raw
        return self.scale.apply(raw)

    def refine_scale(self, depth_m: np.ndarray, detections: list[Detection]) -> np.ndarray:
        """Fit pinhole scale on CoreML relative maps. No-op for metric transformers."""
        if self.backend != "coreml" or not detections or self._last_raw is None:
            return depth_m
        self.scale.update(self._last_raw, detections, self._last_raw.shape[:2])
        return self.scale.apply(self._last_raw)


def synthetic_depth(h: int, w: int, nearest_m: float = 1.8) -> np.ndarray:
    """Corridor mock used only when no weights are installed."""
    ys = np.linspace(4.2, max(0.9, nearest_m * 0.7), h, dtype=np.float32)
    depth = np.repeat(ys[:, None], w, axis=1)
    cy, cx = int(h * 0.62), w // 2
    yy, xx = np.ogrid[:h, :w]
    blob = ((yy - cy) / (h * 0.22)) ** 2 + ((xx - cx) / (w * 0.14)) ** 2
    depth = np.minimum(depth, nearest_m + blob * 2.8)
    depth[:, : w // 3] = np.maximum(depth[:, : w // 3], 2.9)
    depth[:, 2 * w // 3 :] = np.maximum(depth[:, 2 * w // 3 :], 2.7)
    return depth.astype(np.float32)
