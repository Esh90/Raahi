"""One-shot "what's ahead?" — nearest labelled + depth objects, each with metres."""
from __future__ import annotations

import numpy as np

from server.config import DESCRIBE_MAX, OBJ_CLIP_EN, WARN_M
from server.engine import geometry as g
from server.engine.objects import depth_objects, iou
from server.engine.types import KIND_DESCRIBE, PRI_DESCRIBE, Decision
from server.perception.detector import Detection


def compose(
    detections: list[Detection],
    depth_m: np.ndarray | None,
    frame_shape: tuple[int, ...],
) -> Decision:
    h, w = int(frame_shape[0]), int(frame_shape[1])
    items: list[tuple[float, str, str, int]] = []  # z, obj_clip, pos, steps
    seen_boxes: list[tuple[int, int, int, int]] = []

    for det in detections:
        z = g.distance_for_box(depth_m, det.cls, det.box, (h, w))
        if not np.isfinite(z):
            continue
        cx, _ = g.box_center(det.box)
        pos = g.position_from_cx(cx, w)
        n = g.steps_from_metres(z)
        items.append((z, g.obj_clip(det.cls), pos, n))
        seen_boxes.append(det.box)

    centre_close = False
    if depth_m is not None:
        depths = g.zone_depths(depth_m)
        centre_close = depths[1] < WARN_M

    for obj in depth_objects(depth_m):
        if any(iou(obj["box"], b) > 0.35 for b in seen_boxes):
            continue
        z = float(obj["dist_m"])
        pos = obj["zone"] if obj["zone"] in {"left", "right", "ahead"} else "ahead"
        items.append((z, "obj_unknown", pos, g.steps_from_metres(z)))

    if not items and centre_close and depth_m is not None:
        zc = g.zone_depths(depth_m)[1]
        items.append((zc, "obj_unknown", "ahead", g.steps_from_metres(zc)))

    items.sort(key=lambda t: t[0])
    items = items[:DESCRIBE_MAX]

    if not items:
        return Decision(
            kind=KIND_DESCRIBE,
            key="describe:empty",
            clips=("sys_nothing_ahead",),
            priority=PRI_DESCRIBE,
            interrupt=False,
            english="Nothing ahead",
        )

    clips: list[str] = []
    bits: list[str] = []
    for z, obj, pos, n in items:
        clips.extend([obj, g.pos_clip(pos), g.steps_clip(n)])
        name = OBJ_CLIP_EN.get(obj, "obstacle")
        bits.append(f"{name} {pos}, {n} steps")

    return Decision(
        kind=KIND_DESCRIBE,
        key="describe:" + "|".join(f"{o}:{p}:{n}" for _, o, p, n in items),
        clips=tuple(clips),
        priority=PRI_DESCRIBE,
        interrupt=False,
        english="Ahead: " + "; ".join(bits),
        dist_m=items[0][0],
    )
