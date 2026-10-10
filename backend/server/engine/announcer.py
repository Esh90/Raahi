"""Cooldown, priority, interrupt. Speak only on change."""
from __future__ import annotations

import time

from server.config import ANNOUNCE_COOLDOWN_S, FIND_GUIDANCE_REPEAT_S, FIND_SCAN_PERIOD_S
from server.engine.types import (
    KIND_CLEAR,
    KIND_FIND_APPROACH,
    KIND_FIND_SCAN,
    KIND_FIND_TURN,
    KIND_IDLE,
    KIND_OBSTACLE,
    KIND_URGENT,
    Reply,
    Decision,
)


URGENT_REPEAT_S = 3.0      # while still in danger, re-say the direction this often
URGENT_EPISODE_S = 6.0     # a stop after this long is a new episode -> full "stop" again
OBSTACLE_REPEAT_S = 4.0    # while an obstacle stays ahead, refresh guidance (fresh steps) this often
MIN_GAP_S = 1.5            # never change instruction faster than this (flip-flop guard)


class Announcer:
    def __init__(self) -> None:
        self.last_key: str | None = None
        self.last_ts: float = 0.0
        self.last_priority: int = 0
        self.last_english: str = ""
        self._had_obstacle = False
        self._clear_said = False
        self.last_kind: str | None = None
        self.last_direction: str | None = None

    def reset(self) -> None:
        self.last_key = None
        self.last_ts = 0.0
        self.last_priority = 0
        self.last_english = ""
        self._had_obstacle = False
        self._clear_said = False
        self.last_kind = None
        self.last_direction = None

    def update(
        self,
        decision: Decision,
        mode: str = "navigate",
        confirm_clips: list[str] | None = None,
        confirm_english: str = "",
        now: float | None = None,
    ) -> Reply:
        now = time.monotonic() if now is None else now
        confirm_clips = list(confirm_clips or [])

        if decision.kind in (KIND_URGENT, KIND_OBSTACLE):
            self._had_obstacle = True
            self._clear_said = False

        speak_decision, interrupt = self._should_speak(decision, now)

        clips: list[str] = []
        english_parts: list[str] = []
        urgent = False
        haptic = "none"

        if confirm_clips:
            clips.extend(confirm_clips)
            if confirm_english:
                english_parts.append(confirm_english)

        if speak_decision and decision.clips:
            if decision.kind == KIND_CLEAR:
                if self._had_obstacle and not self._clear_said:
                    clips.extend(decision.clips)
                    english_parts.append(decision.english)
                    self._clear_said = True
                    self._had_obstacle = False
                    self.last_key = decision.key
                    self.last_ts = now
                    self.last_priority = decision.priority
                    self.last_english = decision.english
            else:
                spoken = list(decision.clips)
                english = decision.english
                repeat_stop = decision.kind == KIND_URGENT and self.last_kind == KIND_URGENT and (
                    now - self.last_ts) < URGENT_EPISODE_S
                if repeat_stop:
                    # Already said "stop" - now only the way out ("move 2 steps left").
                    spoken = [c for c in decision.clips if not c.startswith("urg_")] or spoken
                    english = english.replace("Stop, ", "").replace("obstacle ahead, ", "")
                clips.extend(spoken)
                if english:
                    english_parts.append(english)
                urgent = decision.urgent
                haptic = decision.haptic if not repeat_stop else "short"
                interrupt = interrupt or decision.interrupt or decision.priority > self.last_priority
                self.last_key = decision.key
                self.last_ts = now
                self.last_priority = decision.priority
                self.last_english = decision.english
                self.last_kind = decision.kind
                self.last_direction = decision.direction

        overlay = list(decision.overlay) if decision.overlay else []
        return Reply(
            clips=clips,
            urgent=urgent,
            interrupt=interrupt,
            haptic=haptic,
            mode=mode,
            overlay=overlay,
            english=" — ".join(p for p in english_parts if p),
        )

    def _should_speak(self, decision: Decision, now: float) -> tuple[bool, bool]:
        if decision.kind in (KIND_IDLE,) or not decision.clips:
            return False, False
        if decision.kind == KIND_CLEAR:
            return True, False

        last_topic = _topic(self.last_key)
        changed = decision.topic != last_topic
        higher = decision.priority > self.last_priority
        dt = now - self.last_ts

        if decision.kind == KIND_FIND_SCAN:
            if changed or dt >= FIND_SCAN_PERIOD_S - 0.05:
                return True, higher
            return False, False

        if decision.kind in (KIND_FIND_APPROACH, KIND_FIND_TURN):
            if changed or dt >= FIND_GUIDANCE_REPEAT_S:
                return True, higher
            return False, False

        if decision.kind == KIND_URGENT:
            if self.last_kind != KIND_URGENT or dt >= URGENT_EPISODE_S:
                return True, True                      # new danger: full stop + direction
            if decision.direction != self.last_direction and dt >= MIN_GAP_S:
                return True, True                      # way out changed
            if dt >= URGENT_REPEAT_S:
                return True, False                     # still blocked: repeat the direction
            return False, False

        if decision.kind == KIND_OBSTACLE:
            if self.last_kind == KIND_URGENT and dt < MIN_GAP_S:
                return False, False                    # we just said stop + direction
            same_way = self.last_kind in (KIND_OBSTACLE, KIND_URGENT) and                 decision.direction == self.last_direction
            if same_way and dt < OBSTACLE_REPEAT_S:
                return False, False                    # label/step flicker, same instruction
            if changed and dt >= MIN_GAP_S or self.last_kind not in (KIND_OBSTACLE, KIND_URGENT):
                return True, higher
            if dt >= OBSTACLE_REPEAT_S:
                return True, False                     # keep guiding until the path is clear
            return False, False

        if changed:
            return True, higher
        return False, False


def _topic(key: str | None) -> str:
    if not key:
        return ""
    return ":".join(p for p in key.split(":") if not p.isdigit())
