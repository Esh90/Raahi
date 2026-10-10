import pytest

from server.voice.intents import Intent, confirmation_clips, normalise, parse

WAKE_CASES = [
    # Urdu script
    ("راہی دروازہ ڈھونڈو", Intent("find", "door")),
    ("راہی، دروازہ کہاں ہے؟", Intent("find", "door")),
    ("بصیرت کرسی ڈھونڈو", Intent("find", "chair")),
    ("راہی میز ڈھونڈیں", Intent("find", "table")),
    ("راہی کوئی ہے؟", Intent("find", "person")),
    ("راہی سامنے کیا ہے", Intent("describe")),
    ("راہی آگے کیا ہے؟", Intent("describe")),
    ("راہی رک جاؤ", Intent("stop")),
    ("راہی بس کرو", Intent("stop")),
    ("راہی خاموش", Intent("pause")),
    ("راہی شروع کرو", Intent("resume")),
    ("راہی کوڑے دان کہاں ہے", Intent("find", "trash_bin")),
    ("راہی صوفہ ڈھونڈو", Intent("find", "sofa")),
    # Roman Urdu
    ("raahi darwaza dhoondo", Intent("find", "door")),
    ("Raahi, darwaza kahan hai?", Intent("find", "door")),
    ("raahi kursi dhundo", Intent("find", "chair")),
    ("basirat mez dhoondo", Intent("find", "table")),
    ("raahi koi hai", Intent("find", "person")),
    ("raahi saamne kya hai", Intent("describe")),
    ("raahi samne kya hai", Intent("describe")),
    ("raahi ruk jao", Intent("stop")),
    ("raahi bas", Intent("stop")),
    ("raahi khamosh", Intent("pause")),
    ("raahi chalo", Intent("resume")),
    ("raahi ji dobara bolo", Intent("repeat")),
    # typos / fuzzy
    ("raahi darwaaza dhooondo", Intent("find", "door")),
    ("rahee kursee kahan hai", Intent("find", "chair")),
    ("baseerat saamnay kya hai", Intent("describe")),
    # English + mixed
    ("raahi find the door", Intent("find", "door")),
    ("raahi where is the chair", Intent("find", "chair")),
    ("raahi what's ahead", Intent("describe")),
    ("raahi stop", Intent("stop")),
    ("raahi pause", Intent("pause")),
    ("raahi door kahan hai", Intent("find", "door")),
    ("راہی door ڈھونڈو", Intent("find", "door")),
    ("raahi, darwaza", Intent("find", "door")),
    # Scribe may spell the name "rahi" - still the wake word
    ("rahi darwaza dhoondo", Intent("find", "door")),
    # wake word but nonsense
    ("raahi aaj mausam kaisa hai", Intent("unknown")),
]


@pytest.mark.parametrize("text,expected", WAKE_CASES)
def test_with_wake_word(text, expected):
    assert parse(text) == expected


@pytest.mark.parametrize("text", [
    "darwaza dhoondo",          # no wake word
    "aaj mausam acha hai",
    "دروازہ بند کرو",
    "",
    "   ",
])
def test_no_wake_word_is_ignored(text):
    assert parse(text) is None


@pytest.mark.parametrize("text,expected", [
    ("darwaza dhoondo", Intent("find", "door")),
    ("saamne kya hai", Intent("describe")),
    ("کرسی کہاں ہے", Intent("find", "chair")),
    ("ruk jao", Intent("stop")),
])
def test_double_tap_no_wake_needed(text, expected):
    assert parse(text, require_wake=False) == expected


def test_normalise_unifies_letters():
    assert normalise("كرسي") == normalise("کرسی")
    assert normalise("Darwaza!!!") == "darwaza"


def test_confirmation_clips():
    assert confirmation_clips(Intent("find", "door")) == ["cmd_finding_door"]
    assert confirmation_clips(Intent("unknown")) == ["sys_not_understood"]
    assert confirmation_clips(Intent("pause")) == ["sys_paused"]
