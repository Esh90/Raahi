package com.raahi.app.core.voice

import com.raahi.app.core.audio.ClipPlayer
import com.raahi.app.core.navigation.Routes
import com.raahi.app.data.AppSettings
import com.raahi.app.data.SettingsRepository
import com.raahi.app.data.SpeechSpeed
import com.raahi.app.data.ThemeChoice
import com.raahi.app.domain.GuidanceSession
import com.raahi.app.domain.SessionPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/** Things only the UI layer can do (navigation, starting a camera session with its permission prompt). */
sealed interface UiRequest {
    data class Navigate(val route: String) : UiRequest
    data object Back : UiRequest
    data object StartGuidance : UiRequest
}

/**
 * Executes recognised voice commands ([VoiceCommands] ids) entirely on the phone - no server round trip,
 * so navigation / settings react as soon as the sentence is recognised. Settings change through the same
 * [SettingsRepository] the Settings screens use; guidance control goes through the app-wide [GuidanceSession].
 * Spoken feedback reuses the prerecorded clips ("ٹھیک ہے" / "Okay.", "سمجھ نہیں آیا" / "didn't catch that"),
 * so no new recordings are needed; screen changes are also announced by TalkBack via the screen titles.
 */
class CommandDispatcher(
    private val scope: CoroutineScope,
    private val settingsRepo: SettingsRepository,
    private val settings: StateFlow<AppSettings?>,
    private val session: GuidanceSession,
    private val player: ClipPlayer,
) {
    private val _requests = MutableSharedFlow<UiRequest>(extraBufferCapacity = 8)
    val requests: SharedFlow<UiRequest> = _requests.asSharedFlow()

    /** Last command handled (shown on the Voice screen / useful in logs). */
    @Volatile var lastCommand: String = ""
        private set

    /** Runs command [id]. Returns false for [VoiceCommands.UNKNOWN] (after saying "didn't catch that"). */
    fun execute(id: String): Boolean {
        lastCommand = id
        val s = settings.value ?: AppSettings()
        when (id) {
            "nav_home" -> go(Routes.HOME)
            "nav_settings" -> go(Routes.SETTINGS)
            "nav_accessibility" -> go(Routes.SET_ACCESSIBILITY)
            "nav_voice_settings" -> go(Routes.SET_VOICE)
            "nav_guidance_settings" -> go(Routes.SET_GUIDANCE)
            "nav_connection" -> go(Routes.SET_CONNECTION)
            "nav_appearance" -> go(Routes.SET_APPEARANCE)
            "nav_history" -> go(Routes.HISTORY)
            "nav_help" -> go(Routes.HELP)
            "nav_privacy" -> go(Routes.PRIVACY)
            "nav_about" -> go(Routes.ABOUT)
            "nav_diagnostics" -> go(Routes.DIAGNOSTICS)
            "nav_quick" -> go(Routes.QUICK)
            "nav_back" -> { _requests.tryEmit(UiRequest.Back); ok() }

            "theme_dark" -> setting { settingsRepo.setTheme(ThemeChoice.DARK) }
            "theme_light" -> setting { settingsRepo.setTheme(ThemeChoice.LIGHT) }
            "theme_system" -> setting { settingsRepo.setTheme(ThemeChoice.SYSTEM) }
            "contrast_on" -> setting { settingsRepo.setHighContrast(true) }
            "contrast_off" -> setting { settingsRepo.setHighContrast(false) }
            "text_bigger" -> setting { settingsRepo.setTextSize(s.textSizeIndex + 1) }
            "text_smaller" -> setting { settingsRepo.setTextSize(s.textSizeIndex - 1) }
            "lang_english" -> setting(afterMs = LANGUAGE_SWITCH_MS) { settingsRepo.setLanguage("en") }
            "lang_urdu" -> setting(afterMs = LANGUAGE_SWITCH_MS) { settingsRepo.setLanguage("ur") }
            "volume_up" -> setting { settingsRepo.setVolume(s.volume + VOLUME_STEP) }
            "volume_down" -> setting { settingsRepo.setVolume(s.volume - VOLUME_STEP) }
            "speed_slow" -> setting { settingsRepo.setSpeed(SpeechSpeed.SLOW) }
            "speed_fast" -> setting { settingsRepo.setSpeed(SpeechSpeed.FAST) }
            "speed_normal" -> setting { settingsRepo.setSpeed(SpeechSpeed.NORMAL) }
            "haptics_on" -> setting { settingsRepo.setHaptics(true) }
            "haptics_off" -> setting { settingsRepo.setHaptics(false) }

            "guidance_start" -> when (session.ui.value.phase) {
                SessionPhase.PAUSED -> session.resume() // the server confirms ("guidance resumed")
                SessionPhase.ACTIVE -> ok()
                SessionPhase.IDLE -> _requests.tryEmit(UiRequest.StartGuidance)
            }
            // ending the session makes the guidance screen return home by itself
            "guidance_stop" -> if (session.ui.value.phase == SessionPhase.IDLE) ok() else session.end()
            "guidance_pause" -> if (session.ui.value.phase == SessionPhase.ACTIVE) session.pause() else ok()
            "guidance_resume" -> when (session.ui.value.phase) {
                SessionPhase.PAUSED -> session.resume()
                SessionPhase.ACTIVE -> ok()
                SessionPhase.IDLE -> _requests.tryEmit(UiRequest.StartGuidance)
            }
            "describe" -> if (session.isActive) session.requestDescribe() else go(Routes.WHATS_AHEAD)
            "repeat" -> session.repeatLast()

            else -> {
                say("sys_not_understood")
                return false
            }
        }
        return true
    }

    private fun go(route: String) {
        _requests.tryEmit(UiRequest.Navigate(route))
        ok()
    }

    private fun setting(afterMs: Long = 0L, change: suspend () -> Unit) {
        scope.launch {
            change()
            // for a language switch, wait until the clip language has changed so "Okay" is in the new language
            if (afterMs > 0) delay(afterMs)
            ok()
        }
    }

    private fun ok() = say("sys_ok")

    private fun say(id: String) {
        player.play(listOf(id), interrupt = true, remember = false)
    }

    companion object {
        const val VOLUME_STEP = 0.2f
        const val LANGUAGE_SWITCH_MS = 300L
    }
}
