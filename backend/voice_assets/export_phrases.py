"""Write voice_assets/PHRASES_TO_RECORD.txt from clips.json.

    python -m voice_assets.export_phrases
"""
from __future__ import annotations

from voice_assets.library import VOICE_DIR, recordable

OUT = VOICE_DIR / "PHRASES_TO_RECORD.txt"

HEADER = """\
RAAHI - URDU CLIPS TO GENERATE IN ELEVENLABS
============================================

HOW TO DO IT
1. ElevenLabs -> Text to Speech. Model: "Eleven v3". Pick ONE female voice and keep it
   (and its settings: Stability ~50, Similarity ~75, Style 0) for EVERY clip.
   Language: Urdu (if the UI asks).
2. For each line below: paste the URDU TEXT, generate, listen. If it sounds wrong,
   regenerate. Then download the MP3.
3. Rename the download to exactly the FILENAME shown (e.g. obj_chair.mp3) and put it in:
       voice_assets/raw/
4. When done (or any time), run:   python -m voice_assets.process_clips
   It trims silence, evens out loudness, copies clips into the app and lists any missing.

TIPS
- Short words (objects) sometimes come out rushed. If so, generate the word
  followed by a full stop, e.g. "کرسی۔"  - the trimmer removes extra silence.
- Keep the same voice for everything; mixed voices sound broken when clips are joined.
- Lines are grouped; the most important groups come first (urgent, objects, positions).

FORMAT:   FILENAME | URDU TEXT | ENGLISH MEANING
"""

ORDER = ["urgent", "object", "position", "distance", "move", "find", "connector", "system"]


def main() -> None:
    items = recordable()
    lines = [HEADER]
    total_chars = 0
    for cat in ORDER:
        group = [c for c in items if c["category"] == cat]
        if not group:
            continue
        lines.append(f"\n---------- {cat.upper()} ({len(group)}) ----------")
        for c in group:
            lines.append(f"{c['id']}.mp3 | {c['urdu_text']} | {c['en']}")
            total_chars += len(c["urdu_text"])
    lines.append(f"\nTOTAL: {len(items)} clips, ~{total_chars} characters of credits.\n")
    OUT.write_text("\n".join(lines), encoding="utf-8")
    print(f"wrote {OUT} ({len(items)} clips, {total_chars} chars)")


if __name__ == "__main__":
    main()
