"""Multiple obstacles from the depth map, each with its own metres."""
from __future__ import annotations

from typing import Any

import cv2
import numpy as np

from server.config import OVERLAY_MAX, WARN_M
from server.engine import geometry as g


def iou(a: tuple[int, int, int, int], b: tuple[int, int, int, int]) -> float:
    ax1, ay1, ax2, ay2 = a
    bx1, by1, bx2, by2 = b
    ix1, iy1 = max(ax1, bx1), max(ay1, by1)
    ix2, iy2 = min(ax2, bx2), min(ay2, by2)
    inter = max(0, ix2 - ix1) * max(0, iy2 - iy1)
    if inter <= 0:
        return 0.0
    area_a = max(1, (ax2 - ax1) * (ay2 - ay1))
    area_b = max(1, (bx2 - bx1) * (by2 - by1))
    return inter / float(area_a + area_b - inter)


def depth_objects(
    depth: np.ndarray | None,
    warn_m: float = WARN_M,
    max_objects: int = OVERLAY_MAX,
) -> list[dict[str, Any]]:
    """Foreground blobs + one box per close zone. Sorted nearest-first."""
    if depth is None or depth.size == 0:
        return []
    blobs = _connected(depth, warn_m)
    zones = _per_zone(depth, warn_m)
    merged: list[dict[str, Any]] = []
    for obj in sorted(blobs + zones, key=lambda o: o["dist_m"]):
        if any(iou(obj["box"], k["box"]) > 0.45 for k in merged):
            continue
        merged.append(obj)
        if len(merged) >= max_objects:
            break
    return merged


def _connected(depth: np.ndarray, warn_m: float) -> list[dict[str, Any]]:
    h, w = depth.shape[:2]
    y0, y1 = g.obstacle_roi_rows(h)
    patch = depth[y0:y1]
    valid = g.valid_mask(patch)
    if int(valid.sum()) < 40:
        return []
    col_bg = np.nanmedian(np.where(valid, patch, np.nan), axis=0)
    bg = np.broadcast_to(col_bg, patch.shape)
    p50 = float(np.nanmedian(patch[valid]))
    fg = valid & (patch < warn_m) & ((patch < bg - 0.28) | (patch < p50 - 0.35))
    u8 = (fg.astype(np.uint8) * 255)
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5))
    u8 = cv2.morphologyEx(u8, cv2.MORPH_OPEN, k)
    n_lab, labels, stats, _ = cv2.connectedComponentsWithStats(u8, connectivity=8)
    min_area = max(80, int(0.01 * patch.size))
    out: list[dict[str, Any]] = []
    for i in range(1, n_lab):
        x, y, bw, bh, area = (int(v) for v in stats[i])
        if area < min_area or bw < 10 or bh < 10:
            continue
        z = float(np.median(patch[labels == i]))
        if not np.isfinite(z) or z >= warn_m:
            continue
        box = (x, y0 + y, x + bw, y0 + y + bh)
        cx = x + bw / 2.0
        out.append(
            {
                "box": box,
                "dist_m": z,
                "zone": g.position_from_cx(cx, w),
                "label": "obstacle",
            }
        )
    return out


def _per_zone(depth: np.ndarray, warn_m: float) -> list[dict[str, Any]]:
    """Separate L/C/R metres so a wall-plus-chair is not one box."""
    h, w = depth.shape[:2]
    y0, y1 = g.obstacle_roi_rows(h)
    depths = g.zone_depths(depth)
    names = ("left", "ahead", "right")
    out: list[dict[str, Any]] = []
    for z, (x0, x1), name in zip(depths, g.zone_ranges(w), names):
        if not np.isfinite(z) or z >= warn_m:
            continue
        patch = depth[y0:y1, x0:x1]
        near = g.valid_mask(patch) & (patch <= z + 0.25)
        if int(near.sum()) < 20:
            box = (x0, y0, x1, y1)
        else:
            ys, xs = np.where(near)
            box = (
                x0 + int(xs.min()),
                y0 + int(ys.min()),
                x0 + int(xs.max()) + 1,
                y0 + int(ys.max()) + 1,
            )
        out.append({"box": box, "dist_m": float(z), "zone": name, "label": "obstacle"})
    return out


def overlay_items(
    detections,
    depth: np.ndarray | None,
    hw: tuple[int, int],
) -> tuple[dict, ...]:
    h, w = hw
    items: list[dict[str, Any]] = []
    det_boxes: list[tuple[int, int, int, int]] = []
    for det in detections:
        z = (
            g.distance_for_box(depth, det.cls, det.box, hw)
            if depth is not None
            else float("nan")
        )
        dist = None if not np.isfinite(z) else round(float(z), 2)
        items.append(
            {
                "label": det.cls,
                "box": g.box_normalized(det.box, h, w),
                "dist_m": dist,
                "conf": round(float(det.conf), 2),
            }
        )
        det_boxes.append(det.box)

    for obj in depth_objects(depth):
        if any(iou(obj["box"], db) > 0.35 for db in det_boxes):
            continue
        items.append(
            {
                "label": obj["label"],
                "box": g.box_normalized(obj["box"], h, w),
                "dist_m": round(float(obj["dist_m"]), 2),
                "conf": 1.0,
                "zone": obj["zone"],
            }
        )
    items.sort(key=lambda o: o["dist_m"] if o["dist_m"] is not None else 99)
    return tuple(items[:OVERLAY_MAX])
