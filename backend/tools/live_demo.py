"""Laptop end-to-end test of the REAL pipeline (same code the Mac server runs per frame):
webcam -> YOLOE detector (Esha) + Depth Anything metric depth (Member B) -> GuidanceEngine
(navigator / find / describe / smoother / announcer) -> Urdu clips on the laptop speakers.
Plus push-to-talk voice commands (ElevenLabs Scribe -> intents -> engine).

    python -m tools.live_demo                         # depth on (slow on a CPU laptop, runs in background)
    python -m tools.live_demo --no-depth              # detector-only fallback (what the phone does offline)
    python -m tools.live_demo --cam 1 --scale 0.8     # other camera / depth scale for this webcam
    python -m tools.live_demo --lang en               # English voice

Keys (click the video window first):
    SPACE  talk (4 s): "darwaza dhoondo", "سامنے کیا ہے", "ruk jao" ... (no wake word needed)
    d      describe              1/2/3/4  find door / chair / person / table
    s      stop find             p        pause / resume              r  repeat
    c      CALIBRATE depth: point the laptop at a wall/door exactly --cal-m metres away (default 1.5)
           and press c - the CENTRE zone is then matched to that distance (do this once)
    [ / ]  depth scale -5% / +5% (fine tuning)
    q/ESC  quit
Every spoken message is printed with its latency and logged to runs/live_log.csv.
"""
from __future__ import annotations

import argparse
import csv
import io
import os
import threading
import time
import wave
from pathlib import Path

import cv2
import numpy as np
import sounddevice as sd
from dotenv import load_dotenv

ROOT = Path(__file__).resolve().parents[1]
load_dotenv(ROOT / ".env")
os.environ.setdefault("RAAHI_MOCK", "0")
# CPU-only laptop: run Depth Anything at a really-small input (~0.45 s instead of ~1.3 s per map).
# On the Mac (MPS) / a CUDA GPU, Member B's calibrated defaults are kept.
try:
    import torch as _torch
    _gpu = _torch.cuda.is_available() or (hasattr(_torch.backends, "mps") and _torch.backends.mps.is_available())
except Exception:
    _gpu = False
if not _gpu:
    os.environ.setdefault("RAAHI_DEPTH_FAST", "1")
    os.environ.setdefault("RAAHI_DEPTH_SHORT_SIDE", "252")

import httpx  # noqa: E402

from server.engine.pipeline import GuidanceEngine  # noqa: E402
from server.perception.detector import configure  # noqa: E402
from server.voice.intents import TARGETS, WAKE_WORDS, Intent, parse  # noqa: E402
from tools.player import Player  # noqa: E402
from voice_assets import sentence as S  # noqa: E402

REC_SECONDS = 4.0
MIC_SR = 16000
ZONE_BGR = {"red": (0, 0, 255), "yellow": (0, 200, 255), "green": (0, 200, 0)}


class DepthWorker:
    """Runs Depth Anything on the newest frame in the background; engine uses the latest map."""

    def __init__(self, scale: float | None):
        from server.perception.depth import get_estimator
        print("[depth] loading model (first time downloads ~100 MB)...")
        self.est = get_estimator()
        # Member B's saved tape scale (server/depth_scale.txt) is for her Mac webcam at full depth
        # resolution. In CPU fast mode it doesn't apply -> start at --scale / RAAHI_DEPTH_SCALE / 1.0
        # and press c. On the Mac her saved scale is kept unless --scale is given.
        if scale:
            self.est.user_scale = scale
        elif os.getenv("RAAHI_DEPTH_FAST") == "1":
            self.est.user_scale = float(os.getenv("RAAHI_DEPTH_SCALE", "1.0") or 1.0)
        print(f"[depth] backend={self.est.backend} device={self.est.device} scale={self.est.user_scale:.3f}")
        self.frame = None
        self.depth = None
        self.ms = 0.0
        self.lock = threading.Lock()
        threading.Thread(target=self._loop, daemon=True).start()

    def calibrate(self, true_m: float) -> str:
        from server.engine.geometry import zone_depths
        if self.depth is None:
            return "no depth yet"
        centre = zone_depths(self.depth)[1]
        if not np.isfinite(centre) or centre <= 0.1:
            return f"bad centre reading ({centre})"
        self.est.user_scale *= true_m / centre
        self.est.reset_temporal()
        self.depth = None
        return f"centre read {centre:.2f} m -> scale {self.est.user_scale:.3f} (now = {true_m} m)"

    def submit(self, frame):
        with self.lock:
            self.frame = frame

    def _loop(self):
        while True:
            with self.lock:
                f, self.frame = self.frame, None
            if f is None:
                time.sleep(0.005)
                continue
            t = time.perf_counter()
            d = self.est.estimate(f)
            self.ms = (time.perf_counter() - t) * 1000
            self.depth = d


# "wall"/"window"-type boxes usually cover the whole background - no usable size, so in
# detector-only mode they'd make everything look blocked. Depth mode handles walls properly.
IGNORE_NO_DEPTH = {"wall", "window", "projector_screen", "whiteboard"}


def pseudo_depth(dets, shape, fx: float = 320.0) -> np.ndarray:
    """--no-depth: paint each detected box with its estimated distance (pinhole on height, or on
    width when the box is cut off top/bottom); everything else is 'far'. Lets the same engine
    guide from the detector alone - this is what the phone can do offline."""
    from server.perception.sizes import height_m
    h, w = shape[:2]
    depth = np.full((h, w), 6.0, np.float32)
    widths = {"person": 0.45, "chair": 0.5, "table": 1.2, "desk": 1.2, "sofa": 1.8, "door": 0.9,
              "glass_door": 0.9, "bed": 1.4, "cupboard": 1.0, "shelf": 0.9, "trash_bin": 0.4,
              "plant_pot": 0.4, "laptop": 0.35, "monitor": 0.5, "tv_led": 1.0, "whiteboard": 1.5,
              "bag": 0.35, "box": 0.4, "bottle": 0.08, "bench": 1.2, "water_cooler": 0.35, "fan_stand": 0.45}
    for d in dets:
        if d.cls in IGNORE_NO_DEPTH:
            continue
        x1, y1, x2, y2 = d.box
        cut_v = y1 <= 3 or y2 >= h - 3
        cut_h = x1 <= 3 or x2 >= w - 3
        hm = height_m(d.cls)
        if hm and not cut_v:
            z = hm * fx / max(8, y2 - y1)
        elif d.cls in widths and not cut_h:
            z = widths[d.cls] * fx / max(8, x2 - x1)
        else:
            z = 0.7 if (x2 - x1) * (y2 - y1) > 0.45 * w * h else 1.6
        depth[y1:y2, x1:x2] = np.minimum(depth[y1:y2, x1:x2], np.float32(z))
    return depth


class Demo:
    def __init__(self, args):
        self.det = configure(args.model, args.imgsz)
        self.engine = GuidanceEngine()
        self.depth = None if args.no_depth else DepthWorker(args.scale)
        self.player = Player(language=args.lang)
        self.player.on_start = self._on_audio_start
        self.listening = False
        self.cal_m = args.cal_m
        self.status = "ready - press c at a known distance to calibrate depth" if self.depth else "ready"
        self.subtitle = ""
        self.last_clips: list[str] = []
        self.pending: dict[tuple, float] = {}
        (ROOT / "runs").mkdir(exist_ok=True)
        self.log = open(ROOT / "runs" / "live_log.csv", "a", newline="", encoding="utf-8")
        self.csv = csv.writer(self.log)

    # ---------------------------------------------------------------- speech
    def say(self, reply, frame_t=None, why=""):
        if reply is None or not reply.clips or self.listening:
            return
        clips = list(reply.clips)
        self.last_clips = clips
        self.pending[tuple(clips)] = frame_t or time.perf_counter()
        self.player.play(clips, interrupt=reply.interrupt)
        self.csv.writerow([time.strftime("%H:%M:%S"), "queued", why, " ".join(clips), reply.english])

    def _on_audio_start(self, ids, queued_at, started):
        lat = (started - self.pending.pop(tuple(ids), queued_at)) * 1000
        self.subtitle = S.to_text(ids, "en")
        print(f"[{time.strftime('%H:%M:%S')}] SPEAK {lat:5.0f} ms | {self.subtitle}")
        self.csv.writerow([time.strftime("%H:%M:%S"), "spoken", f"{lat:.0f}", " ".join(ids), self.subtitle])
        self.log.flush()

    def command(self, intent: Intent | None):
        if intent is None:
            return
        print(f"   intent: {intent}")
        if intent.type == "repeat":
            if self.last_clips:
                self.player.play(self.last_clips, interrupt=True)
            return
        self.say(self.engine.handle_intent(intent), why=f"cmd:{intent.type}")

    # ---------------------------------------------------------------- voice
    def talk(self):
        if not self.listening:
            threading.Thread(target=self._talk, daemon=True).start()

    def _talk(self):
        self.player.stop()
        self.player.play(["ear_listen"])
        time.sleep(0.35)
        self.listening, self.status = True, "LISTENING... speak now"
        audio = sd.rec(int(REC_SECONDS * MIC_SR), samplerate=MIC_SR, channels=1, dtype="int16")
        sd.wait()
        self.listening, self.status = False, "transcribing..."
        buf = io.BytesIO()
        with wave.open(buf, "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(MIC_SR); w.writeframes(audio.tobytes())
        keyterms = sorted({w for w in WAKE_WORDS if " " not in w} | {v[0] for v in TARGETS.values()}
                          | {"ڈھونڈو", "سامنے کیا ہے", "رک جاؤ"})
        try:
            r = httpx.post("https://api.elevenlabs.io/v1/speech-to-text",
                           headers={"xi-api-key": os.getenv("ELEVENLABS_API_KEY", "")},
                           data={"model_id": "scribe_v2", "language_code": "ur", "keyterms": keyterms},
                           files={"file": ("cmd.wav", buf.getvalue(), "audio/wav")}, timeout=20)
            r.raise_for_status()
            text = r.json().get("text", "").strip()
        except Exception as e:
            print("   STT failed:", e)
            self.status = "STT failed (see console)"
            return
        print(f"   heard: {text!r}")
        intent = parse(text, require_wake=False)
        self.status = f"heard: {text}" if text else "heard nothing"
        self.command(intent)

    # ---------------------------------------------------------------- loop
    def run(self, cam: int, width: int):
        cap = cv2.VideoCapture(cam, cv2.CAP_DSHOW) if os.name == "nt" else cv2.VideoCapture(cam)
        cap.set(cv2.CAP_PROP_FRAME_WIDTH, 640)
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)
        if not cap.isOpened():
            raise SystemExit(f"camera {cam} not available")
        self.player.play(["sys_connected"])
        print(__doc__.split("Keys")[1])
        fps_t, fps_n, fps = time.perf_counter(), 0, 0.0
        while True:
            ok, frame = cap.read()
            if not ok:
                break
            frame_t = time.perf_counter()
            h0, w0 = frame.shape[:2]
            frame = cv2.resize(frame, (width, int(h0 * width / w0)))
            if self.depth:
                self.depth.submit(frame)
            t = time.perf_counter()
            dets = self.det(frame)
            det_ms = (time.perf_counter() - t) * 1000
            depth_m = self.depth.depth if self.depth else pseudo_depth(dets, frame.shape)
            if depth_m is not None and depth_m.shape[:2] != frame.shape[:2]:
                depth_m = cv2.resize(depth_m, (frame.shape[1], frame.shape[0]))
            if self.depth and depth_m is None:
                decision, reply = None, None           # depth still warming up
            else:
                decision, reply = self.engine.process(frame.shape, dets, depth_m)
                if not self.listening:
                    self.say(reply, frame_t, why=decision.kind)

            fps_n += 1
            if time.perf_counter() - fps_t > 1:
                fps, fps_n, fps_t = fps_n / (time.perf_counter() - fps_t), 0, time.perf_counter()
            vis = self.draw(frame.copy(), dets, decision, reply, det_ms, fps)
            if depth_m is not None:
                dv = cv2.applyColorMap(np.clip(255 - depth_m / 4.0 * 255, 0, 255).astype(np.uint8),
                                       cv2.COLORMAP_INFERNO)
                vis = np.hstack([vis, dv])
            cv2.imshow("Raahi live demo (left: guidance, right: depth - brighter = closer)", vis)
            k = cv2.waitKey(1) & 0xFF
            if k in (ord("q"), 27):
                break
            self.key(k)
        cap.release()
        cv2.destroyAllWindows()
        self.log.close()
        if self.depth:
            print(f"\nDepth scale used: {self.depth.est.user_scale:.3f}  "
                  f"(put RAAHI_DEPTH_SCALE={self.depth.est.user_scale:.3f} in .env to keep it)")

    def key(self, k):
        if k == ord(" "):
            self.talk()
        elif k == ord("d"):
            self.command(Intent("describe"))
        elif k in (ord("1"), ord("2"), ord("3"), ord("4")):
            self.command(Intent("find", {ord("1"): "door", ord("2"): "chair", ord("3"): "person",
                                         ord("4"): "table"}[k]))
        elif k == ord("s"):
            self.command(Intent("stop"))
        elif k == ord("p"):
            self.command(Intent("resume" if self.engine.state.mode == "paused" else "pause"))
        elif k == ord("r"):
            self.command(Intent("repeat"))
        elif k == ord("c") and self.depth:
            msg = self.depth.calibrate(self.cal_m)
            self.status = "calibrated: " + msg
            print("   calibrate:", msg)
        elif k in (ord("["), ord("]")) and self.depth:
            self.depth.est.user_scale *= 0.95 if k == ord("[") else 1.05
            self.depth.est.reset_temporal()
            print(f"   depth scale -> {self.depth.est.user_scale:.3f}")

    def draw(self, f, dets, decision, reply, det_ms, fps):
        h, w = f.shape[:2]
        if decision is not None and decision.zone_depths:
            y0, y1 = int(0.30 * h), int(0.90 * h)
            for i, (d, col) in enumerate(zip(decision.zone_depths, decision.zone_colors or ("green",) * 3)):
                x0, x1 = int(w * (0, 0.33, 0.67)[i]), int(w * (0.33, 0.67, 1.0)[i])
                cv2.rectangle(f, (x0 + 2, y0), (x1 - 2, y1), ZONE_BGR.get(col, (200, 200, 200)), 2)
                txt = "far" if not np.isfinite(d) else f"{d:.1f}m"
                cv2.putText(f, ("L ", "C ", "R ")[i] + txt, (x0 + 6, y1 - 6), cv2.FONT_HERSHEY_SIMPLEX, 0.5,
                            ZONE_BGR.get(col, (255, 255, 255)), 1, cv2.LINE_AA)
        for o in (reply.overlay if reply else []):
            x1, y1, x2, y2 = [int(v) for v in (o["box"][0] * w, o["box"][1] * h, o["box"][2] * w, o["box"][3] * h)]
            col = (255, 255, 255) if o["label"] == "obstacle" else (255, 200, 0)
            cv2.rectangle(f, (x1, y1), (x2, y2), col, 1)
            dist = "" if o.get("dist_m") is None else f" {o['dist_m']:.1f}m"
            cv2.putText(f, f"{o['label']}{dist}", (x1 + 3, max(14, y1 + 14)), cv2.FONT_HERSHEY_SIMPLEX, 0.45,
                        col, 1, cv2.LINE_AA)
        if not reply:
            for d in dets:
                cv2.rectangle(f, d.box[:2], d.box[2:], (255, 200, 0), 1)
        mode = self.engine.state.mode.upper()
        if self.engine.state.find_target:
            mode += f" {self.engine.state.find_target}"
        dms = f" | depth {self.depth.ms:.0f} ms" if self.depth else " | no depth"
        if self.depth and self.depth.depth is None:
            dms = " | depth loading..."
        bar = f"{mode} | detect {det_ms:.0f} ms{dms} | {fps:.1f} fps {'| SPEAKING' if self.player.is_playing() else ''}"
        cv2.rectangle(f, (0, 0), (w, 22), (0, 0, 0), -1)
        cv2.putText(f, bar, (5, 16), cv2.FONT_HERSHEY_SIMPLEX, 0.42, (255, 255, 255), 1, cv2.LINE_AA)
        cv2.rectangle(f, (0, h - 40), (w, h), (0, 0, 0), -1)
        cv2.putText(f, self.subtitle[:72], (5, h - 24), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (0, 255, 255), 1, cv2.LINE_AA)
        cv2.putText(f, self.status[:72], (5, h - 7), cv2.FONT_HERSHEY_SIMPLEX, 0.42,
                    (0, 0, 255) if self.listening else (200, 200, 200), 1, cv2.LINE_AA)
        return f


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=None, help="default models/raahi_yoloe_n_320.onnx")
    ap.add_argument("--imgsz", type=int, default=None)
    ap.add_argument("--cam", type=int, default=0)
    ap.add_argument("--width", type=int, default=416)
    ap.add_argument("--no-depth", action="store_true", help="detector only (phone offline fallback)")
    ap.add_argument("--scale", type=float, default=None, help="depth scale for this webcam")
    ap.add_argument("--cal-m", type=float, default=1.5, help="known distance used by the c key")
    ap.add_argument("--lang", choices=["ur", "en"], default="ur", help="guidance voice: Urdu or English")
    a = ap.parse_args()
    if a.no_depth:
        os.environ["RAAHI_MOCK"] = "0"
    Demo(a).run(a.cam, a.width)


if __name__ == "__main__":
    main()
