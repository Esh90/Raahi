"""Placeholder clips so the whole pipeline (server, phone audio queue) works before the
real Urdu recordings exist. Uses the OS speech engine to speak the ENGLISH meaning:
  Windows -> SAPI (System.Speech),  macOS -> `say`,  else -> a short tone.

    python -m voice_assets.make_placeholder_clips
    python -m voice_assets.process_clips
"""
from __future__ import annotations

import platform
import subprocess
import tempfile
from pathlib import Path

from pydub import AudioSegment
from pydub.generators import Sine

from voice_assets import audio_utils  # noqa: F401  (sets up ffmpeg)
from voice_assets.library import VOICE_DIR, recordable

OUT_DIR = VOICE_DIR / "placeholder"


def _speak_windows(texts: dict[str, str], tmp: Path) -> None:
    # one PowerShell process for all clips (fast)
    lines = ["Add-Type -AssemblyName System.Speech",
             "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer",
             "$s.Rate = 1"]
    for cid, text in texts.items():
        safe = text.replace("'", "''")
        lines.append(f"$s.SetOutputToWaveFile('{tmp / (cid + '.wav')}'); $s.Speak('{safe}')")
    lines.append("$s.Dispose()")
    script = tmp / "speak.ps1"
    script.write_text("\n".join(lines), encoding="utf-8-sig")
    subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(script)],
                   check=True)


def _speak_mac(texts: dict[str, str], tmp: Path) -> None:
    for cid, text in texts.items():
        subprocess.run(["say", "-o", str(tmp / f"{cid}.aiff"), text], check=True)


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    texts = {c["id"]: c["en"] for c in recordable()}
    system = platform.system()
    with tempfile.TemporaryDirectory() as d:
        tmp = Path(d)
        try:
            if system == "Windows":
                _speak_windows(texts, tmp)
            elif system == "Darwin":
                _speak_mac(texts, tmp)
        except Exception as e:  # fall back to tones
            print(f"OS speech failed ({e}); using tones")
        for cid in texts:
            src = next(iter(tmp.glob(f"{cid}.*")), None)
            if src:
                seg = audio_utils.load(src)
            else:
                seg = Sine(440).to_audio_segment(duration=250).apply_gain(-12)
            audio_utils.export_mp3(audio_utils.finish(seg), OUT_DIR / f"{cid}.mp3")
    print(f"wrote {len(texts)} placeholder clips to {OUT_DIR}")


if __name__ == "__main__":
    main()
