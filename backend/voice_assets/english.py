"""English voice clips: split the recorded batches into clips (same ids as the Urdu ones).

    python -m voice_assets.english split          # batches_en/en_batch_NN.mp3 -> raw_en/<id>.mp3
    python -m voice_assets.process_clips          # trims/normalises Urdu AND English -> clips/ and clips/en/

Texts + batch plan: voice_assets/clips_en.json. Uses the same pause-fitting splitter as Urdu
(voice_assets/batch_split.split_audio).
"""
from __future__ import annotations

import argparse
import json
import sys
from functools import lru_cache

from voice_assets import audio_utils
from voice_assets.batch_split import split_audio
from voice_assets.library import VOICE_DIR

CLIPS_EN_JSON = VOICE_DIR / "clips_en.json"
BATCH_DIR_EN = VOICE_DIR / "batches_en"
RAW_DIR_EN = VOICE_DIR / "raw_en"


@lru_cache
def spec() -> dict:
    return json.loads(CLIPS_EN_JSON.read_text(encoding="utf-8-sig"))


def texts() -> dict[str, str]:
    """id -> English text (including ids that reuse another recording)."""
    s = spec()
    t = {k: v["text"] for k, v in s["clips"].items()}
    for dst, src in s.get("copies", {}).items():
        t.setdefault(dst, t.get(src, ""))
    return t


def copies() -> dict[str, str]:
    return dict(spec().get("copies", {}))


def _batch_file(num: str):
    for name in (f"en_batch_{num}.mp3", f"en_batch_{num}.mp3.mp3", f"en_batch_{num}.wav"):
        p = BATCH_DIR_EN / name
        if p.exists():
            return p
    return None


def split(only: str | None = None, force: bool = False) -> int:
    t = texts()
    RAW_DIR_EN.mkdir(parents=True, exist_ok=True)
    failed = 0
    for num, ids in spec()["batches"].items():
        if only and int(num) != int(only):
            continue
        src = _batch_file(num)
        if src is None:
            print(f"batch {num}: missing batches_en/en_batch_{num}.mp3")
            failed += 1
            continue
        res = split_audio(audio_utils.load(src), [t[i] for i in ids])
        if isinstance(res, str):
            print(f"batch {num}: NOT SAVED - {res}. Regenerate this batch.")
            failed += 1
            continue
        saved = 0
        for cid, piece in zip(ids, res):
            out = RAW_DIR_EN / f"{cid}.mp3"
            if out.exists() and not force:
                continue
            audio_utils.export_mp3(audio_utils.trim(piece), out)
            saved += 1
        print(f"batch {num}: OK, {saved} clips -> raw_en/")
    return 1 if failed else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["split"])
    ap.add_argument("--batch", default=None)
    ap.add_argument("--force", action="store_true")
    a = ap.parse_args()
    return split(a.batch, a.force)


if __name__ == "__main__":
    sys.exit(main())
