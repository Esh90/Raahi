"""Voice navigation grammar (android/app/src/main/assets/voice_commands.json).
The same sentences are checked on the phone side by VoiceCommandsTest.kt."""
import json

import pytest

from server.voice.app_commands import COMMANDS_JSON, match

CASES = [
    # --- navigation (English)
    ("Raahi go to settings", "nav_settings"),
    ("raahi open settings", "nav_settings"),
    ("Raahi, accessibility settings", "nav_accessibility"),
    ("raahi accessibility features", "nav_accessibility"),
    ("raahi voice settings", "nav_voice_settings"),
    ("raahi guidance settings", "nav_guidance_settings"),
    ("raahi connection", "nav_connection"),
    ("raahi appearance", "nav_appearance"),
    ("raahi history", "nav_history"),
    ("raahi help", "nav_help"),
    ("raahi privacy", "nav_privacy"),
    ("raahi about raahi", "nav_about"),
    ("raahi go home", "nav_home"),
    ("raahi go back", "nav_back"),
    ("raahi diagnostics", "nav_diagnostics"),
    ("raahi quick commands", "nav_quick"),
    # --- navigation (Urdu script, as Scribe writes it)
    ("راہی سیٹنگز کھولو", "nav_settings"),
    ("راہی، سیٹنگ", "nav_settings"),
    ("راہی ایکسیسبلٹی", "nav_accessibility"),
    ("راہی ہوم پر جاؤ", "nav_home"),
    ("راہی واپس جاؤ", "nav_back"),
    ("راہی ہسٹری", "nav_history"),
    ("راہی مدد", "nav_help"),
    ("راحی سیٹنگز", "nav_settings"),            # common misspelling of the name
    # --- Roman Urdu
    ("raahi settings kholo", "nav_settings"),
    ("raahi wapas jao", "nav_back"),
    ("raahi awaz ki settings", "nav_voice_settings"),
    # --- theme / display
    ("Raahi change theme to dark", "theme_dark"),
    ("raahi dark mode", "theme_dark"),
    ("raahi light mode", "theme_light"),
    ("raahi switch to light theme", "theme_light"),
    ("راہی ڈارک موڈ", "theme_dark"),
    ("راہی لائٹ موڈ", "theme_light"),
    ("راہی ڈارک موڈ بند", "theme_light"),
    ("raahi dark mode off", "theme_light"),
    ("raahi system theme", "theme_system"),
    ("raahi high contrast", "contrast_on"),
    ("raahi high contrast off", "contrast_off"),
    ("راہی ہائی کنٹراسٹ", "contrast_on"),
    ("raahi bigger text", "text_bigger"),
    ("raahi smaller text", "text_smaller"),
    # --- language / audio
    ("raahi switch to english", "lang_english"),
    ("raahi english", "lang_english"),
    ("راہی انگلش میں بولو", "lang_english"),
    ("راہی اردو", "lang_urdu"),
    ("raahi urdu mein bolo", "lang_urdu"),
    ("raahi volume up", "volume_up"),
    ("raahi louder", "volume_up"),
    ("راہی آواز کم کرو", "volume_down"),
    ("raahi speak slower", "speed_slow"),
    ("raahi speak faster", "speed_fast"),
    ("raahi vibration off", "haptics_off"),
    # --- guidance control
    ("Raahi start detecting", "guidance_start"),
    ("raahi start", "guidance_start"),
    ("راہی شروع کرو", "guidance_start"),
    ("raahi shuru karo", "guidance_start"),
    ("raahi stop detecting", "guidance_stop"),
    ("raahi stop", "guidance_stop"),
    ("راہی بند کرو", "guidance_stop"),
    ("raahi pause", "guidance_pause"),
    ("راہی رک جاؤ", "guidance_pause"),
    ("راہی رک جا", "guidance_pause"),                # how Scribe actually wrote it in testing
    ("Raahi settings کھولو", "nav_settings"),         # Scribe mixes scripts
    ("Raahi dark mode کر دو", "theme_dark"),
    ("raahi resume", "guidance_resume"),
    ("raahi continue", "guidance_resume"),
    ("راہی دوبارہ شروع کرو", "guidance_resume"),
    ("raahi what's ahead", "describe"),
    ("راہی سامنے کیا ہے", "describe"),
    ("raahi samne kya hai", "describe"),
    ("raahi repeat", "repeat"),
    ("راہی دوبارہ بولو", "repeat"),
    # --- typos
    ("raahi setings", "nav_settings"),
    ("raahi acessibility", "nav_accessibility"),
    ("raahee open settings", "nav_settings"),
    # --- not commands
    ("raahi aaj mausam kaisa hai", "unknown"),
    ("raahi", "wake_only"),
    ("راہی", "wake_only"),
]


@pytest.mark.parametrize("text,expected", CASES)
def test_commands(text, expected):
    assert match(text) == expected


@pytest.mark.parametrize("text", ["go to settings", "dark mode", "aaj mausam acha hai", "", "  "])
def test_without_wake_word_is_ignored(text):
    assert match(text, require_wake=True) is None


@pytest.mark.parametrize("text,expected", [
    ("go to settings", "nav_settings"),       # push-to-talk: no wake word needed
    ("ڈارک موڈ", "theme_dark"),
    ("start detecting", "guidance_start"),
    ("raahi help", "nav_help"),              # wake word is also fine
])
def test_push_to_talk(text, expected):
    assert match(text, require_wake=False) == expected


def test_grammar_ids_unique_and_no_find():
    g = json.loads(COMMANDS_JSON.read_text(encoding="utf-8"))
    ids = [c["id"] for c in g["commands"]]
    assert len(ids) == len(set(ids))
    assert not any(i.startswith("find") for i in ids)   # find was removed from voice on purpose


def test_no_phrase_claimed_by_two_commands():
    from server.voice.app_commands import grammar
    seen = {}
    for cid, phrases in grammar()["_cmds"]:
        for p in phrases:
            assert p not in seen, f"'{p}' in both {seen.get(p)} and {cid}"
            seen[p] = cid
