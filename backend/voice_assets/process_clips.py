"""Turn Esha's ElevenLabs downloads into app-ready clips.

    python -m voice_assets.process_clips            # process raw/ (+ placeholders for missing)
    python -m voice_assets.process_clips --strict   # fail if any clip is missing from raw/

Input : voice_assets/raw/<id>.mp3 (also .wav/.m4a/.ogg)  - recorded in ElevenLabs
        voice_assets/placeholder/<id>.mp3                 - optional fallback (make_placeholder_clips)
Output: voice_assets/clips/<id>.mp3, voice_assets/clips/manifest.json
        and the same copied to mobile/public/clips/
"""
from __future__ import annotations

import argparse
import json
import shutil
import sys

from pydub import AudioSegment
from pydub.generators import Sine

from voice_assets import audio_utils
from voice_assets.library import (CLIPS_DIR, MOBILE_CLIPS_DIR, RAW_DIR, VOICE_DIR,
                                  classes, clips)

PLACEHOLDER_DIR = VOICE_DIR / "placeholder"
EXTS = (".mp3", ".wav", ".m4a", ".ogg", ".mp3.mp3")


def find_source(clip_id: str, folder) -> object | None:
    for ext in EXTS:
        p = folder / f"{clip_id}{ext}"
        if p.exists():
            return p
    return None


def make_earcon(clip_id: str) -> AudioSegment:
    if clip_id == "ear_alert":
        beep = Sine(1200).to_audio_segment(duration=90).apply_gain(-6)
        gap = AudioSegment.silent(duration=50)
        return beep + gap + beep
    # ear_listen: soft rising two-tone
    return (Sine(660).to_audio_segment(duration=70).apply_gain(-10)
            + Sine(880).to_audio_segment(duration=90).apply_gain(-10))


def build_manifest(entries: list[dict]) -> dict:
    return {
        "version": 1,
        "clips": entries,
        # class -> clip map so the phone composer needs no other file
        "classes": {c["id"]: {"clip": c["clip"], "plural": bool(c.get("plural")),
                              "height_m": c.get("height_m"), "min_conf": c["min_conf"],
                              "findable": c["findable"]}
                    for c in classes()},
    }


def add_english(entries: list[dict]) -> list[str]:
    """Process raw_en/<id>.mp3 -> clips/en/<id>.mp3 and add en_file/en_text to the entries."""
    from voice_assets.english import RAW_DIR_EN, copies, texts
    en_dir = CLIPS_DIR / "en"
    en_dir.mkdir(parents=True, exist_ok=True)
    en_texts, en_copies = texts(), copies()
    missing = []
    for e in entries:
        cid = e["id"]
        out = en_dir / f"{cid}.mp3"
        if e["source"] == "generated":                  # earcons are language-neutral
            shutil.copy2(CLIPS_DIR / e["file"], out)
        else:
            src = find_source(en_copies.get(cid, cid), RAW_DIR_EN)
            if src is None:
                missing.append(cid)
                continue
            audio_utils.export_mp3(audio_utils.finish(audio_utils.load(src)), out)
        e["en_file"] = f"en/{cid}.mp3"
        e["en_text"] = en_texts.get(cid, e.get("en", ""))
    return missing


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--strict", action="store_true", help="error if any recorded clip is missing")
    ap.add_argument("--no-mobile", action="store_true", help="don't copy into mobile/public/clips")
    args = ap.parse_args()

    CLIPS_DIR.mkdir(parents=True, exist_ok=True)
    entries, missing, placeholders = [], [], []

    for c in clips():
        cid = c["id"]
        out = CLIPS_DIR / f"{cid}.mp3"
        if not c.get("record", True):
            seg = make_earcon(cid) + AudioSegment.silent(duration=30)
            audio_utils.export_mp3(seg, out)
            source = "generated"
        else:
            src = find_source(cid, RAW_DIR)
            source = "recorded"
            if src is None:
                src = find_source(cid, PLACEHOLDER_DIR)
                source = "placeholder"
            if src is None:
                missing.append(cid)
                continue
            if source == "placeholder":
                placeholders.append(cid)
            seg = audio_utils.finish(audio_utils.load(src))
            audio_utils.export_mp3(seg, out)
        entries.append({"id": cid, "file": f"{cid}.mp3", "urdu_text": c["urdu_text"],
                        "en": c["en"], "category": c["category"],
                        "duration_ms": len(audio_utils.load(out)),
                        "source": source})

    # English voice: same ids, files under clips/en/. The app picks `en_file` when English is selected
    # and falls back to the Urdu `file` for any id without an English recording.
    en_missing = add_english(entries)

    # keep previously cached live clips in the manifest
    for p in sorted((CLIPS_DIR / "live").glob("*.mp3")):
        entries.append({"id": p.stem, "file": f"live/{p.name}", "urdu_text": "", "en": p.stem,
                        "category": "live", "source": "live"})

    manifest = build_manifest(entries)
    (CLIPS_DIR / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1),
                                             encoding="utf-8")

    if not args.no_mobile:
        MOBILE_CLIPS_DIR.mkdir(parents=True, exist_ok=True)
        for p in CLIPS_DIR.rglob("*"):
            if p.is_file():
                dest = MOBILE_CLIPS_DIR / p.relative_to(CLIPS_DIR)
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(p, dest)

    rec = sum(1 for e in entries if e["source"] == "recorded")
    print(f"clips ready: {len(entries)}  (recorded {rec}, placeholder {len(placeholders)}, "
          f"generated {sum(1 for e in entries if e['source'] == 'generated')})")
    if placeholders:
        print(f"still using placeholders for {len(placeholders)}: {', '.join(placeholders[:15])}"
              f"{' ...' if len(placeholders) > 15 else ''}")
    if missing:
        print(f"MISSING (no recording, no placeholder) {len(missing)}: {', '.join(missing)}")
    en_ready = sum(1 for e in entries if e.get("en_file"))
    print(f"english clips ready: {en_ready}" + (f"  (no English recording: {', '.join(en_missing)})" if en_missing else ""))
    if args.strict and (missing or placeholders):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
