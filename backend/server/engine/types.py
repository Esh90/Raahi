from __future__ import annotations

from dataclasses import dataclass, field, replace
from typing import Any

# Announcer priority (higher wins and may interrupt)
PRI_IDLE = 0
PRI_SYSTEM = 20
PRI_DESCRIBE = 40
PRI_FIND = 60
PRI_OBSTACLE = 80
PRI_URGENT = 100

KIND_IDLE = "idle"
KIND_CLEAR = "clear"
KIND_OBSTACLE = "obstacle"
KIND_URGENT = "urgent_stop"
KIND_FIND_SCAN = "find_scan"
KIND_FIND_TURN = "find_turn"
KIND_FIND_APPROACH = "find_approach"
KIND_FIND_ARRIVED = "find_arrived"
KIND_FIND_LOST = "find_lost"
KIND_DESCRIBE = "describe"
KIND_CONFIRM = "confirm"


@dataclass(frozen=True)
class Decision:
    kind: str
    key: str
    clips: tuple[str, ...] = ()
    priority: int = PRI_IDLE
    urgent: bool = False
    interrupt: bool = False
    haptic: str = "none"  # none | short | long | arrived
    english: str = ""
    dist_m: float | None = None
    steps_away: int | None = None
    zone_depths: tuple[float, float, float] | None = None
    zone_colors: tuple[str, str, str] | None = None
    overlay: tuple[dict[str, Any], ...] = ()
    direction: str | None = None  # "left" | "right" | "back" | None - where the user is told to go

    @property
    def topic(self) -> str:
        """Key without the numbers ("obstacle:obj_chair:left:3:2" -> "obstacle:obj_chair:left").
        Smoother/announcer compare topics so a flickering step count isn't a "new" message."""
        return ":".join(p for p in self.key.split(":") if not p.isdigit())

    @staticmethod
    def idle(
        zone_depths: tuple[float, float, float] | None = None,
        zone_colors: tuple[str, str, str] | None = None,
        overlay: tuple[dict[str, Any], ...] = (),
    ) -> Decision:
        return Decision(
            kind=KIND_IDLE,
            key="idle",
            zone_depths=zone_depths,
            zone_colors=zone_colors,
            overlay=overlay,
        )

    def with_zones(
        self,
        zone_depths: tuple[float, float, float] | None,
        zone_colors: tuple[str, str, str] | None,
        overlay: tuple[dict[str, Any], ...] = (),
    ) -> Decision:
        return replace(self, zone_depths=zone_depths, zone_colors=zone_colors, overlay=overlay)


@dataclass
class Reply:
    clips: list[str] = field(default_factory=list)
    urgent: bool = False
    interrupt: bool = False
    haptic: str = "none"
    mode: str = "navigate"
    overlay: list[dict[str, Any]] = field(default_factory=list)
    english: str = ""

    def to_payload(self, latency_ms: dict[str, int]) -> dict[str, Any]:
        return {
            "type": "result",
            "clips": self.clips,
            "urgent": self.urgent,
            "interrupt": self.interrupt,
            "haptic": self.haptic,
            "mode": self.mode,
            "overlay": self.overlay,
            "english": self.english,
            "latency_ms": latency_ms,
        }
