package com.raahi.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.raahi.app.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "raahi_settings")

object SpeechSpeed {
    const val SLOW = 0
    const val NORMAL = 1
    const val FAST = 2
    fun factor(i: Int) = when (i) { SLOW -> 0.85f; FAST -> 1.2f; else -> 1.0f }
}

object GuidanceDisplay {
    const val INSTRUCTION = 0 // camera strip + big instruction (approved main layout)
    const val CAMERA = 1 // expanded camera first
    const val SPLIT = 2 // camera and instruction share the screen
}

object ThemeChoice {
    const val LIGHT = 0
    const val DARK = 1
    const val SYSTEM = 2
}

data class AppSettings(
    val onboardingDone: Boolean = false,
    /** What the user typed under Connection; blank means "use the build default". */
    val serverUrlOverride: String = "",
    val textSizeIndex: Int = 1, // 0..3 -> A A A A
    val highContrast: Boolean = false,
    val reduceMotion: Boolean = false,
    val haptics: Boolean = true,
    val largeControls: Boolean = false,
    val language: String = "ur", // "ur" | "en": which voice speaks the guidance clips (same clips in both voices)
    /** Voice guidance on/off (key kept as "urdu_voice" so existing installs keep their choice). */
    val urduVoice: Boolean = true,
    val englishSubtitles: Boolean = true,
    val volume: Float = 0.8f,
    val speed: Int = SpeechSpeed.NORMAL,
    val detectionOverlay: Boolean = true,
    val keepAwake: Boolean = true,
    val guidanceDisplay: Int = GuidanceDisplay.INSTRUCTION,
    val theme: Int = ThemeChoice.LIGHT, // first launch is light; Dark / System are chosen in Appearance
    /** "Raahi, <command>" any time the app is open (core/voice/HandsFreeListener). */
    val handsFreeVoice: Boolean = true,
) {
    /** Effective server URL: user override, else the build-time default (may be empty in release). */
    val serverUrl: String get() = serverUrlOverride.ifBlank { BuildConfig.DEFAULT_SERVER_URL }
}

class SettingsRepository(private val context: Context) {
    private object K {
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val serverUrl = stringPreferencesKey("server_url")
        val textSize = intPreferencesKey("text_size")
        val highContrast = booleanPreferencesKey("high_contrast")
        val reduceMotion = booleanPreferencesKey("reduce_motion")
        val haptics = booleanPreferencesKey("haptics")
        val largeControls = booleanPreferencesKey("large_controls")
        val language = stringPreferencesKey("language")
        val urduVoice = booleanPreferencesKey("urdu_voice")
        val englishSubtitles = booleanPreferencesKey("english_subtitles")
        val volume = floatPreferencesKey("volume")
        val speed = intPreferencesKey("speed")
        val overlay = booleanPreferencesKey("detection_overlay")
        val keepAwake = booleanPreferencesKey("keep_awake")
        val display = intPreferencesKey("guidance_display")
        val theme = intPreferencesKey("theme")
        val handsFree = booleanPreferencesKey("hands_free_voice")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            onboardingDone = p[K.onboardingDone] ?: d.onboardingDone,
            serverUrlOverride = p[K.serverUrl] ?: d.serverUrlOverride,
            textSizeIndex = (p[K.textSize] ?: d.textSizeIndex).coerceIn(0, 3),
            highContrast = p[K.highContrast] ?: d.highContrast,
            reduceMotion = p[K.reduceMotion] ?: d.reduceMotion,
            haptics = p[K.haptics] ?: d.haptics,
            largeControls = p[K.largeControls] ?: d.largeControls,
            language = p[K.language] ?: d.language,
            urduVoice = p[K.urduVoice] ?: d.urduVoice,
            englishSubtitles = p[K.englishSubtitles] ?: d.englishSubtitles,
            volume = (p[K.volume] ?: d.volume).coerceIn(0f, 1f),
            speed = (p[K.speed] ?: d.speed).coerceIn(0, 2),
            detectionOverlay = p[K.overlay] ?: d.detectionOverlay,
            keepAwake = p[K.keepAwake] ?: d.keepAwake,
            guidanceDisplay = (p[K.display] ?: d.guidanceDisplay).coerceIn(0, 2),
            theme = (p[K.theme] ?: d.theme).coerceIn(0, 2),
            handsFreeVoice = p[K.handsFree] ?: d.handsFreeVoice,
        )
    }

    suspend fun setOnboardingDone(v: Boolean) = edit { it[K.onboardingDone] = v }
    suspend fun setServerUrl(v: String) = edit { it[K.serverUrl] = v.trim() }
    suspend fun setTextSize(v: Int) = edit { it[K.textSize] = v.coerceIn(0, 3) }
    suspend fun setHighContrast(v: Boolean) = edit { it[K.highContrast] = v }
    suspend fun setReduceMotion(v: Boolean) = edit { it[K.reduceMotion] = v }
    suspend fun setHaptics(v: Boolean) = edit { it[K.haptics] = v }
    suspend fun setLargeControls(v: Boolean) = edit { it[K.largeControls] = v }
    suspend fun setUrduVoice(v: Boolean) = edit { it[K.urduVoice] = v }
    suspend fun setEnglishSubtitles(v: Boolean) = edit { it[K.englishSubtitles] = v }
    suspend fun setVolume(v: Float) = edit { it[K.volume] = v.coerceIn(0f, 1f) }
    suspend fun setSpeed(v: Int) = edit { it[K.speed] = v.coerceIn(0, 2) }
    suspend fun setDetectionOverlay(v: Boolean) = edit { it[K.overlay] = v }
    suspend fun setKeepAwake(v: Boolean) = edit { it[K.keepAwake] = v }
    suspend fun setGuidanceDisplay(v: Int) = edit { it[K.display] = v.coerceIn(0, 2) }
    suspend fun setTheme(v: Int) = edit { it[K.theme] = v.coerceIn(0, 2) }
    suspend fun setHandsFreeVoice(v: Boolean) = edit { it[K.handsFree] = v }

    /** Language switch: the guidance voice speaks the selected language (Urdu or English clips); voice stays on. */
    suspend fun setLanguage(lang: String) = edit {
        it[K.language] = if (lang == "en") "en" else "ur"
        it[K.urduVoice] = true
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsStore.edit { block(it) }
    }
}
