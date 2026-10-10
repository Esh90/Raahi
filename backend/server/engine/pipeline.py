"""Ties navigator + find + describe + smoother + announcer into one step()."""
from __future__ import annotations

import time

import numpy as np

from server.config import FIND_TARGETS, WARN_M
from server.engine.announcer import Announcer
from server.engine.describe import compose as compose_describe
from server.engine.find_mode import FindGuide
from server.engine.navigator import Navigator
from server.engine.smoother import Smoother
from server.engine.state import ModeState
from server.engine.types import KIND_FIND_ARRIVED, KIND_IDLE, Decision, Reply
from server.perception.detector import Detection
from server.voice.intents import Intent


_CONFIRM = {
    "door": ("cmd_finding_door", "Looking for the door"),
    "chair": ("cmd_finding_chair", "Looking for a chair"),
    "table": ("cmd_finding_table", "Looking for the table"),
    "person": ("cmd_finding_person", "Looking for a person"),
    "sofa": ("cmd_finding_sofa", "Looking for the sofa"),
    "trash_bin": ("cmd_finding_trash_bin", "Looking for the trash bin"),
}


class GuidanceEngine:
    def __init__(self) -> None:
        self.state = ModeState()
        self.navigator = Navigator()
        self.find = FindGuide()
        self.smoother = Smoother()
        self.announcer = Announcer()
        self.last_detections: list[Detection] = []
        self.last_depth: np.ndarray | None = None
        self.last_shape: tuple[int, int] = (480, 416)
        self.last_decision: Decision = Decision.idle()

    def handle_intent(self, intent: Intent | None) -> Reply | None:
        """Apply a parsed command. Returns an immediate reply (confirmation)."""
        if intent is None:
            return None
        st = self.state
        confirm: list[str] = []
        english = ""

        if intent.type == "find":
            target = (intent.target or "door").lower()
            if target not in FIND_TARGETS:
                confirm, english = ["sys_not_understood"], "I didn't catch that"
            else:
                st.mode = "find"
                st.find_target = target
                self.find.start(target)
                clip, english = _CONFIRM[target]
                confirm = [clip]
                self.smoother.reset()
        elif intent.type == "describe":
            st.describe_requested = True
            decision = compose_describe(
                self.last_detections, self.last_depth, self.last_shape
            )
            st.describe_requested = False
            reply = self.announcer.update(
                decision, mode=st.mode, confirm_clips=[], confirm_english=""
            )
            self.last_decision = decision
            return reply
        elif intent.type == "pause":
            if st.mode != "paused":
                st.mode_before_pause = st.mode
            st.mode = "paused"
            confirm, english = ["sys_paused"], "Paused"
        elif intent.type == "resume":
            st.mode = st.mode_before_pause or "navigate"
            confirm, english = ["sys_resumed"], "Resumed"
        elif intent.type == "stop":
            st.mode = "navigate"
            st.find_target = None
            self.find.stop()
            self.smoother.reset()
            confirm, english = [], "Stopped looking"
        elif intent.type == "unknown":
            confirm, english = ["sys_not_understood"], "I didn't catch that"
        else:
            return None

        st.pending_confirm = confirm
        st.pending_english = english
        if not confirm:
            return Reply(clips=[], mode=st.mode, english=english)
        reply = Reply(clips=list(confirm), mode=st.mode, english=english)
        st.pending_confirm = []
        st.pending_english = ""
        return reply

    def process(
        self,
        frame_shape: tuple[int, ...],
        detections: list[Detection],
        depth_m: np.ndarray | None,
        now: float | None = None,
    ) -> tuple[Decision, Reply]:
        now = time.monotonic() if now is None else now
        st = self.state
        st.frame_idx += 1
        self.last_detections = detections
        self.last_depth = depth_m
        self.last_shape = (int(frame_shape[0]), int(frame_shape[1]))

        nav = self.navigator.step(frame_shape, detections, depth_m)
        centre_warn = bool(nav.zone_depths and nav.zone_depths[1] < WARN_M)

        if st.mode == "paused":
            decision = Decision.idle(
                zone_depths=nav.zone_depths,
                zone_colors=nav.zone_colors,
                overlay=nav.overlay,
            )
        elif st.describe_requested:
            decision = compose_describe(detections, depth_m, frame_shape)
            st.describe_requested = False
            decision = decision.with_zones(nav.zone_depths, nav.zone_colors, nav.overlay)
        elif st.mode == "find" and not centre_warn:
            found = self.find.step(detections, depth_m, frame_shape, now=now)
            if found is None or found.kind == KIND_IDLE:
                decision = Decision.idle(
                    zone_depths=nav.zone_depths,
                    zone_colors=nav.zone_colors,
                    overlay=nav.overlay,
                )
            else:
                decision = found.with_zones(nav.zone_depths, nav.zone_colors, nav.overlay)
                if found.kind == KIND_FIND_ARRIVED:
                    st.mode = "navigate"
                    st.find_target = None
                    self.find.stop()
        else:
            # navigate, or find overridden by a close centre-zone obstacle
            decision = nav

        smoothed = self.smoother.update(decision)
        confirm = st.pending_confirm
        confirm_en = st.pending_english
        st.pending_confirm = []
        st.pending_english = ""
        reply = self.announcer.update(
            smoothed,
            mode=st.mode,
            confirm_clips=confirm,
            confirm_english=confirm_en,
            now=now,
        )
        if not reply.overlay:
            reply.overlay = list(nav.overlay)
        self.last_decision = smoothed
        return smoothed, reply
