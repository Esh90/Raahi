"""Live ElevenLabs TTS fallback for object labels with no pre-recorded clip.

Flow (server side, never blocks the safety audio):
    ids = sentence.obstacle(label, ...)          # -> "obj_unknown" if no clip yet
    tts_live.ensure_async(label)                 # fire-and-forget; next time the label has a clip
    ...
    tts_live.cached_ids()                        # pass as live_ids to the sentence composer

Clips are saved to voice_assets/clips/live/live_<label>.mp3 and served by /clips/live/...
The phone fetches a live clip by URL the first time it sees its id.

Env: ELEVENLABS_API_KEY, ELEVENLABS_VOICE_ID (same voice as the recordings),
     RAAHI_LIVE_TTS_MODEL (default eleven_v3).
"""
from __future__ import annotations

import asyncio
import io
import logging
import os
import re

import httpx
from dotenv import load_dotenv

from voice_assets.library import LIVE_DIR

load_dotenv()
log = logging.getLogger("raahi.tts_live")

ELEVEN_API = "https://api.elevenlabs.io"
DEFAULT_MODEL = "eleven_v3"

# English model label -> Urdu word (for open-vocab labels outside shared/classes.json).
URDU_LABELS: dict[str, str] = {
    "printer": "پرنٹر", "keyboard": "کی بورڈ", "mouse": "ماؤس", "phone": "موبائل فون",
    "cell phone": "موبائل فون", "book": "کتاب", "lamp": "لیمپ", "clock": "گھڑی",
    "fan": "پنکھا", "ceiling fan": "چھت کا پنکھا", "air conditioner": "اے سی",
    "refrigerator": "فریج", "microwave": "مائیکرو ویو", "sink": "سنک", "toilet": "ٹوائلٹ",
    "mirror": "آئینہ", "curtain": "پردہ", "carpet": "قالین", "pillow": "تکیہ",
    "umbrella": "چھتری", "shoe": "جوتا", "cup": "کپ", "glass": "گلاس", "plate": "پلیٹ",
    "cart": "ٹرالی", "trolley": "ٹرالی", "ladder": "سیڑھی", "railing": "جنگلا",
    "cable": "تار", "wire": "تار", "bucket": "بالٹی", "broom": "جھاڑو", "speaker": "اسپیکر",
    "projector": "پروجیکٹر", "podium": "روسٹرم", "stage": "اسٹیج", "banner": "بینر",
    "poster": "پوسٹر", "dog": "کتا", "cat": "بلی", "bicycle": "سائیکل", "motorcycle": "موٹر سائیکل",
    "car": "گاڑی", "stool": "اسٹول", "cone": "کون", "extinguisher": "آگ بجھانے والا سلنڈر",
    "fire extinguisher": "آگ بجھانے والا سلنڈر", "water bottle": "پانی کی بوتل",
}

_pending: set[str] = set()


def clip_id_for(label: str) -> str:
    return "live_" + re.sub(r"[^a-z0-9]+", "_", label.lower()).strip("_")


def path_for(label: str):
    return LIVE_DIR / f"{clip_id_for(label)}.mp3"


def cached_ids() -> set[str]:
    return {p.stem for p in LIVE_DIR.glob("live_*.mp3")} if LIVE_DIR.exists() else set()


def urdu_text(label: str) -> str | None:
    return URDU_LABELS.get(label.lower().strip())


async def synthesize(text: str, model: str | None = None) -> bytes:
    key = os.getenv("ELEVENLABS_API_KEY", "").strip()
    voice = os.getenv("ELEVENLABS_VOICE_ID", "").strip()
    if not key or not voice:
        raise RuntimeError("ELEVENLABS_API_KEY / ELEVENLABS_VOICE_ID not set")
    model = model or os.getenv("RAAHI_LIVE_TTS_MODEL", DEFAULT_MODEL)
    body = {"text": text, "model_id": model, "language_code": "ur"}
    url = f"{ELEVEN_API}/v1/text-to-speech/{voice}?output_format=mp3_44100_128"
    async with httpx.AsyncClient(timeout=30) as client:
        r = await client.post(url, json=body, headers={"xi-api-key": key})
        if r.status_code == 400 and "language" in r.text.lower():
            body.pop("language_code")                        # model doesn't take language_code
            r = await client.post(url, json=body, headers={"xi-api-key": key})
    if r.status_code != 200:
        raise RuntimeError(f"ElevenLabs TTS {r.status_code}: {r.text[:200]}")
    return r.content


async def get_clip(label: str) -> str | None:
    """Return the live clip id for label, generating + caching it if needed.
    None if the label has no Urdu translation or the API call failed."""
    cid = clip_id_for(label)
    out = path_for(label)
    if out.exists():
        return cid
    text = urdu_text(label)
    if not text:
        return None
    try:
        audio = await synthesize(text)
    except Exception as e:
        log.warning("live TTS failed for %r: %s", label, e)
        return None
    from pydub import AudioSegment

    from voice_assets import audio_utils
    seg = audio_utils.finish(audio_utils.load(audio))
    audio_utils.export_mp3(seg, out)
    log.info("live clip cached: %s (%s)", cid, text)
    return cid


def ensure_async(label: str) -> None:
    """Fire-and-forget generation from inside the running event loop."""
    if not label or label in _pending or path_for(label).exists() or not urdu_text(label):
        return
    _pending.add(label)

    async def _run():
        try:
            await get_clip(label)
        finally:
            _pending.discard(label)

    try:
        asyncio.get_running_loop().create_task(_run())
    except RuntimeError:            # no loop (scripts / tests)
        asyncio.run(_run())


if __name__ == "__main__":
    import sys
    logging.basicConfig(level=logging.INFO)
    lbl = " ".join(sys.argv[1:]) or "printer"
    print(asyncio.run(get_clip(lbl)))
