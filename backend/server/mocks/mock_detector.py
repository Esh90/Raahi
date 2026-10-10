"""Plausible boxes so Member B is not blocked before YOLOE is ready.

Esha can replace this with a richer mock (or delete the fallback in detector.py).
"""
from __future__ import annotations

import time

import numpy as np

from server.config import MOCK
from server.perception.detector import Detection


def detect(frame_bgr: np.ndarray) -> list[Detection]:
    # Live Mac (MOCK=0) must not invent chairs/doors — unnamed depth still guides.
    if not MOCK:
        return []
    if frame_bgr is None or frame_bgr.size == 0:
        return []
    h, w = frame_bgr.shape[:2]
    t = time.monotonic()
    jitter = int(6 * np.sin(t * 0.7))

    chair = (
        int(w * 0.34) + jitter,
        int(h * 0.42),
        int(w * 0.64) + jitter,
        int(h * 0.92),
    )
    door = (
        int(w * 0.72),
        int(h * 0.18),
        int(w * 0.96),
        int(h * 0.88),
    )
    return [
        Detection(cls="chair", conf=0.82, box=_clamp(chair, h, w)),
        Detection(cls="door", conf=0.71, box=_clamp(door, h, w)),
    ]


def _clamp(box: tuple[int, int, int, int], h: int, w: int) -> tuple[int, int, int, int]:
    x1, y1, x2, y2 = box
    return (
        max(0, min(w - 2, x1)),
        max(0, min(h - 2, y1)),
        max(1, min(w, x2)),
        max(1, min(h, y2)),
    )
