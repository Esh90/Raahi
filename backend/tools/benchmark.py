#!/usr/bin/env python3
"""Timing: depth (Member B) and optionally detector (Esha).

    python tools/benchmark.py
    python tools/benchmark.py --video room.mp4 --frames 100
    python tools/benchmark.py --depth-only
"""
from __future__ import annotations

import argparse
import statistics
import sys
import time
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def _frames(video: str | None, n: int, width: int = 416):
    if video:
        cap = cv2.VideoCapture(video)
        grabbed = 0
        while grabbed < n:
            ok, im = cap.read()
            if not ok:
                cap.set(cv2.CAP_PROP_POS_FRAMES, 0)
                ok, im = cap.read()
                if not ok:
                    break
            h, w = im.shape[:2]
            if w != width:
                im = cv2.resize(im, (width, int(h * width / w)))
            yield im
            grabbed += 1
        cap.release()
        return
    rng = np.random.default_rng(0)
    for _ in range(n):
        yield rng.integers(0, 255, (312, 416, 3), dtype=np.uint8)


def _stats(ms: list[float]) -> str:
    if not ms:
        return "no samples"
    return (
        f"n={len(ms)}  mean={statistics.mean(ms):.1f}  "
        f"p50={statistics.median(ms):.1f}  "
        f"p95={sorted(ms)[max(0, int(len(ms) * 0.95) - 1)]:.1f} ms"
    )


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--video")
    p.add_argument("--frames", type=int, default=50)
    p.add_argument("--depth-only", action="store_true")
    p.add_argument("--warmup", type=int, default=3)
    args = p.parse_args()

    from server.perception.depth import estimate, get_estimator

    est = get_estimator()
    print(f"depth backend: {est.backend}  device={est.device}")

    depth_ms: list[float] = []
    detect_ms: list[float] = []
    detect_fn = None
    if not args.depth_only:
        from server.perception.detector import detect as detect_fn  # type: ignore

    for i, frame in enumerate(_frames(args.video, args.frames + args.warmup)):
        t0 = time.perf_counter()
        d = estimate(frame)
        dt = (time.perf_counter() - t0) * 1000
        if i >= args.warmup:
            depth_ms.append(dt)
        if detect_fn is not None:
            t1 = time.perf_counter()
            _ = detect_fn(frame)
            if i >= args.warmup:
                detect_ms.append((time.perf_counter() - t1) * 1000)
        _ = d
        print(f"\r{i + 1}/{args.frames + args.warmup}", end="", flush=True)
    print()
    print("depth   ", _stats(depth_ms), "  target ≤ 80 ms")
    if detect_ms:
        print("detect  ", _stats(detect_ms), "  target ≤ 35 ms (Esha)")
    print("Pick the most accurate depth that stays under ~80 ms on this Mac.")


if __name__ == "__main__":
    main()
