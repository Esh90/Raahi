package com.raahi.app.core.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.raahi.app.core.audio.ClipPlayer
import com.raahi.app.core.model.ScribeConfig
import com.raahi.app.core.network.RaahiApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class HandsFreeState { OFF, CONNECTING, LISTENING, ARMED, UNAVAILABLE }

/**
 * Hands-free voice control: "Raahi, <command>" in Urdu or English, any time the app is open.
 *
 * While enabled (Settings > Voice & audio > Hands-free) and the app is in the foreground, the microphone is
 * streamed to ElevenLabs Scribe v2 Realtime (single-use token from our server, the API key stays on the
 * server). Every transcript is matched ON THE PHONE by [VoiceCommands] and executed by [CommandDispatcher]:
 *  - low latency: a partial transcript that already contains "Raahi + command" and has been stable for
 *    [PARTIAL_STABLE_MS] is acted on without waiting for the end-of-speech pause; the committed transcript
 *    of the same utterance is then ignored (or executed if it turned out to be a different command)
 *  - "Raahi" alone arms the listener for [ARMED_MS]: the next sentence needs no wake word
 *  - echo guard: audio is not sent while Raahi itself is speaking (+[ECHO_TAIL_MS])
 *  - sentences without the wake word are ignored (conversation around the user never triggers anything)
 * Push-to-talk ([VoiceController]) keeps working; [AppContainer] suspends this listener while it runs.
 */
class HandsFreeListener(
    private val context: Context,
    private val api: RaahiApi,
    private val scribe: ScribeClient,
    private val mic: MicStreamer,
    private val player: ClipPlayer,
    private val commands: () -> VoiceCommands?,
    private val dispatcher: CommandDispatcher,
    private val scope: CoroutineScope,
    /** "ur" | "en": Scribe transcribes in this language (English words in Urdu mode come out in Urdu script) */
    private val languageCode: () -> String,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val _state = MutableStateFlow(HandsFreeState.OFF)
    val state: StateFlow<HandsFreeState> = _state.asStateFlow()

    /** Last sentence that was matched (for the Voice screen / diagnostics). */
    private val _lastHeard = MutableStateFlow("")
    val lastHeard: StateFlow<String> = _lastHeard.asStateFlow()

    @Volatile private var running = false
    @Volatile private var armedUntil = 0L
    @Volatile private var latestPartial = ""
    @Volatile private var handledInUtterance: String? = null
    private var connectJob: Job? = null
    private var partialJob: Job? = null
    private var attempt = 0

    private fun hasMic() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (running) return
        if (!hasMic()) {
            _state.value = HandsFreeState.OFF
            return
        }
        running = true
        attempt = 0
        connect()
    }

    fun stop() {
        running = false
        connectJob?.cancel()
        partialJob?.cancel()
        mic.stop()
        scribe.close()
        armedUntil = 0L
        _state.value = HandsFreeState.OFF
    }

    /** Reconnect (e.g. after the language changed so Scribe uses the new language code). */
    fun restart() {
        if (!running) return
        stop()
        start()
    }

    private fun connect() {
        connectJob?.cancel()
        _state.value = HandsFreeState.CONNECTING
        connectJob = scope.launch {
            val cfg = try { api.scribeConfig() } catch (_: Exception) { ScribeConfig() }
            val token = try {
                api.scribeToken()
            } catch (e: Exception) {
                Log.w(TAG, "no scribe token: ${e.message}")
                retryLater()
                return@launch
            }
            if (!running) return@launch
            val config = cfg.copy(
                languageCode = languageCode(),
                keyterms = (WAKE_TERMS + cfg.keyterms).distinct(),
            )
            scribe.connect(token, config, listener)
        }
    }

    private fun retryLater() {
        if (!running) return
        _state.value = HandsFreeState.UNAVAILABLE
        val wait = RETRY_MS[minOf(attempt, RETRY_MS.size - 1)]
        attempt++
        connectJob = scope.launch {
            delay(wait)
            if (running) connect()
        }
    }

    private val listener = object : ScribeClient.Listener {
        override fun onOpen() {
            val ok = mic.start(onChunk = { buf, n -> sendChunk(buf, n) })
            if (!ok) {
                scribe.close()
                retryLater()
                return
            }
            attempt = 0
            _state.value = HandsFreeState.LISTENING
        }

        override fun onPartial(text: String) {
            val t = text.trim()
            if (t.isEmpty()) return
            latestPartial = t
            partialJob?.cancel()
            partialJob = scope.launch {
                delay(PARTIAL_STABLE_MS)
                if (latestPartial == t) handle(t, fromPartial = true)
            }
        }

        override fun onCommitted(text: String) {
            partialJob?.cancel()
            val t = text.trim()
            if (t.isNotEmpty()) scope.launch { handle(t, fromPartial = false) }
            latestPartial = ""
        }

        override fun onError(kind: String) {
            Log.w(TAG, "scribe error: $kind")
        }

        override fun onClosed() {
            mic.stop()
            if (running) retryLater()
        }
    }

    private fun sendChunk(buf: ByteArray, n: Int) {
        if (player.isPlaying.value || nowMs() - player.lastPlayedEndAt < ECHO_TAIL_MS) return
        scribe.sendAudio(buf, n)
    }

    private fun handle(text: String, fromPartial: Boolean) {
        val grammar = commands() ?: return
        val armed = nowMs() < armedUntil
        val id = grammar.match(text, requireWake = !armed) ?: run {
            if (!fromPartial) handledInUtterance = null
            return
        }
        if (fromPartial) {
            // only act early on a clear command; "Raahi" alone / unknown waits for the full sentence
            if (id == VoiceCommands.UNKNOWN || id == VoiceCommands.WAKE_ONLY || id == handledInUtterance) return
        } else {
            val already = handledInUtterance
            handledInUtterance = null
            if (already == id) return // executed from the partial transcript already
        }
        _lastHeard.value = text
        if (id == VoiceCommands.WAKE_ONLY) {
            armedUntil = nowMs() + ARMED_MS
            _state.value = HandsFreeState.ARMED
            player.play(listOf("ear_listen"), interrupt = true, remember = false)
            scope.launch {
                delay(ARMED_MS)
                if (running && nowMs() >= armedUntil && _state.value == HandsFreeState.ARMED) {
                    _state.value = HandsFreeState.LISTENING
                }
            }
            return
        }
        armedUntil = 0L
        if (running) _state.value = HandsFreeState.LISTENING
        if (fromPartial) handledInUtterance = id
        dispatcher.execute(id)
    }

    companion object {
        private const val TAG = "HandsFree"
        const val PARTIAL_STABLE_MS = 350L
        const val ARMED_MS = 6000L
        const val ECHO_TAIL_MS = 600L
        private val RETRY_MS = longArrayOf(1000, 2000, 5000, 10000)
        private val WAKE_TERMS = listOf("Raahi", "راہی")
    }
}
