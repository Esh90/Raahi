"""Record many clips at once: one ElevenLabs generation per batch, auto-split into clips.

1) Make the batch texts (only clips not yet in raw/):
       python -m voice_assets.batch_split make
   -> voice_assets/BATCHES_TO_RECORD.txt   (one block of text per batch)

2) In ElevenLabs paste ONE whole batch block, generate, download, save as
       voice_assets/batches/batch_01.mp3   (batch_02.mp3, ... matching the batch number)

3) Split:
       python -m voice_assets.batch_split split            # all batches/*.mp3
       python -m voice_assets.batch_split split --batch 3  # just one
   -> voice_assets/raw/<id>.mp3 for every phrase, then run process_clips.

How splitting works: the batch has N phrases separated by long pauses ("..." lines).
We find all silent gaps and cut at the N-1 LONGEST ones, so short pauses inside a phrase
(after a comma) don't matter. Then each piece is checked against its expected length.
If the gap count doesn't fit, nothing is written and you get told which batch to regenerate.
"""
from __future__ import annotations

import argparse
import json
import re
import statistics
import sys

from pydub import AudioSegment
from pydub.silence import detect_silence

from voice_assets import audio_utils
from voice_assets.library import RAW_DIR, VOICE_DIR, recordable

BATCH_DIR = VOICE_DIR / "batches"
PLAN_JSON = BATCH_DIR / "plan.json"
OUT_TXT = VOICE_DIR / "BATCHES_TO_RECORD.txt"
MAX_ITEMS = 14
MAX_CHARS = 260
SEPARATOR = "\n...\n"
MIN_GAP_MS = 120

HEADER = """\
RAAHI - BATCH RECORDING (fast way)
=================================
For EACH batch below:
 1. ElevenLabs -> Text to Speech -> Eleven v3 -> the SAME female voice + settings as before.
 2. Copy everything between the ===== lines of ONE batch (the phrases and the "..." lines)
    and paste it as ONE text. Generate. Listen once: every phrase spoken, with a clear pause
    between phrases? If a phrase is skipped or mumbled, just regenerate.
 3. Download and save as:   voice_assets/batches/batch_NN.mp3   (NN = the batch number)
 4. When you have them all (or any time):
        python -m voice_assets.batch_split split
        python -m voice_assets.process_clips
Batches marked SOLO are one long phrase; they're saved directly (no splitting needed).
"""


def _is_solo(text: str) -> bool:
    # sentences with an internal full stop pause as long as our separators -> record alone
    return len(re.findall(r"[۔.!؟?]", text.rstrip("۔.!؟? "))) > 0


def _speakable(text: str) -> str:
    t = text.strip()
    return t if t[-1] in "۔.!؟?،," else t + "۔"


def make() -> None:
    todo = [c for c in recordable() if not any((RAW_DIR / f"{c['id']}{e}").exists() for e in (".mp3", ".wav"))]
    batches, cur = [], []
    for c in todo:
        if _is_solo(c["urdu_text"]):
            batches.append([c])
            continue
        if cur and (len(cur) >= MAX_ITEMS or sum(len(x["urdu_text"]) for x in cur) + len(c["urdu_text"]) > MAX_CHARS
                    or cur[-1]["category"] != c["category"] and len(cur) >= MAX_ITEMS // 2):
            batches.append(cur)
            cur = []
        cur.append(c)
    if cur:
        batches.append(cur)

    BATCH_DIR.mkdir(parents=True, exist_ok=True)
    plan = {f"{i + 1:02d}": [c["id"] for c in b] for i, b in enumerate(batches)}
    PLAN_JSON.write_text(json.dumps(plan, indent=1), encoding="utf-8")

    lines = [HEADER, f"{len(todo)} clips in {len(batches)} batches (instead of {len(todo)} downloads).\n"]
    for num, b in zip(plan, batches):
        solo = " (SOLO)" if len(b) == 1 else ""
        lines.append(f"\n########## BATCH {num}{solo}  ->  save as batches/batch_{num}.mp3   ({len(b)} phrases)")
        lines.append("  contains: " + ", ".join(c["id"] for c in b))
        lines.append("=" * 40)
        lines.append(SEPARATOR.join(_speakable(c["urdu_text"]) for c in b))
        lines.append("=" * 40)
    OUT_TXT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"{len(todo)} clips -> {len(batches)} batches. Open {OUT_TXT}")


def _choose_gaps(gaps: list[list[int]], total_ms: int, texts: list[str]) -> list[list[int]]:
    """Pick n-1 of the gaps so the pieces best fit the phrases.

    Pauses after a comma inside a phrase can be as long as the pauses between phrases, so
    "longest gaps" alone is not enough. DP over gap choices, cost =
        sum over phrases of log(actual_ms / expected_ms)^2   (expected ~ number of characters)
      - GAP_WEIGHT * sum of log(gap length)                  (still prefer long gaps)
    """
    import math

    n, k = len(texts), len(gaps)
    chars = [max(2, len(t.strip())) for t in texts]
    speech_total = total_ms - sum(b - a for a, b in gaps)
    ms_per_char = max(1.0, speech_total / sum(chars))
    GAP_WEIGHT = 0.35

    def seg_cost(i_phrase: int, start: int, end: int) -> float:
        dur = max(40, end - start)
        exp = chars[i_phrase] * ms_per_char
        return math.log(dur / exp) ** 2

    # bounds: index -1 = audio start, index k = audio end
    def seg_start(gi):  # speech starts after gap gi
        return 0 if gi < 0 else gaps[gi][1]

    def seg_end(gi):
        return total_ms if gi >= k else gaps[gi][0]

    INF = float("inf")
    # best[p][g] = min cost having placed phrase p ending at gap g (p-th cut = gap g)
    best = [[INF] * k for _ in range(n - 1)]
    back = [[-1] * k for _ in range(n - 1)]
    for g in range(k):
        best[0][g] = seg_cost(0, seg_start(-1), seg_end(g)) - GAP_WEIGHT * math.log(gaps[g][1] - gaps[g][0])
    for p in range(1, n - 1):
        for g in range(p, k):
            for h in range(p - 1, g):
                if best[p - 1][h] == INF:
                    continue
                c = (best[p - 1][h] + seg_cost(p, seg_start(h), seg_end(g))
                     - GAP_WEIGHT * math.log(gaps[g][1] - gaps[g][0]))
                if c < best[p][g]:
                    best[p][g], back[p][g] = c, h
    final = min(range(k), key=lambda g: best[n - 2][g] + seg_cost(n - 1, seg_start(g), seg_end(k))
                if best[n - 2][g] < INF else INF)
    idx, g = [], final
    for p in range(n - 2, -1, -1):
        idx.append(g)
        g = back[p][g]
    return [gaps[i] for i in reversed(idx)]


def split_audio(seg: AudioSegment, texts: list[str]) -> list[AudioSegment] | str:
    """Cut seg into len(texts) pieces at the longest silent gaps. Returns error string on failure."""
    n = len(texts)
    if n == 1:
        return [seg]
    thresh = seg.dBFS - 18 if seg.dBFS != float("-inf") else -50
    gaps = detect_silence(seg, min_silence_len=MIN_GAP_MS, silence_thresh=thresh, seek_step=5)
    # ignore silence at the very start / end
    gaps = [g for g in gaps if g[0] > 50 and g[1] < len(seg) - 50]
    if len(gaps) < n - 1:
        return f"found only {len(gaps)} pauses for {n} phrases (a phrase was probably skipped or merged)"
    chosen = _choose_gaps(gaps, len(seg), texts)
    cuts = [0] + [(a + b) // 2 for a, b in chosen] + [len(seg)]
    pieces = [seg[cuts[i]:cuts[i + 1]] for i in range(n)]

    # sanity: speech duration per character should be similar across pieces
    speech_ms = [len(audio_utils.trim(p)) for p in pieces]
    rates = [ms / max(4, len(t)) for ms, t in zip(speech_ms, texts)]
    med = statistics.median(rates)
    # very short words ("and", "Yes?") have a fixed onset, so their per-letter rate is naturally high
    bad = [i for i, r in enumerate(rates) if (r > med * 2.6 and len(texts[i].strip()) > 4) or r < med / 2.6]
    if bad:
        return ("piece lengths don't match the text for: " + ", ".join(str(i + 1) for i in bad)
                + "  (phrase order/skip problem)")
    return pieces


def split(only: str | None, force: bool) -> int:
    if not PLAN_JSON.exists():
        print("run `python -m voice_assets.batch_split make` first")
        return 1
    plan = json.loads(PLAN_JSON.read_text(encoding="utf-8-sig"))
    texts = {c["id"]: c["urdu_text"] for c in recordable()}
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    failed = 0
    for num, ids in plan.items():
        if only and int(num) != int(only):
            continue
        src = next((p for e in (".mp3", ".wav", ".m4a") for p in [BATCH_DIR / f"batch_{num}{e}"] if p.exists()), None)
        if src is None:
            src = next(iter(sorted(BATCH_DIR.glob(f"batch_{num}*.mp3"))), None)   # e.g. batch_01.mp3.mp3
        if src is None:
            if only:
                print(f"batch {num}: no file batches/batch_{num}.mp3")
            continue
        res = split_audio(audio_utils.load(src), [texts[i] for i in ids])
        if isinstance(res, str):
            print(f"batch {num}: NOT SAVED - {res}. Regenerate this batch (or record its phrases one by one).")
            failed += 1
            continue
        saved = 0
        for cid, piece in zip(ids, res):
            out = RAW_DIR / f"{cid}.mp3"
            if out.exists() and not force:
                continue
            audio_utils.export_mp3(audio_utils.trim(piece), out)
            saved += 1
        print(f"batch {num}: OK, {saved} clips saved -> raw/  ({', '.join(ids)})")
    return 1 if failed else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["make", "split"])
    ap.add_argument("--batch", default=None, help="only this batch number")
    ap.add_argument("--force", action="store_true", help="overwrite clips already in raw/")
    a = ap.parse_args()
    if a.cmd == "make":
        make()
        return 0
    return split(a.batch, a.force)


if __name__ == "__main__":
    sys.exit(main())
