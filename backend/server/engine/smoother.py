"""3-frame hysteresis (DANGER: 2 frames). Immediate kinds skip the hold."""
from __future__ import annotations

from server.config import SMOOTH_DANGER_FRAMES, SMOOTH_FRAMES
from server.engine.types import (
    KIND_CONFIRM,
    KIND_DESCRIBE,
    KIND_FIND_ARRIVED,
    KIND_FIND_LOST,
    KIND_IDLE,
    KIND_URGENT,
    Decision,
)

IMMEDIATE = {
    KIND_DESCRIBE,
    KIND_CONFIRM,
    KIND_FIND_ARRIVED,
    KIND_FIND_LOST,
}


class Smoother:
    def __init__(self) -> None:
        self._keys: list[str] = []
        self._held: Decision | None = None

    def reset(self) -> None:
        self._keys.clear()
        self._held = None

    def update(self, decision: Decision) -> Decision:
        if decision.kind in IMMEDIATE:
            self._keys.clear()
            self._held = decision
            return decision

        if decision.kind == KIND_IDLE:
            return self._held if self._held is not None else decision

        # Compare TOPICS (key minus numbers): "chair ahead, 3 steps, move left 2" and
        # "..., 2 steps, move left 1" are the same instruction with fresher numbers.
        n = SMOOTH_DANGER_FRAMES if decision.kind == KIND_URGENT else SMOOTH_FRAMES
        topic = decision.topic
        self._keys.append(topic)
        if len(self._keys) > n:
            self._keys = self._keys[-n:]

        if len(self._keys) >= n and all(k == topic for k in self._keys):
            self._held = decision
            return decision

        if self._held is not None:
            return self._held
        return Decision.idle(
            zone_depths=decision.zone_depths,
            zone_colors=decision.zone_colors,
            overlay=decision.overlay,
        )
