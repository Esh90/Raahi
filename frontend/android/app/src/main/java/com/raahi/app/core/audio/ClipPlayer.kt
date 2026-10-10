package com.raahi.app.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Why audio output is considered broken (drives the "No sound" error screen). */
enum class AudioProblem { CLIPS_UNAVAILABLE, PLAYBACK_FAILED }

/**
 * Plays the server's prerecorded clips one after another.
 *
 *  - `interrupt = true` stops what is playing and drops everything queued (urgent / stop messages)
 *  - identical consecutive requests are de-duplicated so a repeated announcement cannot overlap itself
 *  - a sentence that waited longer than [STALE_MS] before starting is discarded: advice about where an obstacle
 *    *was* is dangerous (checked on its first clip, so a sentence is never cut in the middle)
 *  - a new normal sentence replaces normal sentences still waiting (only the newest guidance matters);
 *    the sentence already playing finishes first, urgent ones are never replaced
 *  - respects audio focus (transient, ducking others); losing focus stops playback
 */
class ClipPlayer(
    context: Context,
    private val repo: ClipRepository,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private class Item(val id: String, val enqueuedAt: Long, val urgent: Boolean, val seq: Long)

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val lock = Any()
    private val queue = ArrayDeque<Item>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var worker: Job? = null

    @Volatile private var currentDone: CompletableDeferred<Unit>? = null
    @Volatile private var volume = 0.8f
    @Volatile private var speed = 1.0f
    @Volatile private var enabled = true
    private var focusRequest: AudioFocusRequest? = null
    private var failures = 0
    private var seqCounter = 0L
    @Volatile private var playingSeq = -1L
    private var lastQueuedSignature: String = ""
    private var lastQueuedAt = 0L

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _problem = MutableStateFlow<AudioProblem?>(null)
    val problem: StateFlow<AudioProblem?> = _problem.asStateFlow()

    /** The most recent spoken clip sequence (for the "Repeat" button / double tap). */
    @Volatile var lastSpoken: List<String> = emptyList()
        private set

    /** Last time anything finished playing; used as an echo guard for the microphone. */
    @Volatile var lastPlayedEndAt: Long = 0L
        private set

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            stopAll()
        }
    }

    fun setVolume(v: Float) { volume = v.coerceIn(0f, 1f) }
    fun setSpeed(s: Float) { speed = s.coerceIn(0.5f, 2f) }

    /** When false nothing is played (user switched Urdu voice guidance off). */
    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) stopAll()
    }

    fun clearProblem() { _problem.value = null }

    private fun ensureWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch(Dispatchers.Default) { runWorker() }
    }

    /**
     * Queue [ids] for playback. Returns false when nothing was queued (disabled / duplicate).
     */
    fun play(ids: List<String>, interrupt: Boolean = false, urgent: Boolean = false, remember: Boolean = true): Boolean {
        if (ids.isEmpty() || !enabled) return false
        val now = nowMs()
        val signature = ids.joinToString("|")
        synchronized(lock) {
            // identical request within 1.2 s while it is still queued/playing = duplicate
            if (!interrupt && signature == lastQueuedSignature && now - lastQueuedAt < DUPLICATE_MS) return false
            lastQueuedSignature = signature
            lastQueuedAt = now
            val seq = ++seqCounter
            if (interrupt) {
                queue.clear()
                currentDone?.complete(Unit)
            } else {
                // newest guidance wins: drop normal sentences that haven't started (keep the playing one)
                queue.removeAll { !it.urgent && it.seq != playingSeq }
            }
            ids.forEach { queue.addLast(Item(it, now, urgent || interrupt, seq)) }
        }
        if (remember) lastSpoken = ids.filterNot { it.startsWith("ear_") }
        ensureWorker()
        wake.trySend(Unit)
        return true
    }

    fun stopAll() {
        synchronized(lock) { queue.clear() }
        currentDone?.complete(Unit)
    }

    fun release() {
        stopAll()
        worker?.cancel()
        worker = null
        abandonFocus()
    }

    private suspend fun runWorker() {
        while (true) {
            val item = synchronized(lock) { queue.removeFirstOrNull() }
            if (item == null) {
                if (_isPlaying.value) lastPlayedEndAt = nowMs()
                _isPlaying.value = false
                abandonFocus()
                wake.receive()
                continue
            }
            if (item.seq != playingSeq) {
                // first clip of a new sentence: drop the whole sentence if it waited too long
                if (!item.urgent && nowMs() - item.enqueuedAt > STALE_MS) {
                    synchronized(lock) { queue.removeAll { it.seq == item.seq } }
                    continue
                }
                playingSeq = item.seq
            }
            playOne(item)
        }
    }

    private suspend fun playOne(item: Item) {
        val file = repo.ensureFile(item.id)
        if (file == null || !file.exists()) {
            noteFailure(AudioProblem.CLIPS_UNAVAILABLE)
            return
        }
        if (!requestFocus()) {
            noteFailure(AudioProblem.PLAYBACK_FAILED)
            return
        }
        val done = CompletableDeferred<Unit>()
        currentDone = done
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(attrs)
            mp.setDataSource(file.absolutePath)
            mp.setVolume(volume, volume)
            mp.setOnCompletionListener { done.complete(Unit) }
            mp.setOnErrorListener { _, _, _ ->
                failures++
                done.complete(Unit)
                true
            }
            withContext(Dispatchers.IO) { mp.prepare() }
            if (done.isCompleted) return // interrupted while preparing
            mp.start()
            if (speed != 1.0f) {
                try { mp.playbackParams = mp.playbackParams.setSpeed(speed) } catch (_: Exception) { }
            }
            _isPlaying.value = true
            withTimeoutOrNull(MAX_CLIP_MS) { done.await() }
            failures = 0
            _problem.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "playback failed for ${item.id}: ${e.javaClass.simpleName}")
            noteFailure(AudioProblem.PLAYBACK_FAILED)
        } finally {
            currentDone = null
            try { mp.stop() } catch (_: Exception) { }
            mp.release()
        }
    }

    private fun noteFailure(p: AudioProblem) {
        failures++
        if (failures >= FAILURES_BEFORE_PROBLEM) _problem.value = p
    }

    private fun requestFocus(): Boolean {
        if (focusRequest != null) return true
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        val ok = audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (ok) focusRequest = req
        return ok
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    companion object {
        private const val TAG = "ClipPlayer"
        const val STALE_MS = 2000L
        const val DUPLICATE_MS = 1200L
        const val MAX_CLIP_MS = 25_000L
        const val FAILURES_BEFORE_PROBLEM = 3
    }
}
