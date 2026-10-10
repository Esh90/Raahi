package com.raahi.app

import com.raahi.app.core.model.ConnectionState
import com.raahi.app.core.model.ServerResult
import com.raahi.app.core.network.GuidanceSocket
import com.raahi.app.core.network.ServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Integration test of the real [GuidanceSocket] against a local WebSocket server that speaks the contract of
 * contracts/protocol.md (hello -> `result`, one `result` per binary frame). It does NOT replace testing against the
 * Python backend (see ANDROID_README.md "Verification").
 */
class GuidanceSocketTest {
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private val received = CopyOnWriteArrayList<String>()
    private val frames = CopyOnWriteArrayList<Int>()

    @Before
    fun setUp() {
        server = MockWebServer()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    received += text
                    if (text.contains("\"hello\"")) {
                        webSocket.send("""{"type":"result","clips":["sys_connected"],"urgent":false,"interrupt":false,"haptic":"none","mode":"navigate","overlay":[],"english":"Connected","latency_ms":{"detect":0,"depth":0,"engine":0,"total":1}}""")
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    frames += bytes.size
                    webSocket.send("not json at all") // malformed: must be ignored
                    webSocket.send("""{"type":"result","clips":["obj_chair","pos_ahead","steps_2","move_left_2"],"urgent":false,"interrupt":false,"haptic":"short","mode":"navigate","overlay":[{"label":"chair","box":[0.3,0.4,0.6,0.9],"dist_m":1.4}],"english":"Chair ahead","latency_ms":{"detect":10,"depth":20,"engine":1,"total":40}}""")
                }

                override fun onOpen(webSocket: WebSocket, response: Response) = Unit
            })
        )
        server.start()
    }

    @After
    fun tearDown() {
        scope.cancel()
        runCatching { server.shutdown() }
    }

    @Test
    fun `connects sends hello receives results and applies one-frame-in-flight flow control`() = runBlocking {
        val url = "http://${server.hostName}:${server.port}"
        val socket = GuidanceSocket(OkHttpClient(), scope, { ServerConfig.parse(url) })

        val results = CopyOnWriteArrayList<ServerResult>()
        val job = scope.launch { socket.results.collect { results += it } }

        socket.connect()
        withTimeout(5_000) { socket.state.first { it is ConnectionState.Connected } }
        withTimeout(5_000) { while (results.isEmpty()) kotlinx.coroutines.delay(20) }

        // hello went out first and was answered with sys_connected
        assertTrue(received.first().contains("\"type\":\"hello\""))
        assertTrue(received.first().contains("\"client\":\"android\""))
        assertEquals(listOf("sys_connected"), results[0].clips)

        // first frame is accepted, the second is dropped until the server has answered
        val jpeg = ByteArray(1234) { 7 }
        assertTrue(socket.sendFrame(jpeg))
        assertFalse(socket.sendFrame(jpeg))

        withTimeout(5_000) { while (results.size < 2) kotlinx.coroutines.delay(20) }
        assertEquals(listOf(1234), frames.toList())
        assertEquals("chair", results[1].overlay[0].label)
        assertEquals("short", results[1].haptic)
        assertEquals(1, socket.malformedCount) // "not json at all" was ignored, not crashed on

        // after the reply a new frame is accepted again
        assertTrue(socket.sendFrame(jpeg))

        // commands go out as text
        assertTrue(socket.sendText(com.raahi.app.core.network.Protocol.command("pause")))
        withTimeout(5_000) { while (received.none { it.contains("\"pause\"") }) kotlinx.coroutines.delay(20) }

        socket.disconnect()
        assertEquals(ConnectionState.Idle, socket.state.value)
        assertFalse(socket.sendFrame(jpeg))
        job.cancel()
        Unit
    }

    @Test
    fun `reports server unavailable when nothing listens`() = runBlocking {
        val port = server.port
        server.shutdown()
        val socket = GuidanceSocket(OkHttpClient(), scope, { ServerConfig.parse("http://127.0.0.1:$port") })
        socket.connect()
        withTimeout(10_000) { socket.state.first { it == ConnectionState.ServerUnavailable } }
        assertFalse(socket.sendFrame(ByteArray(10)))
        socket.disconnect()
        Unit
    }
}
