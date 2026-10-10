"""Wake word + voice command parsing (Urdu script, Roman Urdu, English, mixed).

    parse("بصیرت دروازہ ڈھونڈو")          -> Intent("find", "door")
    parse("raahi saamne kya hai")          -> Intent("describe")
    parse("kursi dhoondo", require_wake=False)  -> Intent("find", "chair")   # double-tap path
    parse("aaj mausam acha hai")          -> None   (no wake word -> ignore)
    parse("raahi blah blah")               -> Intent("unknown")
"""
from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass

from rapidfuzz import fuzz

# Change the app name here only.
WAKE_WORDS = ["راہی", "raahi", "rahi", "raahee", "rahee", "بصیرت", "basirat", "baseerat", "basirat ji"]
WAKE_THRESHOLD = 80
PHRASE_THRESHOLD = 85


@dataclass
class Intent:
    type: str                 # "find" | "describe" | "stop" | "pause" | "resume" | "repeat" | "unknown"
    target: str | None = None

    def to_command(self) -> dict:
        d = {"type": "command", "intent": self.type}
        if self.target:
            d["target"] = self.target
        return d


# --- normalisation ---------------------------------------------------------

_URDU_VARIANTS = str.maketrans({
    "ي": "ی", "ى": "ی", "ې": "ی", "ئ": "ی",   # yeh variants
    "ك": "ک",                               # kaf
    "ە": "ہ", "ة": "ہ",            # heh variants
    "ۀ": "ہ", "ۂ": "ہ", "ۃ": "ہ",
    "أ": "ا", "إ": "ا", "آ": "آ",
    "ؤ": "و",
    "٠": "0", "١": "1", "٢": "2", "٣": "3", "٤": "4", "٥": "5", "٦": "6", "٧": "7", "٨": "8", "٩": "9",
    "۰": "0", "۱": "1", "۲": "2", "۳": "3", "۴": "4", "۵": "5", "۶": "6", "۷": "7", "۸": "8", "۹": "9",
})
_DIACRITICS = re.compile(r"[ً-ٰٟۖ-ۭ]")   # zabar/zer/pesh etc.
_PUNCT = re.compile(r"[^\w\s]", re.UNICODE)


def normalise(text: str) -> str:
    text = unicodedata.normalize("NFC", text or "")
    text = text.translate(_URDU_VARIANTS)
    text = _DIACRITICS.sub("", text)
    text = text.replace("‌", " ").replace("‍", "")
    text = text.lower()
    text = _PUNCT.sub(" ", text)
    text = re.sub(r"(.)\1{2,}", r"\1\1", text)          # "dhooondo" -> "dhoondo"
    return re.sub(r"\s+", " ", text).strip()


# --- vocab -----------------------------------------------------------------

# Targets that find-mode supports, with every way people may say them.
TARGETS: dict[str, list[str]] = {
    "door":      ["دروازہ", "دروازے", "دروازا", "darwaza", "darwaze", "darwazah", "darwaaza", "door", "gate", "exit", "باہر", "ڈور", "گیٹ"],
    "chair":     ["کرسی", "کرسیاں", "kursi", "kursee", "kursi", "chair", "seat", "بیٹھنے کی جگہ", "چیئر", "چئیر"],
    "table":     ["میز", "mez", "maiz", "mez", "table", "desk", "ٹیبل", "ڈیسک"],
    "person":    ["کوئی ہے", "کوئی", "بندہ", "آدمی", "شخص", "koi hai", "koi", "banda", "aadmi", "admi", "person", "someone", "anyone", "somebody", "پرسن"],
    "sofa":      ["صوفہ", "صوفے", "sofa", "sofe", "couch"],
    "trash_bin": ["کوڑے دان", "کوڑا دان", "ڈسٹ بن", "kooray daan", "koore dan", "kooda daan", "dustbin", "dust bin", "trash", "bin", "kachra"],
}

# Phrases per intent (checked in order; first match wins). find needs a target.
INTENT_PHRASES: list[tuple[str, list[str]]] = [
    ("stop",     ["رک جاؤ", "رک جاو", "رکو مت ڈھونڈو", "بس کرو", "بس", "بند کرو", "ڈھونڈنا بند",
                  "ruk jao", "ruk jaao", "bas", "bas karo", "band karo", "stop", "cancel", "stop searching", "اسٹاپ", "سٹاپ"]),
    ("pause",    ["روکو", "خاموش", "چپ", "خاموش ہو جاؤ", "roko", "khamosh", "chup", "pause", "quiet", "be quiet", "mute"]),
    ("resume",   ["شروع کرو", "دوبارہ شروع", "چلو", "shuru karo", "shuru", "dobara shuru", "chalo", "start", "resume", "continue", "unmute"]),
    ("repeat",   ["دوبارہ بولو", "پھر سے", "کیا کہا", "dobara bolo", "phir se", "kya kaha", "repeat", "say again", "again"]),
    ("describe", ["سامنے کیا ہے", "آگے کیا ہے", "کیا ہے سامنے", "کیا نظر آ رہا ہے", "بتاؤ کیا ہے", "ارد گرد کیا ہے",
                  "saamne kya hai", "samne kya hai", "samnay kya hai", "aage kya hai", "agay kya hai", "kya hai samne",
                  "kya nazar aa raha hai", "what is ahead", "whats ahead", "what's ahead", "what is in front",
                  "describe", "what do you see", "around me"]),
]
FIND_VERBS = ["ڈھونڈو", "ڈھونڈیں", "ڈھونڈ", "تلاش", "کہاں ہے", "کدھر ہے", "کہاں", "کدھر", "لے چلو", "لے جاؤ", "تک لے",
              "dhoondo", "dhundo", "dhoondho", "dhoondhein", "dhoondain", "dhoond", "talash", "kahan hai", "kahan", "kidhar",
              "kidhar hai", "le chalo", "le jao", "find", "where is", "where", "take me", "look for", "search", "guide me", "فائنڈ", "ویئر از"]


# --- matching --------------------------------------------------------------

def _tokens(text: str) -> list[str]:
    return text.split()


def _ngrams(tokens: list[str], n_max: int = 4) -> list[str]:
    out = []
    for n in range(1, n_max + 1):
        for i in range(len(tokens) - n + 1):
            out.append(" ".join(tokens[i:i + n]))
    return out


def _contains(text: str, phrase: str, threshold: int = PHRASE_THRESHOLD) -> bool:
    """Fuzzy 'phrase appears in text'. Exact substring on word boundaries first, then
    fuzzy match against n-grams of the same word count (handles typos)."""
    phrase = normalise(phrase)
    if not phrase:
        return False
    if re.search(rf"(?<!\w){re.escape(phrase)}(?!\w)", text):
        return True
    n = len(phrase.split())
    toks = _tokens(text)
    for i in range(len(toks) - n + 1):
        cand = " ".join(toks[i:i + n])
        # very short words (bas, bin, koi) must match exactly - fuzzy is too loose
        if len(phrase) <= 4:
            continue
        if fuzz.ratio(cand, phrase) >= threshold:
            return True
    return False


def strip_wake_word(text: str) -> tuple[bool, str]:
    """Returns (found, text_after_wake_word). Wake word may be anywhere in the first 3 words."""
    toks = _tokens(text)
    wakes = [normalise(w) for w in WAKE_WORDS]
    for i, tok in enumerate(toks[:3]):
        for w in wakes:
            # 'ji' after the name is common ("raahi ji")
            if tok == w or (len(tok) > 3 and fuzz.ratio(tok, w) >= WAKE_THRESHOLD):
                rest = toks[i + 1:]
                if rest and rest[0] in ("ji", "جی"):
                    rest = rest[1:]
                return True, " ".join(rest)
    return False, text


def match_target(text: str) -> str | None:
    best, best_pos = None, 10**9
    for target, words in TARGETS.items():
        for w in words:
            if _contains(text, w):
                pos = text.find(normalise(w))
                pos = pos if pos >= 0 else 10**8
                # prefer the earliest-mentioned target, longer phrase on ties
                if pos < best_pos:
                    best, best_pos = target, pos
    return best


def match_command(text: str) -> Intent:
    if not text:
        return Intent("unknown")
    target = match_target(text)
    has_find_verb = any(_contains(text, v) for v in FIND_VERBS)

    # "darwaza dhoondo" / "kursi kahan hai" / "find the door"
    if target and has_find_verb:
        return Intent("find", target)

    for intent, phrases in INTENT_PHRASES:
        if any(_contains(text, p) for p in phrases):
            return Intent(intent)

    # "koi hai?" alone means "is anyone there" -> find person
    if target == "person":
        return Intent("find", "person")
    # bare object name after the wake word ("raahi, darwaza") -> treat as find
    if target and len(_tokens(text)) <= 2:
        return Intent("find", target)
    return Intent("unknown")


def parse(text: str, require_wake: bool = True) -> Intent | None:
    """None = no wake word (ignore the transcript).
    require_wake=False when the user double-tapped to talk."""
    norm = normalise(text)
    if not norm:
        return None
    found, rest = strip_wake_word(norm)
    if not found:
        if require_wake:
            return None
        rest = norm
    return match_command(rest)


# clip reply for each intent (used by the server to confirm instantly)
def confirmation_clips(intent: Intent) -> list[str]:
    if intent.type == "find":
        from voice_assets.sentence import find_confirm
        return find_confirm(intent.target or "")
    return {
        "describe": [],                 # the description itself is the reply
        "stop": ["find_cancelled"],
        "pause": ["sys_paused"],
        "resume": ["sys_resumed"],
        "repeat": [],
        "unknown": ["sys_not_understood"],
    }.get(intent.type, ["sys_not_understood"])
