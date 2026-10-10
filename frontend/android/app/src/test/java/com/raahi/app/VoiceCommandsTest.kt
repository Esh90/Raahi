package com.raahi.app

import com.raahi.app.core.voice.VoiceCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * On-device voice command grammar (assets/voice_commands.json). The same sentences are checked by the backend
 * reference implementation (tests/test_app_commands.py), from which this list is generated - keep them in step.
 */
class VoiceCommandsTest {
    private val grammar = VoiceCommands(File("src/main/assets/voice_commands.json").readText(Charsets.UTF_8))

    private val cases = listOf(
        "Raahi go to settings" to "nav_settings",
        "raahi open settings" to "nav_settings",
        "Raahi, accessibility settings" to "nav_accessibility",
        "raahi accessibility features" to "nav_accessibility",
        "raahi voice settings" to "nav_voice_settings",
        "raahi guidance settings" to "nav_guidance_settings",
        "raahi connection" to "nav_connection",
        "raahi appearance" to "nav_appearance",
        "raahi history" to "nav_history",
        "raahi help" to "nav_help",
        "raahi privacy" to "nav_privacy",
        "raahi about raahi" to "nav_about",
        "raahi go home" to "nav_home",
        "raahi go back" to "nav_back",
        "raahi diagnostics" to "nav_diagnostics",
        "raahi quick commands" to "nav_quick",
        "راہی سیٹنگز کھولو" to "nav_settings",
        "راہی، سیٹنگ" to "nav_settings",
        "راہی ایکسیسبلٹی" to "nav_accessibility",
        "راہی ہوم پر جاؤ" to "nav_home",
        "راہی واپس جاؤ" to "nav_back",
        "راہی ہسٹری" to "nav_history",
        "راہی مدد" to "nav_help",
        "راحی سیٹنگز" to "nav_settings",
        "raahi settings kholo" to "nav_settings",
        "raahi wapas jao" to "nav_back",
        "raahi awaz ki settings" to "nav_voice_settings",
        "Raahi change theme to dark" to "theme_dark",
        "raahi dark mode" to "theme_dark",
        "raahi light mode" to "theme_light",
        "raahi switch to light theme" to "theme_light",
        "راہی ڈارک موڈ" to "theme_dark",
        "راہی لائٹ موڈ" to "theme_light",
        "راہی ڈارک موڈ بند" to "theme_light",
        "raahi dark mode off" to "theme_light",
        "raahi system theme" to "theme_system",
        "raahi high contrast" to "contrast_on",
        "raahi high contrast off" to "contrast_off",
        "راہی ہائی کنٹراسٹ" to "contrast_on",
        "raahi bigger text" to "text_bigger",
        "raahi smaller text" to "text_smaller",
        "raahi switch to english" to "lang_english",
        "raahi english" to "lang_english",
        "راہی انگلش میں بولو" to "lang_english",
        "راہی اردو" to "lang_urdu",
        "raahi urdu mein bolo" to "lang_urdu",
        "raahi volume up" to "volume_up",
        "raahi louder" to "volume_up",
        "راہی آواز کم کرو" to "volume_down",
        "raahi speak slower" to "speed_slow",
        "raahi speak faster" to "speed_fast",
        "raahi vibration off" to "haptics_off",
        "Raahi start detecting" to "guidance_start",
        "raahi start" to "guidance_start",
        "راہی شروع کرو" to "guidance_start",
        "raahi shuru karo" to "guidance_start",
        "raahi stop detecting" to "guidance_stop",
        "raahi stop" to "guidance_stop",
        "راہی بند کرو" to "guidance_stop",
        "raahi pause" to "guidance_pause",
        "راہی رک جاؤ" to "guidance_pause",
        "راہی رک جا" to "guidance_pause",
        "Raahi settings کھولو" to "nav_settings",
        "Raahi dark mode کر دو" to "theme_dark",
        "raahi resume" to "guidance_resume",
        "raahi continue" to "guidance_resume",
        "راہی دوبارہ شروع کرو" to "guidance_resume",
        "raahi what's ahead" to "describe",
        "راہی سامنے کیا ہے" to "describe",
        "raahi samne kya hai" to "describe",
        "raahi repeat" to "repeat",
        "راہی دوبارہ بولو" to "repeat",
        "raahi setings" to "nav_settings",
        "raahi acessibility" to "nav_accessibility",
        "raahee open settings" to "nav_settings",
        "raahi aaj mausam kaisa hai" to "unknown",
        "raahi" to "wake_only",
        "راہی" to "wake_only",
    )

    @Test
    fun recognisesCommandsInUrduRomanUrduAndEnglish() {
        val wrong = cases.filter { (text, expected) -> grammar.match(text, requireWake = true) != expected }
            .map { (text, expected) -> "$text -> ${grammar.match(text, requireWake = true)} (expected $expected)" }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test
    fun ignoresSentencesWithoutWakeWord() {
        listOf("go to settings", "dark mode", "aaj mausam acha hai", "", "  ").forEach {
            assertNull(it, grammar.match(it, requireWake = true))
        }
    }

    @Test
    fun pushToTalkNeedsNoWakeWord() {
        assertEquals("nav_settings", grammar.match("go to settings", requireWake = false))
        assertEquals("theme_dark", grammar.match("ڈارک موڈ", requireWake = false))
        assertEquals("guidance_start", grammar.match("start detecting", requireWake = false))
        assertEquals("nav_help", grammar.match("raahi help", requireWake = false))
    }

    @Test
    fun noFindCommandsAndUniqueIds() {
        assertTrue(grammar.ids.none { it.startsWith("find") })
        assertEquals(grammar.ids.size, grammar.ids.toSet().size)
    }

    @Test
    fun normalisationMatchesBackend() {
        assertEquals(VoiceCommands.normalise("کرسی"), VoiceCommands.normalise("كرسي"))
        assertEquals("what s ahead", VoiceCommands.normalise("What's   ahead?!"))
        assertEquals("dhoondo", VoiceCommands.normalise("dhooondo"))
        assertEquals(1.0, VoiceCommands.ratio("settings", "settings"), 1e-9)
        assertEquals(1.0 - 1.0 / 8, VoiceCommands.ratio("setings", "settings"), 1e-9)
    }
}
