package com.raahi.app.domain

import com.raahi.app.core.audio.ClipPlayer
import com.raahi.app.core.camera.CameraController
import com.raahi.app.core.camera.EncodedFrame
import com.raahi.app.core.camera.UprightMonitor
import com.raahi.app.core.haptics.Haptics
import com.raahi.app.core.model.ClipManifest
import com.raahi.app.core.model.ConnectionState
import com.raahi.app.core.model.FindTarget
import com.raahi.app.core.model.HapticKind
import com.raahi.app.core.model.OverlayBox
import com.raahi.app.core.model.ServerMode
import com.raahi.app.core.model.ServerResult
import com.raahi.app.core.network.GuidanceSocket
import com.raahi.app.core.network.Protocol
import com.raahi.app.data.HistoryRepository
import com.raahi.app.data.SessionRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class SessionPhase { IDLE, ACTIVE, PAUSED }

/** GUIDANCE = free navigation, FIND = looking for a target, SCAN = one-off "What's ahead?" (not saved to history). */
enum class SessionKind { GUIDANCE, FIND, SCAN }

data class SessionUi(
    val phase: SessionPhase = SessionPhase.IDLE,
    val kind: SessionKind = SessionKind.GUIDANCE,
    val target: FindTarget? = null,
    val startedAt: Long = 0L,
    val pausedAt: Long = 0L,
    val serverMode: String = ServerMode.NAVIGATE,
    val instruction: Instruction? = null,
    val subtitle: String = "",
    val overlay: List<OverlayBox> = emptyList(),
    val frameWidth: Int = 416,
    val frameHeight: Int = 554,
    val danger: Boolean = false,
    val upright: Boolean = true,
    val foundBanner: String? = null,
    val serverTotalMs: Int = 0,
    val serverDetectMs: Int = 0,
    val serverDepthMs: Int = 0,
    val fps: Int = 0,
    val framesSent: Long = 0L,
)

sealed interface SessionEvent {
    /** The find target has been lost twice in a row: show the "not found" screen. */
    data class FindNotFound(val target: FindTarget?) : SessionEvent
    data class Found(val target: FindTarget?) : SessionEvent
}

/**
 * The single owner of a guidance session: wires the camera frames to the socket, the socket's `result`
 * messages to audio / haptics / UI, and records the session for History. It never invents data: every field of
 * [SessionUi] comes from a real [ServerResult] or from the device sensors.
 */
class GuidanceSession(
    private val scope: CoroutineScope,
    private val socket: GuidanceSocket,
    private val player: ClipPlayer,
    private val haptics: Haptics,
    private val camera: CameraController,
    private val upright: UprightMonitor,
    private val history: HistoryRepository,
    private val manifest: () -> ClipManifest,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val _ui = MutableStateFlow(SessionUi())
    val ui: StateFlow<SessionUi> = _ui.asStateFlow()

    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    private val _lastFinished = MutableStateFlow<SessionRecord?>(null)
    val lastFinished: StateFlow<SessionRecord?> = _lastFinished.asStateFlow()

    private var recorder: SessionRecorder? = null
    private var lastPhaseChange = 0L
    private var lastUrgentAt = 0L
    private var dangerDismissed = false
    private var lostCount = 0
    private var foundBannerAt = 0L
    private var lastUprightPromptAt = 0L
    private val resultTimes = ArrayDeque<Long>()

    val connection: StateFlow<ConnectionState> get() = socket.state
    val roundTripMs: StateFlow<Int> get() = socket.roundTripMs

    init {
        camera.shouldCapture = { wantsFrame() }
        camera.onFrame = { frame -> onFrame(frame) }

        scope.launch { socket.results.collect { onResult(it) } }
        scope.launch { upright.upright.collect { u -> _ui.update { it.copy(upright = u) } } }
        scope.launch {
            var prev: ConnectionState = ConnectionState.Idle
            socket.state.collect { s ->
                if (_ui.value.phase != SessionPhase.IDLE && prev is ConnectionState.Connected && s !is ConnectionState.Connected) {
                    player.play(listOf("sys_connection_lost"), interrupt = true, remember = false)
                }
                prev = s
            }
        }
        scope.launch {
            while (true) {
                delay(TICK_MS)
                if (_ui.value.phase != SessionPhase.IDLE) tick()
            }
        }
    }

    val isActive: Boolean get() = _ui.value.phase != SessionPhase.IDLE

    private fun wantsFrame(): Boolean {
        val s = _ui.value
        return s.phase == SessionPhase.ACTIVE && s.upright && socket.canAcceptFrame()
    }

    private fun onFrame(f: EncodedFrame) {
        if (socket.sendFrame(f.jpeg)) {
            _ui.update {
                if (it.frameWidth == f.width && it.frameHeight == f.height) it.copy(framesSent = it.framesSent + 1)
                else it.copy(frameWidth = f.width, frameHeight = f.height, framesSent = it.framesSent + 1)
            }
        }
    }

    /** Waits (up to [timeoutMs]) for the socket; false => the caller shows "Server unavailable". */
    suspend fun awaitConnected(timeoutMs: Long = 4000): Boolean {
        socket.connect()
        if (socket.state.value is ConnectionState.Connected) return true
        return withTimeoutOrNull(timeoutMs) { socket.state.first { it is ConnectionState.Connected } } != null
    }

    /** Start a session. Returns false when the server cannot be reached. */
    suspend fun begin(kind: SessionKind, target: FindTarget? = null): Boolean {
        if (!awaitConnected()) return false
        if (_ui.value.phase != SessionPhase.IDLE) end()
        val now = nowMs()
        recorder = when (kind) {
            SessionKind.GUIDANCE -> SessionRecorder("guidance", null, now, manifest)
            SessionKind.FIND -> SessionRecorder("find", target, now, manifest)
            SessionKind.SCAN -> null
        }
        lostCount = 0
        dangerDismissed = false
        lastPhaseChange = now
        _ui.value = SessionUi(phase = SessionPhase.ACTIVE, kind = kind, target = target, startedAt = now, upright = upright.upright.value)
        upright.start()
        // The server engine is shared and keeps its mode between sessions: normalise it (mode=navigate, find off).
        socket.sendText(Protocol.command("stop"))
        if (kind == SessionKind.FIND && target != null) socket.sendText(Protocol.command("find", target.wire))
        return true
    }

    /** End the session; returns the saved record (null for SCAN sessions). */
    fun end(): SessionRecord? {
        if (_ui.value.phase == SessionPhase.IDLE) return null
        socket.sendText(Protocol.command("stop"))
        player.stopAll()
        haptics.cancel()
        upright.stop()
        val rec = recorder?.finish(nowMs())
        recorder = null
        _ui.value = SessionUi()
        if (rec != null) {
            _lastFinished.value = rec
            scope.launch { history.add(rec) }
        }
        return rec
    }

    fun pause() {
        if (_ui.value.phase != SessionPhase.ACTIVE) return
        lastPhaseChange = nowMs()
        _ui.update { it.copy(phase = SessionPhase.PAUSED, pausedAt = lastPhaseChange, danger = false) }
        haptics.cancel()
        socket.sendText(Protocol.command("pause"))
    }

    fun resume() {
        if (_ui.value.phase != SessionPhase.PAUSED) return
        lastPhaseChange = nowMs()
        _ui.update { it.copy(phase = SessionPhase.ACTIVE, pausedAt = 0L) }
        socket.sendText(Protocol.command("resume"))
    }

    fun togglePause() = if (_ui.value.phase == SessionPhase.PAUSED) resume() else pause()

    /** Replay the last spoken instruction locally (the server has no "repeat" handler). */
    fun repeatLast(): Boolean = player.play(player.lastSpoken, interrupt = true, remember = false)

    fun requestFind(t: FindTarget): Boolean {
        lostCount = 0
        _ui.update { it.copy(target = t, kind = if (it.kind == SessionKind.SCAN) it.kind else SessionKind.FIND) }
        return socket.sendText(Protocol.command("find", t.wire))
    }

    /** Free-text command (push-to-talk sentence); the server parses it with server/voice/intents.py. */
    fun sendCommandText(text: String): Boolean = socket.sendText(Protocol.commandText(text, wakeRequired = false))

    fun requestDescribe(): Boolean = socket.sendText(Protocol.command("describe"))

    fun stopFinding(): Boolean = socket.sendText(Protocol.command("stop"))

    fun dismissDanger() {
        dangerDismissed = true
        _ui.update { it.copy(danger = false) }
    }

    fun clearFoundBanner() = _ui.update { it.copy(foundBanner = null) }

    private fun onResult(r: ServerResult) {
        val now = nowMs()
        resultTimes.addLast(now)
        while (resultTimes.isNotEmpty() && now - resultTimes.first() > 2000) resultTimes.removeFirst()

        val st = _ui.value
        if (st.phase == SessionPhase.IDLE) return // connection chatter outside a session is never spoken

        // keep the local phase in sync with the server's mode (ignore stale results right after a local change)
        var phase = st.phase
        if (now - lastPhaseChange > PHASE_GUARD_MS) {
            if (r.mode == ServerMode.PAUSED && phase == SessionPhase.ACTIVE) { phase = SessionPhase.PAUSED; lastPhaseChange = now }
            else if (r.mode != ServerMode.PAUSED && phase == SessionPhase.PAUSED) { phase = SessionPhase.ACTIVE; lastPhaseChange = now }
        }

        if (r.clips.isNotEmpty()) player.play(r.clips, interrupt = r.interrupt, urgent = r.urgent)
        if (r.haptic != HapticKind.NONE) haptics.vibrate(r.haptic, now)

        val stop = InstructionParser.isStopAlert(r) || (r.urgent && r.haptic == HapticKind.LONG)
        if (stop || r.urgent) lastUrgentAt = now
        val danger = stop && !dangerDismissed && phase == SessionPhase.ACTIVE

        val ins = InstructionParser.parse(r, manifest(), now)
        var banner = st.foundBanner
        if (InstructionParser.isArrived(r)) {
            lostCount = 0
            banner = "${st.target?.label ?: "Target"} found"
            foundBannerAt = now
            _events.tryEmit(SessionEvent.Found(st.target))
        }
        if (InstructionParser.isFindLost(r) && st.kind == SessionKind.FIND) {
            lostCount++
            if (lostCount >= 2) {
                lostCount = 0
                _events.tryEmit(SessionEvent.FindNotFound(st.target))
            }
        }

        recorder?.onResult(r, now)

        _ui.update {
            it.copy(
                phase = phase,
                serverMode = r.mode,
                instruction = ins ?: it.instruction,
                subtitle = if (r.english.isNotBlank() && r.clips.isNotEmpty()) r.english else it.subtitle,
                overlay = r.overlay,
                danger = if (danger) true else it.danger && !dangerDismissed,
                foundBanner = banner,
                serverTotalMs = r.latency.total,
                serverDetectMs = r.latency.detect,
                serverDepthMs = r.latency.depth,
                fps = resultTimes.size / 2,
            )
        }
    }

    private fun tick() {
        val now = nowMs()
        if (dangerDismissed && now - lastUrgentAt > EPISODE_MS) dangerDismissed = false
        if (foundBannerAt != 0L && now - foundBannerAt > BANNER_MS) foundBannerAt = 0L
        _ui.update { cur ->
            var s = cur
            if (s.danger && now - lastUrgentAt > DANGER_CLEAR_MS) s = s.copy(danger = false)
            val ins = s.instruction
            if (ins != null && now - ins.receivedAt > STALE_INSTRUCTION_MS) s = s.copy(instruction = null, subtitle = "")
            if (s.foundBanner != null && foundBannerAt == 0L) s = s.copy(foundBanner = null)
            s.copy(fps = resultTimes.count { now - it <= 2000 } / 2)
        }
        val s = _ui.value
        if (s.phase == SessionPhase.ACTIVE && !s.upright && now - lastUprightPromptAt > UPRIGHT_PROMPT_MS) {
            lastUprightPromptAt = now
            player.play(listOf("phone_upright"), remember = false)
        }
    }

    companion object {
        const val TICK_MS = 500L
        const val PHASE_GUARD_MS = 1500L
        const val DANGER_CLEAR_MS = 4000L
        const val EPISODE_MS = 6000L
        const val STALE_INSTRUCTION_MS = 8000L
        const val BANNER_MS = 4000L
        const val UPRIGHT_PROMPT_MS = 8000L
    }
}
