package com.raahi.app.core.voice

import android.util.Base64
import android.util.Log
import com.raahi.app.core.model.ScribeConfig
import com.raahi.app.core.network.RaahiJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * ElevenLabs Scribe v2 Realtime over WebSocket.
 * Protocol (elevenlabs.io/docs/api-reference/speech-to-text/v-1-speech-to-text-realtime):
 *   wss://api.elevenlabs.io/v1/speech-to-text/realtime?model_id=scribe_v2_realtime&token=<single-use>&...
 *   client -> {"message_type":"input_audio_chunk","audio_base_64":"..","commit":false,"sample_rate":16000}
 *   server -> session_started | partial_transcript | committed_transcript | error types with an "error" field
 * The permanent API key never leaves the server: the single-use [token] comes from `GET /scribe-token`.
 */
class ScribeClient(baseClient: OkHttpClient) {

    interface Listener {
        fun onOpen()
        fun onPartial(text: String)
        fun onCommitted(text: String)
        fun onError(kind: String)
        fun onClosed()
    }

    private val client = baseClient.newBuilder().readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build()
    @Volatile private var ws: WebSocket? = null
    @Volatile private var open = false

    val isOpen: Boolean get() = open

    fun connect(token: String, config: ScribeConfig, listener: Listener) {
        close()
        val url = buildUrl(token, config)
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                open = true
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val obj = parse(text) ?: return
                when (val type = obj["message_type"]?.jsonPrimitive?.contentOrNull) {
                    "partial_transcript" -> listener.onPartial(obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    "committed_transcript" -> listener.onCommitted(obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    null, "session_started", "warning" -> Unit
                    else -> if (obj.containsKey("error")) listener.onError(type)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                open = false
                listener.onClosed()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "scribe socket failure: ${t.javaClass.simpleName}")
                open = false
                listener.onError("connection")
                listener.onClosed()
            }
        })
    }

    private fun parse(text: String): JsonObject? = try {
        RaahiJson.parseToJsonElement(text).jsonObject
    } catch (_: Exception) {
        null
    }

    /** Send PCM16 mono 16 kHz audio. */
    fun sendAudio(pcm: ByteArray, length: Int, commit: Boolean = false): Boolean {
        val socket = ws ?: return false
        if (!open) return false
        val b64 = Base64.encodeToString(pcm, 0, length, Base64.NO_WRAP)
        val msg = buildJsonObject {
            put("message_type", "input_audio_chunk")
            put("audio_base_64", b64)
            put("commit", commit)
            put("sample_rate", SAMPLE_RATE)
        }.toString()
        return socket.send(msg)
    }

    fun close() {
        open = false
        ws?.close(1000, "done")
        ws = null
    }

    companion object {
        private const val TAG = "ScribeClient"
        const val SAMPLE_RATE = 16000
        private const val MAX_KEYTERMS = 20

        fun buildUrl(token: String, config: ScribeConfig): HttpUrl {
            val b = HttpUrl.Builder()
                .scheme("https")
                .host("api.elevenlabs.io")
                .addPathSegments("v1/speech-to-text/realtime")
                .addQueryParameter("model_id", config.modelId.ifBlank { "scribe_v2_realtime" })
                .addQueryParameter("token", token)
                .addQueryParameter("audio_format", "pcm_16000")
                .addQueryParameter("language_code", config.languageCode.ifBlank { "ur" })
                .addQueryParameter("commit_strategy", "vad")
                .addQueryParameter("vad_silence_threshold_secs", "0.6")
            config.keyterms.take(MAX_KEYTERMS).forEach { b.addQueryParameter("keyterms", it) }
            return b.build()
        }
    }
}
