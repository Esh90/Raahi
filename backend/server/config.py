"""Thresholds, camera intrinsics, class maps. Shared by engine + depth.

Focal length (FX/FY) is pixels at the streamed frame width (plan: 416).
Recompute with: python tools/calibrate.py --image shot.jpg --width-m W --distance-m Z
"""
from __future__ import annotations

import os
from pathlib import Path

try:
    from dotenv import load_dotenv

    load_dotenv()
except ImportError:
    pass

ROOT = Path(__file__).resolve().parents[1]


def _env(key: str, default: str = "") -> str:
    val = os.getenv(f"RAAHI_{key}")
    if val is None:
        val = os.getenv(f"BASIRAT_{key}")
    return default if val is None else val


HOST = _env("HOST", "0.0.0.0")
PORT = int(_env("PORT", "8000"))

# Day 1 / CI: skip heavy models
MOCK = _env("MOCK", "0").strip() in {"1", "true", "True", "yes"}
DEPTH_BACKEND = _env("DEPTH_BACKEND", "auto").strip().lower()
DEPTH_COREML_PATH = _env("DEPTH_COREML", "").strip()
DEPTH_SHORT_SIDE = int(_env("DEPTH_SHORT_SIDE", "336"))
DEPTH_MODEL_ID = "depth-anything/Depth-Anything-V2-Metric-Indoor-Small-hf"
# Multiplies every metre. Webcam FOV ≠ the model's training cameras.
# Set via POST /calibrate or models/depth_scale.txt — do not guess.
DEPTH_SCALE = float(_env("DEPTH_SCALE", "1.0") or "1.0")

# Navigator
ROI_TOP = 0.30
ROI_BOTTOM = 0.95
# Walking-height slab for zone metres — drops the floor that Depth Anything
# paints as the closest pixels in the bottom ~20% of the frame.
ROI_OBSTACLE_BOTTOM = 0.90
ZONE_SPLITS = (0.0, 0.33, 0.67, 1.0)  # left / centre / right
DANGER_M = 0.8
WARN_M = 2.0
STEP_M = 0.6
LATERAL_MARGIN_M = 0.35
MOVE_STEPS_MAX = 4
SIDESTEP_DELTA_M = 0.20  # need this much extra room on one side to say "move left/right"
DEPTH_EMA_ALPHA = 0.7  # track walking; old 0.45 lagged a metre behind
PINHOLE_BLEND = 0.25  # weight of pinhole when it agrees with metric depth

# Depth validity
DEPTH_MIN_M = 0.15
DEPTH_MAX_M = 8.0
MIN_DEPTH_PIXELS = 20

# Smoother / announcer
SMOOTH_FRAMES = 3
SMOOTH_DANGER_FRAMES = 2
ANNOUNCE_COOLDOWN_S = 3.0
FIND_SCAN_PERIOD_S = 4.0
FIND_LOST_S = 3.0
FIND_ARRIVED_M = 1.0
FIND_ALIGN_DEG = 10.0
FIND_GUIDANCE_REPEAT_S = 3.0
DESCRIBE_MAX = 5
OVERLAY_MAX = 8

# Camera (streamed JPEG width from the phone)
STREAM_WIDTH = 416
FX = float(_env("FX", "320"))
FY = float(_env("FY", str(FX)))

# Detector (Esha tunes; used here for overlay + pinhole)
CONF_THRESH = 0.35
MIN_BOX_AREA_FRAC = 0.02

# ---- classes / clips: generated from shared/classes.json + voice_assets/clips.json (Esha) ----
import json as _json


def _load_json(rel: str) -> dict:
    try:
        return _json.loads((ROOT / rel).read_text(encoding="utf-8-sig"))
    except (OSError, ValueError):
        return {}


_CLASSES = [c for c in _load_json("shared/classes.json").get("classes", []) if c.get("enabled", True)]
_CLIPS = {c["id"]: c for c in _load_json("voice_assets/clips.json").get("clips", [])}

CLASSES = [c["id"] for c in _CLASSES]

# detector class id (and space/alias spellings) -> object clip
CLASS_TO_OBJ_CLIP = {"unknown": "obj_unknown"}
for _c in _CLASSES:
    for _k in (_c["id"], _c["id"].replace("_", " "), *_c.get("prompts", [])):
        CLASS_TO_OBJ_CLIP[_k] = _c["clip"]
CLASS_TO_OBJ_CLIP.update({"backpack": "obj_bag", "plant": "obj_plant_pot", "fan stand": "obj_fan_stand"})

# plural nouns use pos_*_pl ("سیڑھیاں سامنے ہیں")
PLURAL_CLIPS = {c["clip"] for c in _CLASSES if c.get("plural")}

FIND_TARGETS = ("door", "chair", "table", "person", "sofa", "trash_bin")
FIND_ALIASES = {
    "door": {"door", "glass_door", "glass door"},
    "chair": {"chair"},
    "table": {"table", "desk"},
    "person": {"person"},
    "sofa": {"sofa"},
    "trash_bin": {"trash_bin", "trash bin"},
}

OBJ_CLIP_EN = {cid: c.get("en", cid) for cid, c in _CLIPS.items() if cid.startswith("obj_")}
OBJ_CLIP_EN.setdefault("obj_unknown", "obstacle")

WAKE_WORDS = ("راہی", "raahi", "raahi", "raahi ji")  # real list: server/voice/intents.py
