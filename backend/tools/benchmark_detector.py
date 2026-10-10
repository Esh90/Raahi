"""Benchmark detector latency.

    python -m tools.benchmark                                     # yoloe n & s at 320/416
    python -m tools.benchmark --models models/raahi_yoloe_s_416.onnx --sizes 416
    python -m tools.benchmark --frames samples/ --n 100

On the Mac use CoreML (.mlpackage) / --device mps; here on Windows it's CPU.
"""
from __future__ import annotations

import argparse
import statistics
import time
from pathlib import Path

import cv2
import numpy as np

from server.perception.detector import Detector


def load_frames(folder: str | None, width: int = 416) -> list[np.ndarray]:
    frames = []
    if folder and Path(folder).is_dir():
        for f in sorted(Path(folder).iterdir()):
            img = cv2.imread(str(f))
            if img is not None:
                h, w = img.shape[:2]
                frames.append(cv2.resize(img, (width, int(h * width / w))))
    if not frames:
        rng = np.random.default_rng(0)
        frames = [rng.integers(0, 255, (312, width, 3), dtype=np.uint8) for _ in range(5)]
    return frames


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", nargs="+", default=["yoloe-26n-seg.pt", "yoloe-26s-seg.pt"])
    ap.add_argument("--sizes", nargs="+", type=int, default=[320, 416])
    ap.add_argument("--frames", default="samples")
    ap.add_argument("--n", type=int, default=50)
    ap.add_argument("--device", default=None)
    args = ap.parse_args()

    frames = load_frames(args.frames)
    print(f"{'model':40s} {'imgsz':>5s} {'mean':>7s} {'p95':>7s}  dets/frame")
    for m in args.models:
        for s in args.sizes:
            det = Detector(m, s, args.device)
            for f in frames[:3]:
                det(f)                      # warm-up
            ts, nd = [], 0
            for i in range(args.n):
                f = frames[i % len(frames)]
                t = time.perf_counter()
                nd += len(det(f))
                ts.append((time.perf_counter() - t) * 1000)
            ts.sort()
            print(f"{m:40s} {s:5d} {statistics.mean(ts):6.1f}ms {ts[int(0.95*(len(ts)-1))]:6.1f}ms  {nd/args.n:.1f}")


if __name__ == "__main__":
    main()
