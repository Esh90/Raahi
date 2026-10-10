"""Hear a composed sentence exactly as the phone will play it (clips back to back).

    python -m voice_assets.preview_sentence obj_chair pos_ahead steps_2 move_left_2
    python -m voice_assets.preview_sentence --demo          # writes a set of example sentences
Output: runs/preview/<name>.wav (opens on Windows/macOS if --play)
"""
from __future__ import annotations

import argparse
import os
import platform
import subprocess
from pathlib import Path

from pydub import AudioSegment

from voice_assets import audio_utils  # noqa: F401  (ffmpeg setup)
from voice_assets import sentence as S
from voice_assets.library import CLIPS_DIR, ROOT

OUT = ROOT / "runs" / "preview"

DEMOS = {
    "obstacle_chair": S.obstacle("chair", "ahead", 2, "left", 2),
    "stop_person": S.urgent_stop("person"),
    "stairs_left": S.obstacle("stairs", "left", 4),
    "describe": S.describe([("chair", "ahead", 2), ("door", "left", 5), ("trash_bin", "right", 3)]),
    "find_door": S.find_confirm("door") + S.find_guidance("door", -18, 4) + S.find_arrived("door"),
}


def join(ids: list[str]) -> AudioSegment:
    out = AudioSegment.silent(duration=100)
    for i in ids:
        p = CLIPS_DIR / f"{i}.mp3"
        if not p.exists():
            p = CLIPS_DIR / "live" / f"{i}.mp3"
        if not p.exists():
            print(f"  missing clip {i}")
            continue
        out += audio_utils.load(p)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ids", nargs="*")
    ap.add_argument("--demo", action="store_true")
    ap.add_argument("--play", action="store_true")
    args = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    jobs = DEMOS if args.demo or not args.ids else {"custom": args.ids}
    for name, ids in jobs.items():
        path = OUT / f"{name}.wav"
        join(ids).export(path, format="wav")
        print(f"{name}: {S.to_text(ids)}\n   {S.to_text(ids, 'en')}\n   -> {path}")
        if args.play:
            if platform.system() == "Windows":
                os.startfile(path)  # type: ignore[attr-defined]
            elif platform.system() == "Darwin":
                subprocess.run(["afplay", str(path)])


if __name__ == "__main__":
    main()
