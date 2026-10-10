from __future__ import annotations

from dataclasses import dataclass, field


@dataclass
class ModeState:
    mode: str = "navigate"  # navigate | find | paused
    mode_before_pause: str = "navigate"
    find_target: str | None = None
    describe_requested: bool = False
    pending_confirm: list[str] = field(default_factory=list)
    pending_english: str = ""
    frame_idx: int = 0
