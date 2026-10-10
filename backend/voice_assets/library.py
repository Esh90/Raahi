"""Load clips.json and classes.json. Shared by every voice script and the server."""
from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VOICE_DIR = ROOT / "voice_assets"
CLIPS_JSON = VOICE_DIR / "clips.json"
CLASSES_JSON = ROOT / "shared" / "classes.json"
RAW_DIR = VOICE_DIR / "raw"          # Esha drops ElevenLabs downloads here as <id>.mp3
CLIPS_DIR = VOICE_DIR / "clips"      # processed, app-ready clips
LIVE_DIR = CLIPS_DIR / "live"        # cached live-TTS clips
# old Capacitor web prototype (repo/frontend/web-prototype); the Android app downloads clips from the server
MOBILE_CLIPS_DIR = ROOT.parent / "frontend" / "web-prototype" / "public" / "clips"


@lru_cache
def clips() -> list[dict]:
    return json.loads(CLIPS_JSON.read_text(encoding="utf-8-sig"))["clips"]


@lru_cache
def clip_ids() -> frozenset[str]:
    return frozenset(c["id"] for c in clips())


def clip(clip_id: str) -> dict:
    for c in clips():
        if c["id"] == clip_id:
            return c
    raise KeyError(clip_id)


def recordable() -> list[dict]:
    return [c for c in clips() if c.get("record", True)]


@lru_cache
def classes() -> list[dict]:
    return json.loads(CLASSES_JSON.read_text(encoding="utf-8-sig"))["classes"]


def class_info(cls_id: str) -> dict | None:
    for c in classes():
        if c["id"] == cls_id:
            return c
    return None
