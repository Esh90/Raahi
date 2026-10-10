#!/usr/bin/env python3
"""Push synthetic (or webcam) JPEGs at the live server so B can test without the APK.

    python tools/send_test_frame.py
    python tools/send_test_frame.py --webcam --url ws://127.0.0.1:8000/ws
"""
from __future__ import annotations

import argparse
import json
import time

import cv2
import numpy as np

try:
    from websockets.sync.client import connect
except ImportError:
    connect = None


def encode(frame) -> bytes:
    ok, buf = cv2.imencode(".jpg", frame, [int(cv2.IMWRITE_JPEG_QUALITY), 60])
    if not ok:
        raise RuntimeError("jpeg encode failed")
    return buf.tobytes()


def synthetic(t: float) -> np.ndarray:
    h, w = 312, 416
    img = np.zeros((h, w, 3), np.uint8)
    # ceiling → wall → floor
    img[: int(h * 0.28)] = (48, 42, 38)
    img[int(h * 0.28) : int(h * 0.52)] = (62, 58, 52)
    for y in range(int(h * 0.52), h):
        k = (y - int(h * 0.52)) / max(1, h - int(h * 0.52))
        img[y] = (int(28 + 40 * k), int(32 + 36 * k), int(36 + 32 * k))
    cv2.line(img, (0, int(h * 0.52)), (w, int(h * 0.52)), (90, 80, 70), 1)
    x = int(w * (0.34 + 0.08 * np.sin(t)))
    cv2.rectangle(img, (x, int(h * 0.42)), (x + 88, int(h * 0.90)), (70, 115, 168), -1)
    cv2.rectangle(img, (x + 8, int(h * 0.48)), (x + 80, int(h * 0.62)), (40, 70, 110), -1)
    cv2.rectangle(img, (int(w * 0.72), int(h * 0.16)), (int(w * 0.96), int(h * 0.90)), (150, 148, 142), -1)
    cv2.rectangle(img, (int(w * 0.76), int(h * 0.28)), (int(w * 0.93), int(h * 0.78)), (90, 120, 140), -1)
    return img


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--url", default="ws://127.0.0.1:8000/ws")
    p.add_argument("--webcam", action="store_true")
    p.add_argument("--n", type=int, default=0, help="0 = until Ctrl-C")
    p.add_argument("--sleep", type=float, default=0.0, help="seconds between frames")
    args = p.parse_args()
    if connect is None:
        raise SystemExit("pip install websockets")

    cap = cv2.VideoCapture(0) if args.webcam else None
    n = 0
    with connect(args.url, max_size=2**23) as ws:
        ws.send(json.dumps({"type": "hello", "client": "bench", "version": "0.1"}))
        print(ws.recv())
        t0 = time.time()
        while args.n == 0 or n < args.n:
            if cap is not None:
                ok, frame = cap.read()
                if not ok:
                    break
                h, w = frame.shape[:2]
                frame = cv2.resize(frame, (416, int(h * 416 / w)))
            else:
                frame = synthetic(time.time() - t0)
            ws.send(encode(frame))
            reply = json.loads(ws.recv())
            n += 1
            lat = reply.get("latency_ms", {})
            print(
                f"{n:4d}  {reply.get('mode'):8}  {lat.get('total')}ms  "
                f"{reply.get('clips')}  {reply.get('english')}"
            )
            if args.sleep:
                time.sleep(args.sleep)
    if cap is not None:
        cap.release()


if __name__ == "__main__":
    main()
