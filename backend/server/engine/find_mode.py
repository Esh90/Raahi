"""Guide the user to a named target. Obstacle DANGER/WARN always overrides."""
from __future__ import annotations

import time
from collections import deque

import numpy as np

from server.config import (
    FIND_ALIASES,
    FIND_ALIGN_DEG,
    FIND_ARRIVED_M,
    FIND_LOST_S,
    FIND_SCAN_PERIOD_S,
    FIND_TARGETS,
    FX,
    OBJ_CLIP_EN,
)
from server.engine import geometry as g
from server.engine.types import (
    KIND_FIND_APPROACH,
    KIND_FIND_ARRIVED,
    KIND_FIND_LOST,
    KIND_FIND_SCAN,
    KIND_FIND_TURN,
    PRI_FIND,
    Decision,
)
from server.perception.detector import Detection


class FindGuide:
    def __init__(self) -> None:
        self.target: str | None = None
        self.last_seen: float = 0.0
        self.last_scan_ts: float = 0.0
        self._z_hist: deque[float] = deque(maxlen=5)
        self._ever_seen = False

    def start(self, target: str) -> None:
        t = (target or "").strip().lower()
        if t not in FIND_TARGETS:
            t = "door"
        self.target = t
        self.last_seen = 0.0
        self.last_scan_ts = 0.0
        self._z_hist.clear()
        self._ever_seen = False

    def stop(self) -> None:
        self.target = None
        self._z_hist.clear()
        self._ever_seen = False

    def step(
        self,
        detections: list[Detection],
        depth_m: np.ndarray | None,
        frame_shape: tuple[int, ...],
        now: float | None = None,
        fx: float = FX,
    ) -> Decision | None:
        if not self.target:
            return None
        now = time.monotonic() if now is None else now
        h, w = int(frame_shape[0]), int(frame_shape[1])
        match = _nearest_target(self.target, detections, depth_m, (h, w))

        if match is None:
            if self._ever_seen and self.last_seen > 0 and (now - self.last_seen) > FIND_LOST_S:
                self._ever_seen = False
                return Decision(
                    kind=KIND_FIND_LOST,
                    key="find_lost",
                    clips=("find_lost",),
                    priority=PRI_FIND,
                    english="Target lost, searching again",
                )
            if now - self.last_scan_ts >= FIND_SCAN_PERIOD_S:
                self.last_scan_ts = now
                return Decision(
                    kind=KIND_FIND_SCAN,
                    key="find_scan",
                    clips=("find_searching", "turn_slowly"),
                    priority=PRI_FIND,
                    english="Searching, turn slowly",
                )
            return Decision.idle()

        det, z = match
        self._ever_seen = True
        self.last_seen = now
        z_now = z
        self._z_hist.append(z)
        z = float(np.median(self._z_hist))
        cx, _cy = g.box_center(det.box)
        angle = g.bearing_deg(cx, w / 2.0, fx)
        name = OBJ_CLIP_EN.get(g.obj_clip(self.target), self.target)

        if z_now < FIND_ARRIVED_M and abs(angle) < FIND_ALIGN_DEG + 5:
            clip = f"find_arrived_{self.target}"
            return Decision(
                kind=KIND_FIND_ARRIVED,
                key=f"arrived:{self.target}",
                clips=(clip,),
                priority=PRI_FIND,
                haptic="arrived",
                interrupt=True,
                english=f"You have reached the {name}",
                dist_m=z,
            )

        if abs(angle) < FIND_ALIGN_DEG:
            n = g.steps_from_metres(z)
            obj = g.obj_clip(self.target)
            return Decision(
                kind=KIND_FIND_APPROACH,
                key=f"approach:{self.target}:{n}",
                clips=(obj, "pos_ahead", g.steps_clip(n)),
                priority=PRI_FIND,
                english=f"{name.capitalize()} straight ahead, {n} steps",
                dist_m=z,
                steps_away=n,
            )

        if angle < 0:
            return Decision(
                kind=KIND_FIND_TURN,
                key=f"turn:left:{self.target}",
                clips=("turn_left_slight",),
                priority=PRI_FIND,
                english="Turn a little left",
                dist_m=z,
            )
        return Decision(
            kind=KIND_FIND_TURN,
            key=f"turn:right:{self.target}",
            clips=("turn_right_slight",),
            priority=PRI_FIND,
            english="Turn a little right",
            dist_m=z,
        )


def _nearest_target(
    target: str,
    detections: list[Detection],
    depth: np.ndarray | None,
    hw: tuple[int, int],
) -> tuple[Detection, float] | None:
    aliases = FIND_ALIASES.get(target, {target})
    scored: list[tuple[float, Detection]] = []
    for det in detections:
        if det.cls.strip().lower() not in aliases:
            continue
        z = g.distance_for_box(depth, det.cls, det.box, hw)
        scored.append((z if np.isfinite(z) else 99.0, det))
    if not scored:
        return None
    scored.sort(key=lambda t: t[0])
    z, det = scored[0]
    return det, z
