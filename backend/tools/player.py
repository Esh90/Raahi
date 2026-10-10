"""Laptop clip player that behaves like the phone's audio: all clips pre-decoded in memory, ONE audio
output stream opened at start-up and kept running, clip lists played back to back with no gaps.

Why one persistent stream: opening a new stream per sentence (sd.play) costs 50-300 ms on Windows and an
interrupt (sd.stop from another thread) could kill the stream that was just starting - the terminal said
"SPEAK" but nothing was heard. Here an interrupt only swaps what the always-running callback reads, so it is
instant and race-free, and `on_start` fires when the first samples of a sentence really go to the speakers.

Sync rules (voice must describe what the camera sees NOW, not 3 seconds ago):
  * interrupt=True (danger / commands): drop everything, play immediately
  * a new normal message replaces any normal message still waiting (only the newest guidance matters)
  * a normal message that waited longer than MAX_WAIT_S behind a long sentence is dropped as stale
"""
from __future__ import annotations

import queue
import threading
import time
from collections import deque

import numpy as np
import sounddevice as sd

from voice_assets import audio_utils
from voice_assets.library import CLIPS_DIR, clips

SR = audio_utils.SAMPLE_RATE
MAX_WAIT_S = 1.5


class _Utterance:
    __slots__ = ("ids", "audio", "queued_at", "urgent")

    def __init__(self, ids, audio, queued_at, urgent):
        self.ids, self.audio, self.queued_at, self.urgent = ids, audio, queued_at, urgent


class Player:
    def __init__(self, volume: float = 1.0, language: str = "ur"):
        """language "en" plays clips/en/<id>.mp3 (English voice), falling back to Urdu per clip."""
        self.buffers: dict[str, np.ndarray] = {}
        for c in clips():
            p = CLIPS_DIR / f"{c['id']}.mp3"
            en = CLIPS_DIR / "en" / f"{c['id']}.mp3"
            if language == "en" and en.exists():
                p = en
            if p.exists():
                self.buffers[c["id"]] = self._decode(p)
        self.reload_live()
        self.volume = volume
        self.on_start = None                 # callback(ids, queued_at, started_at) - called off the audio thread
        self.last_started: tuple[list[str], float] | None = None
        self.dropped = 0                     # stale messages skipped (for diagnostics)

        self._lock = threading.Lock()
        self._cur: _Utterance | None = None
        self._pos = 0
        self._pending: deque[_Utterance] = deque()
        self._events: queue.Queue = queue.Queue()
        threading.Thread(target=self._notify_loop, daemon=True).start()
        self._stream = sd.OutputStream(samplerate=SR, channels=1, dtype="float32", latency="low",
                                       callback=self._callback)
        self._stream.start()
        self._out_latency = float(self._stream.latency or 0.0)

    @staticmethod
    def _decode(path) -> np.ndarray:
        seg = audio_utils.load(path)
        return np.array(seg.get_array_of_samples(), dtype=np.float32) / 32768.0

    def reload_live(self):
        for p in (CLIPS_DIR / "live").glob("*.mp3"):
            if p.stem not in self.buffers:
                self.buffers[p.stem] = self._decode(p)

    # ------------------------------------------------------------------ public API (same as before)
    def play(self, ids: list[str], interrupt: bool = False):
        parts = [self.buffers[i] for i in ids if i in self.buffers]
        missing = [i for i in ids if i not in self.buffers]
        if missing:
            print(f"[player] missing clips: {missing}")
        if not parts:
            return
        u = _Utterance(list(ids), (np.concatenate(parts) * self.volume).astype(np.float32),
                       time.perf_counter(), interrupt)
        with self._lock:
            if interrupt:
                self._cur = None
                self._pending.clear()
            else:
                # only the newest normal guidance is worth saying; keep queued urgent messages
                self._pending = deque(p for p in self._pending if p.urgent)
            self._pending.append(u)

    def stop(self):
        with self._lock:
            self._cur = None
            self._pending.clear()

    def is_playing(self) -> bool:
        with self._lock:
            return self._cur is not None or bool(self._pending)

    def close(self):
        try:
            self._stream.stop()
            self._stream.close()
        except Exception:
            pass

    # ------------------------------------------------------------------ audio thread
    def _next(self, now: float) -> _Utterance | None:
        while self._pending:
            u = self._pending.popleft()
            if u.urgent or now - u.queued_at <= MAX_WAIT_S:
                return u
            self.dropped += 1
        return None

    def _callback(self, outdata, frames, time_info, status):
        out = outdata[:, 0]
        filled = 0
        now = time.perf_counter()
        with self._lock:
            while filled < frames:
                if self._cur is None:
                    self._cur = self._next(now)
                    self._pos = 0
                    if self._cur is None:
                        break
                    # first sample of this sentence reaches the speakers after the output latency
                    self._events.put((self._cur.ids, self._cur.queued_at, now + self._out_latency))
                n = min(frames - filled, len(self._cur.audio) - self._pos)
                out[filled:filled + n] = self._cur.audio[self._pos:self._pos + n]
                filled += n
                self._pos += n
                if self._pos >= len(self._cur.audio):
                    self._cur = None
        if filled < frames:
            out[filled:] = 0.0

    def _notify_loop(self):
        while True:
            ids, queued_at, started = self._events.get()
            self.last_started = (ids, started)
            cb = self.on_start
            if cb:
                try:
                    cb(ids, queued_at, started)
                except Exception as e:  # never let a print error kill audio notifications
                    print("[player] on_start error:", e)
