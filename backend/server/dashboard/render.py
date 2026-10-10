"""Clean overlays for the judge dashboard — no text stacked on the video."""
from __future__ import annotations

import cv2
import numpy as np

from server.config import DANGER_M, WARN_M
from server.engine.geometry import obstacle_roi_rows, zone_ranges
from server.perception.detector import Detection

_ZONE_BGR = {
    "green": (90, 200, 90),
    "yellow": (40, 185, 230),
    "red": (70, 70, 255),
}


def annotate(
    frame_bgr: np.ndarray,
    detections: list[Detection],
    overlay: list[dict],
    zone_colors: tuple[str, str, str] | None,
    zone_depths: tuple[float, float, float] | None,
    spoken: str = "",
) -> np.ndarray:
    _ = spoken, zone_depths  # spoken + zone metres live in the HTML, not on the frame
    vis = frame_bgr.copy()
    h, w = vis.shape[:2]
    y0, y1 = obstacle_roi_rows(h)
    colors = zone_colors or ("green", "green", "green")

    overlay_layer = vis.copy()
    for (x0, x1), color in zip(zone_ranges(w), colors):
        bgr = _ZONE_BGR.get(color, (160, 160, 160))
        cv2.rectangle(overlay_layer, (x0, y0), (x1, y1), bgr, -1)
        cv2.rectangle(vis, (x0, y0), (x1, y0 + 5), bgr, -1)
    vis[y0:y1] = cv2.addWeighted(vis[y0:y1], 0.82, overlay_layer[y0:y1], 0.18, 0)

    for i in range(1, 3):
        x = zone_ranges(w)[i][0]
        cv2.line(vis, (x, y0), (x, y1), (255, 255, 255), 1, cv2.LINE_AA)

    items = overlay or [
        {
            "label": d.cls,
            "box": [d.box[0] / w, d.box[1] / h, d.box[2] / w, d.box[3] / h],
            "dist_m": None,
        }
        for d in detections
    ]
    used_label_rows: list[tuple[int, int, int]] = []  # x1, x2, y
    for item in items:
        x1n, y1n, x2n, y2n = item["box"]
        x1, y1b, x2, y2b = int(x1n * w), int(y1n * h), int(x2n * w), int(y2n * h)
        dist = item.get("dist_m")
        if dist is not None and dist < DANGER_M:
            color = (70, 70, 255)
        elif dist is not None and dist < WARN_M:
            color = (40, 185, 230)
        elif dist is not None:
            color = (90, 200, 90)
        else:
            color = (200, 200, 200)
        cv2.rectangle(vis, (x1, y1b), (x2, y2b), color, 2)

        caption = item.get("label", "?")
        if dist is not None:
            caption = f"{caption}  {dist:.1f}m"
        (tw, th), _ = cv2.getTextSize(caption, cv2.FONT_HERSHEY_SIMPLEX, 0.55, 1)
        pad = 6
        ly = y1b - th - 12
        if ly < th + 8:
            ly = y1b + th + 10
        # nudge down if another label occupies this band
        for ux1, ux2, uy in used_label_rows:
            if abs(ly - uy) < th + 10 and not (x1 + tw + 2 * pad < ux1 or x1 > ux2):
                ly = uy + th + 12
        used_label_rows.append((x1, x1 + tw + 2 * pad, ly))
        cv2.rectangle(
            vis,
            (x1, ly - th - pad),
            (x1 + tw + 2 * pad, ly + pad),
            color,
            -1,
        )
        cv2.putText(
            vis,
            caption,
            (x1 + pad, ly),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.55,
            (16, 16, 18),
            1,
            cv2.LINE_AA,
        )
    return vis


def colorize_depth(depth_m: np.ndarray | None, h: int, w: int) -> np.ndarray:
    if depth_m is None:
        return np.zeros((h, w, 3), dtype=np.uint8)
    d = cv2.resize(depth_m, (w, h), interpolation=cv2.INTER_NEAREST)
    finite = np.isfinite(d)
    vis = np.zeros_like(d, dtype=np.float32)
    if finite.any():
        clipped = np.clip(d, 0.3, 6.0)
        vis[finite] = ((6.0 - clipped) / (6.0 - 0.3))[finite]
    u8 = np.clip(vis * 255, 0, 255).astype(np.uint8)
    color = cv2.applyColorMap(u8, cv2.COLORMAP_TURBO)
    color[~finite] = (18, 18, 22)
    return color


def encode_jpeg(bgr: np.ndarray, quality: int = 72) -> bytes:
    ok, buf = cv2.imencode(".jpg", bgr, [int(cv2.IMWRITE_JPEG_QUALITY), quality])
    return buf.tobytes() if ok else b""
