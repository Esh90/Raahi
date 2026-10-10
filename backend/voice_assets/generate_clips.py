"""OPTIONAL shortcut: generate the clips through the ElevenLabs API instead of the web UI.
Writes into voice_assets/raw/ (same place as manual downloads), skipping files that exist,
so it never wastes credits and never overwrites a clip you recorded by hand.

    python -m voice_assets.generate_clips --only obj_chair,pos_ahead,steps_2,move_left_2
    python -m voice_assets.generate_clips --missing          # everything not in raw/ yet
    python -m voice_assets.generate_clips --audition         # 3 sentences x your shortlisted voices
then:
    python -m voice_assets.process_clips

Env (.env): ELEVENLABS_API_KEY, ELEVENLABS_VOICE_ID, optional AUDITION_VOICE_IDS=id1,id2,id3
"""
from __future__ import annotations

import argparse
import asyncio
import os

from dotenv import load_dotenv

from server.voice.tts_live import synthesize
from voice_assets.library import RAW_DIR, VOICE_DIR, recordable

load_dotenv()

AUDITION_SENTENCES = [
    "رکیں، سامنے رکاوٹ ہے!",
    "کرسی سامنے ہے، دو قدم پر۔ دو قدم بائیں ہٹیں۔",
    "ٹھیک ہے، دروازہ ڈھونڈ رہی ہوں۔",
]


async def gen_one(clip: dict, sem: asyncio.Semaphore) -> str:
    out = RAW_DIR / f"{clip['id']}.mp3"
    async with sem:
        audio = await synthesize(clip["urdu_text"], os.getenv("RAAHI_CLIP_MODEL", "eleven_v3"))
    out.write_bytes(audio)
    return clip["id"]


async def audition() -> None:
    voices = [v for v in os.getenv("AUDITION_VOICE_IDS", "").split(",") if v]
    if not voices:
        raise SystemExit("set AUDITION_VOICE_IDS=id1,id2,... in .env (female voices from the Voice Library)")
    out_dir = VOICE_DIR / "audition"
    out_dir.mkdir(exist_ok=True)
    for v in voices:
        os.environ["ELEVENLABS_VOICE_ID"] = v
        for i, s in enumerate(AUDITION_SENTENCES):
            (out_dir / f"{v}_{i}.mp3").write_bytes(await synthesize(s))
            print(f"{v} sentence {i} ok")
    print(f"listen in {out_dir}, then put the winner in ELEVENLABS_VOICE_ID")


async def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default="", help="comma-separated clip ids")
    ap.add_argument("--missing", action="store_true")
    ap.add_argument("--force", action="store_true", help="overwrite existing raw files")
    ap.add_argument("--audition", action="store_true")
    args = ap.parse_args()

    if args.audition:
        return await audition()

    RAW_DIR.mkdir(parents=True, exist_ok=True)
    wanted = {i.strip() for i in args.only.split(",") if i.strip()}
    todo = [c for c in recordable()
            if (not wanted or c["id"] in wanted)
            and (args.force or not (RAW_DIR / f"{c['id']}.mp3").exists())]
    if not wanted and not args.missing:
        raise SystemExit("use --only ids or --missing")
    print(f"generating {len(todo)} clips ({sum(len(c['urdu_text']) for c in todo)} chars)")
    sem = asyncio.Semaphore(2)          # Creator plan concurrency is small
    for coro in asyncio.as_completed([gen_one(c, sem) for c in todo]):
        try:
            print("ok", await coro)
        except Exception as e:
            print("FAILED", e)


if __name__ == "__main__":
    asyncio.run(main())
