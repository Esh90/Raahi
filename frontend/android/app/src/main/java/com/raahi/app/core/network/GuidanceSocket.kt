package com.raahi.app.core.network

import android.util.Log
import com.raahi.app.core.model.ConnectionState
import com.raahi.app.core.model.ServerResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/**
 * The `/ws` connection to the Raahi server.
 *
 *  - binary message  = one JPEG camera frame, the server answers with exactly one `result` JSON per frame
 *  - text message    = hello / command / command_text (see [Protocol])
 *
 * Handles reconnection with back-off, and one-frame-in-flight flow control ("phone sends one JPEG,
 * waits for JSON" - PLAN.md). Frames are never queued: if the link is busy or down they are dropped.
 */
class GuidanceSocket(
    baseClient: OkHttpClient,
    private val scope: CoroutineScope,
    private val config: () -> ServerConfig?,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val client: OkHttpClient = baseClient.newBuilder()
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _results = MutableSharedFlow<ServerResult>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val results: SharedFlow<ServerResult> = _results.asSharedFlow()

    /** Last measured frame -> result time, ms. */
    private val _roundTripMs = MutableStateFlow(0)
    val roundTripMs: StateFlow<Int> = _roundTripMs.asStateFlow()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var wanted = false
    @Volatile private var generation = 0
    @Volatile private var endedGen = -1
    @Volatile private var everConnectedThisRun = false
    @Volatile private var frameInFlightSince = 0L
    @Volatile private var lastFrameSentAt = 0L
    @Volatile var malformedCount = 0
        private set
    private var loop: Job? = null
    private var attempt = 0

    val isOpen: Boolean get() = _state.value is ConnectionState.Connected

    /** Start (or keep) the connection. Safe to call repeatedly. */
    fun connect() {
        if (wanted) return
        wanted = true
        everConnectedThisRun = false
        attempt = 0
        loop = scope.launch { runLoop() }
    }

    /** Close and stop reconnecting. */
    fun disconnect() {
        wanted = false
        generation++
        loop?.cancel()
        loop = null
        ws?.close(1000, "bye")
        ws = null
        frameInFlightSince = 0L
        _state.value = ConnectionState.Idle
    }

    /** Close the current socket and reconnect right away. */
    fun reconnectNow() {
        if (!wanted) {
            connect()
            return
        }
        generation++
        ws?.cancel()
        ws = null
        loop?.cancel()
        attempt = 0
        loop = scope.launch { runLoop() }
    }

    private suspend fun runLoop() {
        while (wanted) {
            val cfg = config()
            if (cfg == null) {
                _state.value = ConnectionState.ServerUnavailable
                delay(2000)
                continue
            }
            val myGen = ++generation
            _state.value = ConnectionState.Connecting
            val request = try {
                Request.Builder().url(cfg.wsUrl).build()
            } catch (_: IllegalArgumentException) {
                _state.value = ConnectionState.ServerUnavailable
                delay(3000)
                continue
            }
            val socket = client.newWebSocket(request, listener(myGen))
            ws = socket
            // Wait until this generation ends (closed/failed) or we were told to stop.
            while (wanted && generation == myGen && endedGen != myGen) delay(100)
            // stopped, or reconnectNow() started a newer loop
            if (!wanted || generation != myGen) return
            // connection ended on its own: back-off, then loop
            val backoff = BACKOFF_SEC[minOf(attempt, BACKOFF_SEC.lastIndex)]
            attempt++
            var left = backoff
            while (left > 0 && wanted && generation == myGen) {
                _state.value = if (everConnectedThisRun) ConnectionState.Lost(left) else ConnectionState.ServerUnavailable
                delay(1000)
                left--
            }
        }
    }

    private fun listener(myGen: Int) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (myGen != generation) return
            everConnectedThisRun = true
            attempt = 0
            _state.value = ConnectionState.Connected(nowMs())
            webSocket.send(Protocol.hello())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (myGen != generation) return
            val result = try {
                RaahiJson.decodeFromString<ServerResult>(text)
            } catch (_: Exception) {
                malformedCount++
                Log.w(TAG, "ignored malformed server message")
                return
            }
            if (result.type != "result") return
            val sent = frameInFlightSince
            if (sent != 0L) {
                _roundTripMs.value = (nowMs() - sent).toInt().coerceAtLeast(0)
                frameInFlightSince = 0L
            }
            _results.tryEmit(result)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = ended(webSocket, myGen)
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "socket failure: ${t.javaClass.simpleName}")
            ended(webSocket, myGen)
        }
    }

    private fun ended(webSocket: WebSocket, myGen: Int) {
        if (myGen != generation) return
        if (ws === webSocket) ws = null
        frameInFlightSince = 0L
        endedGen = myGen // runLoop notices and handles back-off
    }

    /**
     * Send one JPEG. Returns false (frame dropped) when the socket is closed or the previous frame
     * has not been answered yet (timeout [IN_FLIGHT_TIMEOUT_MS] so a lost reply cannot stall guidance).
     */
    fun sendFrame(jpeg: ByteArray): Boolean {
        val socket = ws ?: return false
        if (_state.value !is ConnectionState.Connected) return false
        val now = nowMs()
        val busy = frameInFlightSince != 0L && now - frameInFlightSince < IN_FLIGHT_TIMEOUT_MS
        if (busy) return false
        frameInFlightSince = now
        lastFrameSentAt = now
        return socket.send(jpeg.toByteString())
    }

    /** True if [sendFrame] would accept a frame right now (cheap check before encoding a JPEG). */
    fun canAcceptFrame(): Boolean {
        if (ws == null || _state.value !is ConnectionState.Connected) return false
        val s = frameInFlightSince
        return s == 0L || nowMs() - s >= IN_FLIGHT_TIMEOUT_MS
    }

    fun sendText(json: String): Boolean {
        val socket = ws ?: return false
        if (_state.value !is ConnectionState.Connected) return false
        return socket.send(json)
    }

    companion object {
        private const val TAG = "GuidanceSocket"
        const val IN_FLIGHT_TIMEOUT_MS = 2500L
        val BACKOFF_SEC = intArrayOf(1, 2, 4, 5, 5, 8)
    }
}
