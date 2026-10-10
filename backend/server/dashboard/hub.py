"""Fan-out of annotated frames to judge browsers at ~5 fps."""
from __future__ import annotations

import base64
import time
from collections import deque

from fastapi import WebSocket
class DashboardHub:
    def __init__(self) -> None:
        self.clients: list[WebSocket] = []
        self.latency: deque[int] = deque(maxlen=90)
        self.last_payload: dict | None = None
        self.last_push = 0.0
        self.phone_connected = False
        self.frames = 0
        self._ticks: deque[float] = deque(maxlen=120)
        self._last_clips: list[str] = []
        self._last_spoken: str = ""

    def note_latency(self, total_ms: int) -> None:
        self.latency.append(int(total_ms))

    def fps(self) -> float:
        now = time.monotonic()
        recent = [t for t in self._ticks if now - t <= 2.0]
        return round(len(recent) / 2.0, 1) if recent else 0.0

    async def register(self, ws: WebSocket) -> None:
        await ws.accept()
        self.clients.append(ws)
        if self.last_payload:
            try:
                await ws.send_json(self.last_payload)
            except Exception:
                pass

    def drop(self, ws: WebSocket) -> None:
        if ws in self.clients:
            self.clients.remove(ws)
    async def push(
        self,
        frame_jpeg: bytes,
        depth_jpeg: bytes,
        meta: dict,
        min_dt: float = 0.2,
    ) -> None:
        now = time.monotonic()
        self.frames += 1
        self._ticks.append(now)
        clips = list(meta.get("clips") or [])
        spoken = meta.get("spoken_en") or ""
        if clips:
            self._last_clips = clips
        if spoken:
            self._last_spoken = spoken
        payload = {
            "type": "frame",
            "jpeg_b64": base64.b64encode(frame_jpeg).decode("ascii") if frame_jpeg else "",
            "depth_b64": base64.b64encode(depth_jpeg).decode("ascii") if depth_jpeg else "",
            "latency_history": list(self.latency),
            "latency_avg_ms": int(sum(self.latency) / len(self.latency)) if self.latency else 0,
            "phone": self.phone_connected,
            "fps": self.fps(),
            **meta,
            "clips": clips or self._last_clips,
            "spoken_en": spoken or self._last_spoken,
        }
        self.last_payload = payload
        if now - self.last_push < min_dt:
            return
        self.last_push = now
        dead: list[WebSocket] = []
        for ws in list(self.clients):
            try:
                await ws.send_json(payload)
            except Exception:
                dead.append(ws)
        for ws in dead:
            self.drop(ws)
hub = DashboardHub()
