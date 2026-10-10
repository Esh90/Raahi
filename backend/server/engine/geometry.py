from __future__ import annotations

import math

import numpy as np

from server.config import (
    CLASS_TO_OBJ_CLIP,
    DEPTH_MAX_M,
    DEPTH_MIN_M,
    FX,
    FY,
    LATERAL_MARGIN_M,
    MIN_DEPTH_PIXELS,
    MOVE_STEPS_MAX,
    PINHOLE_BLEND,
    ROI_BOTTOM,
    ROI_OBSTACLE_BOTTOM,
    ROI_TOP,
    STEP_M,
    ZONE_SPLITS,
)
from server.perception.sizes import height_m, width_m


def roi_rows(h: int) -> tuple[int, int]:
    y0 = int(h * ROI_TOP)
    y1 = max(y0 + 1, int(h * ROI_BOTTOM))
    return y0, y1


def obstacle_roi_rows(h: int) -> tuple[int, int]:
    """Rows used for zone metres — cuts the floor band DA paints as near."""
    y0 = int(h * ROI_TOP)
    y1 = max(y0 + 1, int(h * ROI_OBSTACLE_BOTTOM))
    return y0, y1


def zone_ranges(w: int) -> list[tuple[int, int]]:
    edges = [int(w * s) for s in ZONE_SPLITS]
    edges[-1] = w
    return [(edges[i], edges[i + 1]) for i in range(3)]


def valid_mask(depth: np.ndarray) -> np.ndarray:
    return np.isfinite(depth) & (depth > DEPTH_MIN_M) & (depth < DEPTH_MAX_M)


def percentile_depth(
    depth: np.ndarray, y0: int, y1: int, x0: int, x1: int, q: float = 10.0
) -> float:
    patch = depth[y0:y1, x0:x1]
    if patch.size == 0:
        return float("inf")
    vals = patch[valid_mask(patch)]
    if vals.size < MIN_DEPTH_PIXELS:
        return float("inf")
    return float(np.percentile(vals, q))


def nearest_surface_depth(
    depth: np.ndarray, y0: int, y1: int, x0: int, x1: int
) -> float:
    """Metres to the nearest real surface, not the wall behind it.

    10th percentile of a whole zone is the background if the obstacle is
    small. If a near cluster sits clearly in front of the median, use that.
    """
    patch = depth[y0:y1, x0:x1]
    if patch.size == 0:
        return float("inf")
    vals = patch[valid_mask(patch)]
    if vals.size < MIN_DEPTH_PIXELS:
        return float("inf")
    p5 = float(np.percentile(vals, 5))
    p50 = float(np.percentile(vals, 50))
    gap = 0.30
    if p5 + gap < p50:
        near = vals[vals <= p5 + gap]
        need = max(MIN_DEPTH_PIXELS, int(0.02 * vals.size))
        if near.size >= need:
            return float(np.percentile(near, 20))
    return float(np.percentile(vals, 8))


def zone_depths(depth: np.ndarray) -> tuple[float, float, float]:
    h, w = depth.shape[:2]
    y0, y1 = obstacle_roi_rows(h)
    out: list[float] = []
    for x0, x1 in zone_ranges(w):
        ya, yb = _trim_floor_rows(depth, y0, y1, x0, x1)
        out.append(nearest_surface_depth(depth, ya, yb, x0, x1))
    return out[0], out[1], out[2]


def _trim_floor_rows(
    depth: np.ndarray, y0: int, y1: int, x0: int, x1: int
) -> tuple[int, int]:
    """Drop a smooth full-width ground ramp; keep beds/people/walls."""
    patch = depth[y0:y1, x0:x1]
    n = int(patch.shape[0])
    if n < 16 or patch.shape[1] < 8:
        return y0, y1
    # vectorised per-row 10th percentile of valid pixels (was a Python loop: ~200 ms/frame on CPU;
    # np.nanpercentile is just as slow - it loops per row internally). One sort + index instead.
    valid = valid_mask(patch)
    counts = valid.sum(axis=1)
    srt = np.sort(np.where(valid, patch, np.inf), axis=1)
    k = np.clip(((counts - 1) * 0.10).astype(np.int64), 0, patch.shape[1] - 1)
    row_p10 = srt[np.arange(n), k].astype(np.float32)
    row_p10[counts < 5] = np.nan
    upper = float(np.nanmedian(row_p10[: n // 2]))
    lower = float(np.nanmedian(row_p10[int(n * 0.75) :]))
    if not (np.isfinite(upper) and np.isfinite(lower)):
        return y0, y1
    if lower >= upper - 0.35:
        return y0, y1
    close = patch[int(n * 0.75) :] < (lower + 0.15)
    col_frac = float(close.any(axis=0).mean()) if close.size else 0.0
    last = row_p10[int(n * 0.75) :]
    last = last[np.isfinite(last)]
    flat_step = last.size > 3 and float(np.nanstd(last)) < 0.12
    if col_frac > 0.9 and not flat_step:
        return y0, y0 + int(n * 0.75)
    return y0, y1


def zone_color(d: float, danger_m: float, warn_m: float) -> str:
    if d < danger_m:
        return "red"
    if d < warn_m:
        return "yellow"
    return "green"


def zone_colors(
    depths: tuple[float, float, float], danger_m: float, warn_m: float
) -> tuple[str, str, str]:
    return tuple(zone_color(d, danger_m, warn_m) for d in depths)  # type: ignore[return-value]


def clamp_box(box: tuple[int, int, int, int], h: int, w: int) -> tuple[int, int, int, int]:
    x1, y1, x2, y2 = (int(v) for v in box)
    x1 = max(0, min(w - 1, x1))
    x2 = max(0, min(w, x2))
    y1 = max(0, min(h - 1, y1))
    y2 = max(0, min(h, y2))
    if x2 <= x1:
        x2 = min(w, x1 + 1)
    if y2 <= y1:
        y2 = min(h, y1 + 1)
    return x1, y1, x2, y2


def box_inner50(box: tuple[int, int, int, int], h: int, w: int) -> tuple[int, int, int, int]:
    x1, y1, x2, y2 = clamp_box(box, h, w)
    bw, bh = x2 - x1, y2 - y1
    return (
        int(x1 + 0.25 * bw),
        int(y1 + 0.25 * bh),
        int(x2 - 0.25 * bw),
        int(y2 - 0.25 * bh),
    )


def median_box_depth(depth: np.ndarray, box: tuple[int, int, int, int]) -> float:
    h, w = depth.shape[:2]
    x1, y1, x2, y2 = box_inner50(box, h, w)
    patch = depth[y1:y2, x1:x2]
    if patch.size == 0:
        return float("nan")
    vals = patch[valid_mask(patch)]
    if vals.size < max(8, MIN_DEPTH_PIXELS // 2):
        return float("nan")
    return float(np.median(vals))


def box_truncated(box: tuple[int, int, int, int], h: int, w: int, margin: int = 2) -> bool:
    x1, y1, x2, y2 = (int(v) for v in box)
    return x1 <= margin or y1 <= margin or x2 >= w - margin or y2 >= h - margin


def pinhole_z(cls: str, box: tuple[int, int, int, int], fy: float = FY) -> float | None:
    H = height_m(cls)
    if H is None:
        return None
    _x1, y1, _x2, y2 = box
    h_px = max(1.0, float(y2 - y1))
    return float(H * fy / h_px)


def pinhole_width_z(cls: str, box: tuple[int, int, int, int], fx: float = FX) -> float | None:
    W = width_m(cls)
    if W is None:
        return None
    x1, _y1, x2, _y2 = box
    return float(W * fx / max(1.0, float(x2 - x1)))


def size_distance(cls: str, box: tuple[int, int, int, int], h: int, w: int, fx: float = FX, fy: float = FY) -> float | None:
    """Distance from the object's known size: height if the box is whole, else width if only the top/bottom is
    cut off (a person sitting close to the camera: face + shoulders fill the frame, the width still measures)."""
    x1, y1, x2, y2 = (int(v) for v in box)
    m = 2
    cut_v = y1 <= m or y2 >= h - m
    cut_h = x1 <= m or x2 >= w - m
    if not cut_v and not cut_h:
        return pinhole_z(cls, box, fy)
    if cut_v and not cut_h:
        return pinhole_width_z(cls, box, fx)
    return None


def distance_for_box(
    depth: np.ndarray | None,
    cls: str,
    box: tuple[int, int, int, int],
    frame_hw: tuple[int, int],
    fy: float = FY,
) -> float:
    h, w = frame_hw
    z_depth = float("nan")
    if depth is not None:
        z_depth = median_box_depth(depth, box)
    z_pin = size_distance(cls, box, h, w, fy=fy)
    return fuse_metres(z_depth, z_pin)


def fuse_metres(z_depth: float, z_pinhole: float | None) -> float:
    """Blend when depth and size-based distance agree within 2x; otherwise trust the CLOSER one.

    Safety first: monocular depth is unreliable very close to the camera (a person 0.5 m away read as 2.3 m in
    testing), and saying "obstacle" a bit early is far better than "path clear" in front of someone."""
    d_ok = math.isfinite(z_depth)
    p_ok = z_pinhole is not None and math.isfinite(z_pinhole) and z_pinhole > 0
    if d_ok and not p_ok:
        return float(z_depth)
    if p_ok and not d_ok:
        return float(z_pinhole)
    if not d_ok and not p_ok:
        return float("inf")
    ratio = float(z_depth) / float(z_pinhole)
    if 0.5 <= ratio <= 2.0:
        b = PINHOLE_BLEND
        return float((1.0 - b) * z_depth + b * z_pinhole)
    return float(min(z_depth, z_pinhole))


def steps_from_metres(z: float, step_m: float = STEP_M) -> int:
    if not math.isfinite(z):
        return 9
    return max(1, int(math.ceil(z / step_m)))


def steps_clip(n: int) -> str:
    if n >= 9:
        return "steps_far"
    return f"steps_{min(8, max(1, n))}"


def lateral_metres(
    box: tuple[int, int, int, int],
    side: str,
    z: float,
    fx: float = FX,
    width: int = 0,
) -> float:
    x1, _y1, x2, _y2 = box
    cx = width / 2.0
    edge = float(x1 if side == "left" else x2)
    return abs(edge - cx) * z / max(fx, 1e-6) + LATERAL_MARGIN_M


def move_steps(lateral: float, step_m: float = STEP_M) -> int:
    return int(min(MOVE_STEPS_MAX, max(1, math.ceil(lateral / step_m))))


def position_from_cx(cx: float, w: int) -> str:
    if cx < w * ZONE_SPLITS[1]:
        return "left"
    if cx > w * ZONE_SPLITS[2]:
        return "right"
    return "ahead"


def pos_clip(side: str) -> str:
    return {"left": "pos_left", "right": "pos_right", "ahead": "pos_ahead"}.get(
        side, "pos_ahead"
    )


def obj_clip(cls: str) -> str:
    key = (cls or "unknown").strip().lower()
    if key in CLASS_TO_OBJ_CLIP:
        return CLASS_TO_OBJ_CLIP[key]
    return CLASS_TO_OBJ_CLIP.get(key.replace("_", " "), "obj_unknown")


def overlaps_center_roi(box: tuple[int, int, int, int], h: int, w: int) -> bool:
    x1, y1, x2, y2 = box
    ry0, ry1 = roi_rows(h)
    zx0, zx1 = zone_ranges(w)[1]
    return x2 > zx0 and x1 < zx1 and y2 > ry0 and y1 < ry1


def box_center(box: tuple[int, int, int, int]) -> tuple[float, float]:
    x1, y1, x2, y2 = box
    return (x1 + x2) / 2.0, (y1 + y2) / 2.0


def box_normalized(box: tuple[int, int, int, int], h: int, w: int) -> list[float]:
    x1, y1, x2, y2 = clamp_box(box, h, w)
    return [
        round(x1 / w, 4),
        round(y1 / h, 4),
        round(x2 / w, 4),
        round(y2 / h, 4),
    ]


def bearing_deg(box_cx: float, image_cx: float, fx: float = FX) -> float:
    return math.degrees(math.atan((box_cx - image_cx) / max(fx, 1e-6)))


def finite_or(value: float, fallback: float) -> float:
    return value if math.isfinite(value) else fallback
