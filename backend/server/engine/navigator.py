"""Zones, nearest obstacle, side + steps. Core of F1."""
from __future__ import annotations

from collections import deque

import numpy as np

from server.config import DANGER_M, FX, OBJ_CLIP_EN, PLURAL_CLIPS, SIDESTEP_DELTA_M, WARN_M

# Hysteresis: once in a state, stay until the centre is clearly past the threshold.
# Stops "stop / obstacle / stop" chatter when the distance hovers around 0.8 m or 2 m.
DANGER_EXIT_M = DANGER_M + 0.25
WARN_EXIT_M = WARN_M + 0.30
SIDE_STICKY_M = 0.40   # keep the previous left/right unless the other side is this much roomier
WALL_FLAT_M = 0.35     # all three zones within this -> a wall/flat surface ahead: turn, don't sidestep
from server.engine import geometry as g
from server.engine.objects import overlay_items
from server.engine.types import (
    KIND_CLEAR,
    KIND_OBSTACLE,
    KIND_URGENT,
    PRI_OBSTACLE,
    PRI_URGENT,
    Decision,
)
from server.perception.detector import Detection


class Navigator:
    def __init__(self) -> None:
        self._z_hist: deque[float] = deque(maxlen=5)
        self._in_danger = False
        self._in_warn = False
        self._last_side: str | None = None

    def reset(self) -> None:
        self._z_hist.clear()
        self._in_danger = False
        self._in_warn = False
        self._last_side = None

    def _sticky(self, side: str | None, left_d: float, right_d: float) -> str | None:
        """Don't flip left/right on noise: keep the previous side unless the other is clearly roomier."""
        if side in ("left", "right") and self._last_side in ("left", "right") and side != self._last_side:
            l = left_d if np.isfinite(left_d) else 9.0
            r = right_d if np.isfinite(right_d) else 9.0
            if abs(l - r) < SIDE_STICKY_M:
                side = self._last_side
        if side in ("left", "right"):
            self._last_side = side
        return side

    def step(
        self,
        frame_shape: tuple[int, ...],
        detections: list[Detection],
        depth_m: np.ndarray | None,
        fx: float = FX,
    ) -> Decision:
        h, w = int(frame_shape[0]), int(frame_shape[1])
        if depth_m is None:
            dummy = np.full((h, w), np.inf, dtype=np.float32)
            depths = (float("inf"), float("inf"), float("inf"))
        else:
            dummy = depth_m
            depths = g.zone_depths(depth_m)
        depths = _with_detections(depths, detections, depth_m, h, w)
        colors = g.zone_colors(depths, DANGER_M, WARN_M)
        overlay = overlay_items(detections, None if depth_m is None else dummy, (h, w))
        left_d, centre_d, right_d = depths

        self._in_danger = centre_d < (DANGER_EXIT_M if self._in_danger else DANGER_M)
        self._in_warn = self._in_danger or centre_d < (WARN_EXIT_M if self._in_warn else WARN_M)

        if self._in_danger:
            self._z_hist.append(centre_d)
            z = float(np.median(self._z_hist))
            side, n_move = _clearer_side(left_d, right_d, w=w, z=z, fx=fx)
            side = self._sticky(side, left_d, right_d)
            # Always tell the user what to do next: a side to step to, or step back.
            if side:
                clips = ["urg_stop", f"move_{side}_{n_move}"]
                english = f"Stop, move {n_move} {side}"
                key = f"urgent_stop:{side}:{n_move}"
            else:
                side = "back"
                clips = ["urg_stop", "move_back"]
                english = "Stop, step back"
                key = "urgent_stop:back"
            return Decision(
                kind=KIND_URGENT,
                key=key,
                clips=tuple(clips),
                priority=PRI_URGENT,
                urgent=True,
                interrupt=True,
                haptic="long",
                english=english,
                dist_m=z,
                steps_away=g.steps_from_metres(z),
                zone_depths=depths,
                zone_colors=colors,
                overlay=overlay,
                direction=side,
            )

        if self._in_warn:
            obstacle, z = _pick_obstacle(detections, dummy, (h, w), centre_d)
            self._z_hist.append(z)
            z = float(np.median(self._z_hist))
            side, n_move = _clearer_side(left_d, right_d, w=w, z=z, fx=fx)
            n_away = g.steps_from_metres(z)
            if side is None:
                # Both sides tight but not dangerous: still steer toward the roomier side.
                side, n_move = _roomier_side(left_d, right_d, w=w, z=z, fx=fx)
            side = self._sticky(side, left_d, right_d)
            finite = [d for d in depths if np.isfinite(d)]
            flat_wall = len(finite) == 3 and max(finite) < WARN_M and max(finite) - min(finite) < WALL_FLAT_M
            if side is None:
                # Everything around is blocked: stop and step back (never a bare "stop").
                return Decision(
                    kind=KIND_URGENT,
                    key="stop_blocked",
                    clips=("urg_stop_obstacle", "move_back"),
                    priority=PRI_URGENT,
                    urgent=True,
                    interrupt=True,
                    haptic="long",
                    english="Stop, obstacle ahead, step back",
                    dist_m=z,
                    steps_away=n_away,
                    zone_depths=depths,
                    zone_colors=colors,
                    overlay=overlay,
                    direction="back",
                )

            cls = obstacle.cls if obstacle else ("wall" if flat_wall else "unknown")
            obj = g.obj_clip(cls)
            name = OBJ_CLIP_EN.get(obj, cls)
            if flat_wall:
                # the whole view is one surface: sidestepping won't help, turn instead
                move_id = f"turn_{side}"
                english = f"{name} ahead, {n_away} steps, turn {side}"
            else:
                move_id = f"move_{side}_{n_move}"
                english = (
                    f"Stop, {name} ahead, {n_away} steps, "
                    f"move {n_move} {side}"
                )
            return Decision(
                kind=KIND_OBSTACLE,
                key=f"obstacle:{obj}:{'turn' if flat_wall else 'move'}:{side}:{n_away}:{n_move}",
                clips=(obj, "pos_ahead_pl" if obj in PLURAL_CLIPS else "pos_ahead", g.steps_clip(n_away), move_id),
                priority=PRI_OBSTACLE,
                urgent=False,
                interrupt=False,
                haptic="short",
                english=english,
                dist_m=z,
                steps_away=n_away,
                zone_depths=depths,
                zone_colors=colors,
                overlay=overlay,
                direction=side,
            )

        self._z_hist.clear()
        return Decision(
            kind=KIND_CLEAR,
            key="clear",
            clips=("sys_path_clear",),
            priority=0,
            english="Path clear",
            zone_depths=depths,
            zone_colors=colors,
            overlay=overlay,
        )


def _clearer_side(
    left_d: float,
    right_d: float,
    w: int,
    z: float,
    fx: float,
) -> tuple[str | None, int]:
    """Pick left/right from which zone is farther. None if both are equally tight."""
    if not np.isfinite(left_d):
        left_d = 0.0
    if not np.isfinite(right_d):
        right_d = 0.0
    if max(left_d, right_d) < DANGER_M:
        return None, 1
    if abs(left_d - right_d) < SIDESTEP_DELTA_M:
        # Same tightness: only sidestep if at least one side is actually open.
        if max(left_d, right_d) < WARN_M:
            return None, 1
    side = "left" if left_d >= right_d else "right"
    box = (w // 3, 0, 2 * w // 3, 1)
    n_move = g.move_steps(g.lateral_metres(box, side, z, fx=fx, width=w))
    return side, n_move


# Boxes that usually cover the background / far wall: their size says nothing about a nearby obstacle.
_BACKGROUND = {"wall", "window", "whiteboard", "projector_screen", "tv_led"}


def _with_detections(
    depths: tuple[float, float, float],
    detections: list[Detection],
    depth_m: np.ndarray | None,
    h: int,
    w: int,
) -> tuple[float, float, float]:
    """Lower each zone's metres with the detected objects in it (size-based + depth, closer wins).
    Monocular depth misses things very close to the camera (a person at 0.5 m read as 2.3 m); the detector's
    box size does not."""
    out = list(depths)
    ry0, ry1 = g.roi_rows(h)
    for det in detections:
        if det.cls in _BACKGROUND:
            continue
        x1, y1, x2, y2 = det.box
        if y2 <= ry0 or y1 >= ry1:
            continue
        z = g.distance_for_box(depth_m, det.cls, det.box, (h, w))
        if not np.isfinite(z):
            continue
        for i, (zx0, zx1) in enumerate(g.zone_ranges(w)):
            if x2 > zx0 and x1 < zx1:
                out[i] = min(out[i], z)
    return out[0], out[1], out[2]


def _roomier_side(
    left_d: float,
    right_d: float,
    w: int,
    z: float,
    fx: float,
) -> tuple[str | None, int]:
    """Fallback when neither side is clearly open: go to the larger one if it has room to stand."""
    left_d = left_d if np.isfinite(left_d) else 0.0
    right_d = right_d if np.isfinite(right_d) else 0.0
    if max(left_d, right_d) < DANGER_M:
        return None, 1
    side = "left" if left_d >= right_d else "right"
    box = (w // 3, 0, 2 * w // 3, 1)
    return side, g.move_steps(g.lateral_metres(box, side, z, fx=fx, width=w))


def _pick_obstacle(
    detections: list[Detection],
    depth: np.ndarray,
    hw: tuple[int, int],
    centre_d: float,
) -> tuple[Detection | None, float]:
    h, w = hw
    candidates: list[tuple[float, Detection]] = []
    for det in detections:
        if not g.overlaps_center_roi(det.box, h, w):
            continue
        z = g.distance_for_box(depth, det.cls, det.box, hw)
        if np.isfinite(z):
            candidates.append((z, det))
    if not candidates:
        return None, centre_d
    candidates.sort(key=lambda t: t[0])
    z, det = candidates[0]
    return det, z


def step(
    frame_shape: tuple[int, ...],
    detections: list[Detection],
    depth_m: np.ndarray | None,
    state=None,  # kept for the Section 5 signature
) -> Decision:
    """Stateless helper; the pipeline holds a Navigator instance."""
    _ = state
    return Navigator().step(frame_shape, detections, depth_m)
