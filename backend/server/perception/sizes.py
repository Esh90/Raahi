"""Real-world object heights for the pinhole distance fallback:  Z = H_real * fy / h_px

Heights come from shared/classes.json (single source). Member B's engine calls height_m(cls);
only use pinhole when the box is NOT cut off by the frame edge.
"""
from __future__ import annotations

from server.perception.classes import all_classes, by_id

# shared/classes.json heights + a few aliases/extras the engine may pass
OBJECT_HEIGHTS_M: dict[str, float] = {
    c["id"]: c["height_m"] for c in all_classes() if c.get("height_m")
}
OBJECT_HEIGHTS_M.update({"backpack": 0.45, "plant": 0.5, "fan stand": 1.1})

EDGE_MARGIN_PX = 4

OBJECT_WIDTHS_M: dict[str, float] = {
    c["id"]: c["width_m"] for c in all_classes() if c.get("width_m")
}
OBJECT_WIDTHS_M.update({"backpack": 0.35, "plant": 0.4, "fan stand": 0.45})


def width_m(cls: str) -> float | None:
    """Typical real width (m). Used when a close object is cut off at the top/bottom (height unusable)."""
    key = (cls or "").strip().lower()
    for k in (key, key.replace(" ", "_"), key.replace("_", " ")):
        if k in OBJECT_WIDTHS_M:
            return OBJECT_WIDTHS_M[k]
    return None


def height_m(cls: str) -> float | None:
    key = (cls or "").strip().lower()
    for k in (key, key.replace(" ", "_"), key.replace("_", " ")):
        if k in OBJECT_HEIGHTS_M:
            return OBJECT_HEIGHTS_M[k]
    return None


def real_height_m(cls_id: str) -> float | None:
    c = by_id().get(cls_id)
    return c.get("height_m") if c else height_m(cls_id)


def is_cut_off(box: tuple[int, int, int, int], frame_h: int, frame_w: int | None = None) -> bool:
    x1, y1, x2, y2 = box
    return y1 <= EDGE_MARGIN_PX or y2 >= frame_h - EDGE_MARGIN_PX


def pinhole_distance_m(cls_id: str, box: tuple[int, int, int, int], fy: float, frame_h: int) -> float | None:
    """Distance in metres, or None if unknown height / box cut off / degenerate."""
    h_real = real_height_m(cls_id)
    h_px = box[3] - box[1]
    if not h_real or h_px < 8 or is_cut_off(box, frame_h):
        return None
    return h_real * fy / h_px
