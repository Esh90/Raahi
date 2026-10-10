"""Object detector (contract: plan.md 5.4).

    from server.perception.detector import detect
    dets = detect(frame_bgr)   # list[Detection], cls = our class id from shared/classes.json

Backends, chosen with env vars (or configure()):
    RAAHI_MODEL   path or name of weights. Default: models/raahi_yoloe_n_320.onnx (exported, fast),
                 falling back to "yoloe-26s-seg.pt" if that file is missing. Relative paths are
                 resolved from the repo root.
                 *.pt with "yoloe" in the name  -> open-vocab YOLOE, set_classes(our prompts)
                 any other *.pt / .onnx / .tflite / .mlpackage / _openvino_model
                 -> fixed-class model (our exported YOLOE, fine-tuned YOLO26, or COCO YOLO26)
    RAAHI_IMGSZ   inference size, default 416
    RAAHI_DEVICE  "cpu" | "mps" | "cuda:0" (default: auto)
Output names are mapped to our ids via prompts / COCO names (server/perception/classes.py),
so every backend returns the same class ids.
RAAHI_MOCK=1 (Member B's CI / no-weights mode) -> server/mocks/mock_detector boxes instead.
"""
from __future__ import annotations

import os
import threading
from dataclasses import dataclass

import numpy as np

from server.perception import classes as C

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
# s@416: people/chairs reliably (person 0.85 vs 0.35 for n@320 on a close-up test frame); ~200 ms on a laptop
# CPU, fast on the Mac. n@320 stays as the fallback (and for the phone).
DEFAULT_MODEL = ROOT / "models" / "raahi_yoloe_s_416.onnx"
FALLBACK_MODEL = ROOT / "models" / "raahi_yoloe_n_320.onnx"
MIN_AREA_FRAC = 0.02     # drop boxes smaller than 2% of the frame
MERGE_IOU = 0.6          # merge duplicate boxes of the same class (e.g. "chair" + "office chair")
BASE_CONF = 0.20         # model-level threshold; per-class thresholds applied after


@dataclass
class Detection:
    cls: str                          # "door", "chair", ...
    conf: float
    box: tuple[int, int, int, int]    # x1, y1, x2, y2 in pixels

    def to_overlay(self, w: int, h: int, dist_m: float | None = None) -> dict:
        x1, y1, x2, y2 = self.box
        return {"label": self.cls, "conf": round(self.conf, 2),
                "box": [round(x1 / w, 4), round(y1 / h, 4), round(x2 / w, 4), round(y2 / h, 4)],
                "dist_m": None if dist_m is None else round(dist_m, 2)}


class Detector:
    def __init__(self, model: str | None = None, imgsz: int | None = None, device: str | None = None):
        from ultralytics import YOLO

        self.model_path = _resolve_model(model or os.getenv("RAAHI_MODEL"))
        default_sz = 320 if "_320" in os.path.basename(self.model_path) else 416
        self.imgsz = int(imgsz or os.getenv("RAAHI_IMGSZ", default_sz))
        self.device = device or os.getenv("RAAHI_DEVICE") or None
        self.open_vocab = self.model_path.endswith(".pt") and "yoloe" in os.path.basename(self.model_path).lower()

        if self.open_vocab:
            from ultralytics import YOLOE
            self.model = YOLOE(self.model_path)
            names = C.prompts()
            try:
                self.model.set_classes(names)                          # newer ultralytics
            except TypeError:
                self.model.set_classes(names, self.model.get_text_pe(names))
        else:
            self.model = YOLO(self.model_path)   # task read from export metadata (seg/detect)
        self._lock = threading.Lock()   # ultralytics predictors aren't thread-safe
        self._name_map = C.name_to_class()

    def __call__(self, frame_bgr: np.ndarray) -> list[Detection]:
        h, w = frame_bgr.shape[:2]
        with self._lock:
            res = self.model.predict(frame_bgr, imgsz=self.imgsz, conf=BASE_CONF, device=self.device,
                                     verbose=False)[0]
        if res.boxes is None or len(res.boxes) == 0:
            return []
        names = res.names
        xyxy = res.boxes.xyxy.cpu().numpy()
        confs = res.boxes.conf.cpu().numpy()
        cls_idx = res.boxes.cls.cpu().numpy().astype(int)

        dets: list[Detection] = []
        min_area = MIN_AREA_FRAC * w * h
        for (x1, y1, x2, y2), conf, ci in zip(xyxy, confs, cls_idx):
            cls_id = self._name_map.get(names.get(int(ci), ""))
            if cls_id is None or not C.by_id()[cls_id].get("enabled", True):
                continue                                   # e.g. COCO "cat" - not ours
            if conf < C.min_conf(cls_id):
                continue
            if (x2 - x1) * (y2 - y1) < min_area:
                continue
            dets.append(Detection(cls_id, float(conf),
                                  (int(max(0, x1)), int(max(0, y1)), int(min(w, x2)), int(min(h, y2)))))
        return merge_duplicates(dets)


def _resolve_model(m: str | None) -> str:
    if not m:
        for m in (DEFAULT_MODEL, FALLBACK_MODEL):
            if m.exists():
                return str(m)
        return "yoloe-26s-seg.pt"
    p = Path(m)
    if not p.is_absolute() and (ROOT / p).exists():
        return str(ROOT / p)
    return m


def _mock_mode() -> bool:
    return os.getenv("RAAHI_MOCK", os.getenv("BASIRAT_MOCK", "0")).strip() in {"1", "true", "True", "yes"}


def iou(a, b) -> float:
    ix1, iy1, ix2, iy2 = max(a[0], b[0]), max(a[1], b[1]), min(a[2], b[2]), min(a[3], b[3])
    inter = max(0, ix2 - ix1) * max(0, iy2 - iy1)
    if inter == 0:
        return 0.0
    ua = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / ua if ua else 0.0


def merge_duplicates(dets: list[Detection]) -> list[Detection]:
    dets = sorted(dets, key=lambda d: d.conf, reverse=True)
    kept: list[Detection] = []
    for d in dets:
        if all(not (k.cls == d.cls and iou(k.box, d.box) > MERGE_IOU) for k in kept):
            kept.append(d)
    return kept


_default: Detector | None = None
_default_lock = threading.Lock()


def configure(model: str | None = None, imgsz: int | None = None, device: str | None = None) -> Detector:
    global _default
    with _default_lock:
        _default = Detector(model, imgsz, device)
    return _default


def detect(frame_bgr: np.ndarray) -> list[Detection]:
    global _default
    if _mock_mode():
        from server.mocks.mock_detector import detect as mock_detect
        return mock_detect(frame_bgr)
    if _default is None:
        with _default_lock:
            if _default is None:
                _default = Detector()
    return _default(frame_bgr)
