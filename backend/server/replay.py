"""Replay a test video through detect + depth + engine.

    python -m server.replay path/to/room.mp4
    python -m server.replay path/to/room.mp4 --max-frames 200
"""
from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

import cv2

# allow `python server/replay.py`
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from server.engine.pipeline import GuidanceEngine  # noqa: E402
from server.perception.depth import estimate  # noqa: E402
from server.perception.detector import detect  # noqa: E402


def replay(path: str, max_frames: int = 0, print_idle: bool = False) -> None:
    cap = cv2.VideoCapture(path)
    if not cap.isOpened():
        raise SystemExit(f"cannot open {path}")
    engine = GuidanceEngine()
    idx = 0
    last_depth = None
    t_all = time.perf_counter()
    print(f"{'n':>4} {'kind':<16} {'clips':<48} {'en'}")
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        idx += 1
        if max_frames and idx > max_frames:
            break
        dets = detect(frame)
        if idx % 2 == 1 or last_depth is None:
            last_depth = estimate(frame)
        decision, reply = engine.process(frame.shape, dets, last_depth)
        if not reply.clips and not print_idle:
            continue
        clips = ",".join(reply.clips) if reply.clips else "—"
        print(f"{idx:4d} {decision.kind:<16} {clips:<48} {reply.english or decision.english}")
    dt = time.perf_counter() - t_all
    fps = idx / dt if dt else 0
    print(f"\n{idx} frames in {dt:.1f}s ({fps:.1f} fps)  last mode={engine.state.mode}")
    cap.release()


def main() -> None:
    p = argparse.ArgumentParser(description="Replay Raahi engine on a video")
    p.add_argument("video")
    p.add_argument("--max-frames", type=int, default=0)
    p.add_argument("--all", action="store_true", help="print idle frames too")
    args = p.parse_args()
    replay(args.video, args.max_frames, print_idle=args.all)


if __name__ == "__main__":
    main()
