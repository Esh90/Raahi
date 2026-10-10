"""Reference implementation of the Android app's on-device voice commands
(frontend/android/app/src/main/java/com/raahi/app/core/voice/VoiceCommands.kt is a line-for-line port).

The vocabulary lives in ONE file, frontend/android/app/src/main/assets/voice_commands.json, read by the app at
startup and by these tests. This module exists so the grammar can be tested without an Android build:

    match("Raahi, settings kholo", require_wake=True)   -> "nav_settings"
    match("ڈارک موڈ", require_wake=False)              -> "theme_dark"
    match("aaj mausam kaisa hai", require_wake=True)   -> None   (no wake word)
    match("raahi blah", require_wake=True)             -> "unknown"
"""
from __future__ import annotations

import json
import re
import unicodedata
from functools import lru_cache
from pathlib import Path

# repo/frontend/frontend/android/app/src/main/assets/voice_commands.json (this file: repo/backend/server/voice/)
COMMANDS_JSON = (Path(__file__).resolve().parents[3] / "frontend" / "android" / "app" / "src" / "main" / "assets"
                 / "voice_commands.json")
WAKE_RATIO = 0.80
PHRASE_RATIO = 0.85
MIN_FUZZY_LEN = 5          # phrases shorter than this must match exactly ("stop", "back", "dark")

_MAP = {
    "ي": "ی", "ى": "ی", "ې": "ی", "ئ": "ی", "ك": "ک", "ە": "ہ", "ة": "ہ", "ۀ": "ہ", "ۂ": "ہ", "ۃ": "ہ",
    "أ": "ا", "إ": "ا", "ؤ": "و",
}
_MARKS = re.compile(r"[ً-ٰٟۖ-ۭ]")


def normalise(text: str) -> str:
    t = unicodedata.normalize("NFC", text or "")
    t = "".join(_MAP.get(ch, ch) for ch in t)
    t = _MARKS.sub("", t).replace("‌", " ").replace("‍", "").lower()
    t = "".join(ch if ch.isalnum() else " " for ch in t)
    t = re.sub(r"(.)\1{2,}", r"\1\1", t)
    return " ".join(t.split())


def ratio(a: str, b: str) -> float:
    """1 - levenshtein / max_len (same formula as the Kotlin port)."""
    if a == b:
        return 1.0
    if not a or not b:
        return 0.0
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return 1.0 - prev[-1] / max(len(a), len(b))


@lru_cache
def grammar() -> dict:
    g = json.loads(COMMANDS_JSON.read_text(encoding="utf-8-sig"))
    g["_wake"] = [normalise(w) for w in g["wake_words"]]
    g["_cmds"] = [(c["id"], [normalise(p) for p in c["phrases"] if normalise(p)]) for c in g["commands"]]
    return g


def strip_wake(norm: str) -> tuple[bool, str]:
    toks = norm.split()
    for i, tok in enumerate(toks[:3]):
        for w in grammar()["_wake"]:
            if tok == w or (len(tok) > 3 and ratio(tok, w) >= WAKE_RATIO):
                rest = toks[i + 1:]
                if rest and rest[0] in ("ji", "جی"):
                    rest = rest[1:]
                return True, " ".join(rest)
    return False, norm


def _phrase_score(text: str, toks: list[str], phrase: str) -> int:
    """Length of [phrase] if it occurs in text (whole words, or fuzzily for longer phrases), else 0."""
    if f" {phrase} " in f" {text} ":
        return len(phrase)
    if len(phrase) < MIN_FUZZY_LEN:
        return 0
    n = len(phrase.split())
    for i in range(len(toks) - n + 1):
        if ratio(" ".join(toks[i:i + n]), phrase) >= PHRASE_RATIO:
            return len(phrase) - 1          # an exact match of equal length wins ties
    return 0


def match_command(norm_text: str) -> str:
    toks = norm_text.split()
    best_id, best = "unknown", 0
    for cid, phrases in grammar()["_cmds"]:
        for p in phrases:
            s = _phrase_score(norm_text, toks, p)
            if s > best:
                best_id, best = cid, s
    return best_id


def match(text: str, require_wake: bool = True) -> str | None:
    """Command id, "unknown", "wake_only" (just "Raahi"), or None (no wake word -> ignore)."""
    norm = normalise(text)
    if not norm:
        return None
    found, rest = strip_wake(norm)
    if not found and require_wake:
        return None
    if found and not rest:
        return "wake_only"
    return match_command(rest if found else norm)
