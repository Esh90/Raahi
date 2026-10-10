"""Voice HTTP routes. Member B mounts this in server/main.py:

    from server.voice.token import router as voice_router
    app.include_router(voice_router)

Routes
    GET /scribe-token          -> {"token": "sutkn_..."}  single-use, 15 min, for Scribe v2 Realtime
    GET /clips/manifest.json   -> clip list + class map (voice_assets/clips/manifest.json)
    GET /clips/{path}          -> clip mp3 (incl. live/<id>.mp3 from tts_live)
    GET /voice/scribe-config   -> keyterms + language for the phone's Scribe connection
The ElevenLabs API key never leaves the server.
"""
from __future__ import annotations

import os

import httpx
from dotenv import load_dotenv
from fastapi import APIRouter, HTTPException
from fastapi.responses import FileResponse, JSONResponse

from voice_assets.library import CLIPS_DIR

load_dotenv()

ELEVEN_API = "https://api.elevenlabs.io"
router = APIRouter()


def api_key() -> str:
    key = os.getenv("ELEVENLABS_API_KEY", "").strip()
    if not key:
        raise HTTPException(503, "ELEVENLABS_API_KEY not set in .env")
    return key


@router.get("/scribe-token")
async def scribe_token():
    async with httpx.AsyncClient(timeout=10) as client:
        r = await client.post(f"{ELEVEN_API}/v1/single-use-token/realtime_scribe",
                              headers={"xi-api-key": api_key()})
    if r.status_code != 200:
        raise HTTPException(502, f"ElevenLabs token error {r.status_code}: {r.text[:200]}")
    return {"token": r.json()["token"]}


@router.get("/voice/scribe-config")
async def scribe_config():
    """Bias Scribe towards our vocabulary (wake word + targets) so short Urdu commands
    are transcribed reliably."""
    # the app matches commands on the phone (frontend/android/.../assets/voice_commands.json); bias Scribe
    # towards the wake word and the command words. The app sends at most 20 keyterms - keep this short.
    keyterms = ["Raahi", "راہی", "settings", "سیٹنگز", "accessibility", "ایکسیسبلٹی", "dark mode", "ڈارک موڈ",
                "light mode", "لائٹ موڈ", "English", "انگلش", "اردو", "start detecting", "شروع کرو",
                "بند کرو", "history", "ہسٹری"]
    return {"model_id": "scribe_v2_realtime", "language_code": "ur", "keyterms": keyterms}


@router.get("/clips/manifest.json")
async def manifest():
    p = CLIPS_DIR / "manifest.json"
    if not p.exists():
        raise HTTPException(404, "run: python -m voice_assets.process_clips")
    return FileResponse(p, media_type="application/json", headers={"Cache-Control": "no-cache"})


@router.get("/clips/{path:path}")
async def clip_file(path: str):
    p = (CLIPS_DIR / path).resolve()
    if CLIPS_DIR.resolve() not in p.parents or not p.is_file():
        raise HTTPException(404)
    return FileResponse(p, media_type="audio/mpeg")


@router.get("/voice/health")
async def voice_health():
    return JSONResponse({"api_key": bool(os.getenv("ELEVENLABS_API_KEY")),
                         "manifest": (CLIPS_DIR / "manifest.json").exists()})


def create_scribe_token() -> dict:
    """Sync helper (Member B's main.py contract): {"ok": True, "token": ...} or {"ok": False, "error": ...}."""
    key = os.getenv("ELEVENLABS_API_KEY", "").strip()
    if not key:
        return {"ok": False, "error": "ELEVENLABS_API_KEY not set in .env"}
    try:
        r = httpx.post(f"{ELEVEN_API}/v1/single-use-token/realtime_scribe",
                       headers={"xi-api-key": key}, timeout=10)
    except httpx.HTTPError as e:
        return {"ok": False, "error": f"network: {e}"}
    if r.status_code != 200:
        return {"ok": False, "error": f"ElevenLabs {r.status_code}: {r.text[:200]}"}
    return {"ok": True, "token": r.json()["token"]}
