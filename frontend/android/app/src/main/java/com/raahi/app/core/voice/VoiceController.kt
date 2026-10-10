package com.raahi.app.core.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.raahi.app.core.audio.ClipPlayer
import com.raahi.app.core.model.ScribeConfig
import com.raahi.app.core.network.Protocol
import com.raahi.app.core.network.RaahiApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface VoiceState {
    data object Idle : VoiceState
    data object Connecting : VoiceState
    data class Listening(val partial: String = "") : VoiceState

    /** A full sentence was recognised and is waiting for Confirm (or was just auto-sent). */
    data class Heard(val text: String, val sent: Boolean) : VoiceState
    data class Failed(val reason: VoiceFailure) : VoiceState
}

enum class VoiceFailure {
    PERMISSION_DENIED, /* microphone permission missing */
    OFFLINE,           /* token / Scribe unreachable: use buttons and gestures */
    MIC_UNAVAILABLE,   /* another app holds the microphone */
    NO_SPEECH,         /* nothing recognised before the timeout */
    NOT_CONNECTED,     /* guidance server socket is down so the command cannot be delivered */
}

/**
 * Push-to-talk voice commands: the user triggers it (button / long-press), we stream the microphone to
 * ElevenLabs Scribe v2 Realtime, and the recognised sentence is handled ON THE PHONE by [commandHandler]
 * (core/voice/VoiceCommands + CommandDispatcher: navigation, settings, guidance control) - no wake word
 * needed here. Without a handler it falls back to the server (`command_text`, server/voice/intents.py).
 *
 * The always-on "Raahi, ..." mode is [HandsFreeListener] (opt-out in Settings > Voice & audio).
 */
class VoiceController(
    private val context: Context,
    private val api: RaahiApi,
    private val scribe: ScribeClient,
    private val mic: MicStreamer,
    private val player: ClipPlayer,
    private val scope: CoroutineScope,
    /** delivers a raw JSON text message over the guidance socket */
    private val sendText: (String) -> Boolean,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    /** "ur" | "en": transcription language (follows the app language) */
    private val languageCode: () -> String = { "ur" },
) {
    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    /**
     * Optional override for delivering a recognised sentence. The standalone Voice screen uses it to start a guidance
     * session first and send the command afterwards (the server resets its mode when a session begins).
     */
    @Volatile var sentenceHandler: ((String) -> Boolean)? = null

    /** Default handler for recognised sentences: on-device voice commands (set by AppContainer). */
    @Volatile var commandHandler: ((String) -> Boolean)? = null

    private var job: Job? = null
    private var timeout: Job? = null
    private var autoSend = false
    @Volatile private var active = false

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Begin one push-to-talk utterance. [autoSend] sends the sentence as soon as it is recognised. */
    fun startListening(autoSend: Boolean) {
        if (active) return
        if (!hasMicPermission()) {
            _state.value = VoiceState.Failed(VoiceFailure.PERMISSION_DENIED)
            return
        }
        this.autoSend = autoSend
        active = true
        _state.value = VoiceState.Connecting
        job = scope.launch {
            player.stopAll()
            player.play(listOf("ear_listen"), interrupt = true, remember = false)

            val cfg = try { api.scribeConfig() } catch (_: Exception) { ScribeConfig() }
            val token = try {
                api.scribeToken()
            } catch (_: Exception) {
                fail(VoiceFailure.OFFLINE)
                player.play(listOf("sys_offline"), interrupt = true, remember = false)
                return@launch
            }
            // let the earcon finish so the microphone does not transcribe it
            var waited = 0
            while (player.isPlaying.value && waited < 1200) { delay(50); waited += 50 }
            delay(150)

            scribe.connect(token, cfg.copy(languageCode = languageCode()), object : ScribeClient.Listener {
                override fun onOpen() {
                    val ok = mic.start(
                        onChunk = { buf, n -> sendChunk(buf, n) },
                        onLevel = { _level.value = it },
                    )
                    if (!ok) {
                        fail(VoiceFailure.MIC_UNAVAILABLE)
                        return
                    }
                    _state.value = VoiceState.Listening()
                    startTimeout()
                }

                override fun onPartial(text: String) {
                    if (active && text.isNotBlank()) _state.value = VoiceState.Listening(text.trim())
                }

                override fun onCommitted(text: String) {
                    val t = text.trim()
                    if (!active || t.isEmpty()) return
                    finishCapture()
                    if (this@VoiceController.autoSend) {
                        deliver(t)
                    } else {
                        _state.value = VoiceState.Heard(t, sent = false)
                    }
                }

                override fun onError(kind: String) {
                    if (active) fail(VoiceFailure.OFFLINE)
                }

                override fun onClosed() {
                    if (active && _state.value is VoiceState.Listening) fail(VoiceFailure.OFFLINE)
                }
            })
        }
    }

    private fun sendChunk(buf: ByteArray, n: Int) {
        // echo guard: never feed Raahi's own voice back into the recogniser
        if (player.isPlaying.value || nowMs() - player.lastPlayedEndAt < ECHO_TAIL_MS) return
        scribe.sendAudio(buf, n)
    }

    private fun startTimeout() {
        timeout?.cancel()
        timeout = scope.launch {
            delay(LISTEN_TIMEOUT_MS)
            if (active && _state.value is VoiceState.Listening) {
                fail(VoiceFailure.NO_SPEECH)
                player.play(listOf("sys_not_understood"), interrupt = true, remember = false)
            }
        }
    }

    private fun finishCapture() {
        timeout?.cancel()
        mic.stop()
        scribe.close()
        _level.value = 0f
    }

    private fun fail(reason: VoiceFailure) {
        finishCapture()
        active = false
        _state.value = VoiceState.Failed(reason)
    }

    /** Send the recognised sentence to the server. */
    private fun deliver(text: String) {
        val handler = sentenceHandler ?: commandHandler
        val ok = if (handler != null) handler(text) else sendText(Protocol.commandText(text, wakeRequired = false))
        active = false
        _state.value = if (ok) VoiceState.Heard(text, sent = true) else VoiceState.Failed(VoiceFailure.NOT_CONNECTED)
    }

    /** User pressed Confirm on the Voice Command screen. */
    fun confirm() {
        val s = _state.value
        if (s is VoiceState.Heard && !s.sent) deliver(s.text)
    }

    /** Cancel listening or discard a recognised-but-unsent sentence. */
    fun cancel() {
        job?.cancel()
        finishCapture()
        active = false
        _state.value = VoiceState.Idle
    }

    fun reset() {
        if (!active) _state.value = VoiceState.Idle
    }

    companion object {
        const val LISTEN_TIMEOUT_MS = 10_000L
        const val ECHO_TAIL_MS = 600L
    }
}
