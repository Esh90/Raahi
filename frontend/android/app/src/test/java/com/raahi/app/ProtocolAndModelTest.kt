package com.raahi.app

import com.raahi.app.core.model.ClipManifest
import com.raahi.app.core.model.ScribeConfig
import com.raahi.app.core.model.ServerResult
import com.raahi.app.core.network.Protocol
import com.raahi.app.core.network.RaahiJson
import com.raahi.app.core.network.ServerConfig
import com.raahi.app.core.voice.ScribeClient
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolAndModelTest {

    @Test
    fun `server result parses the exact sample from contracts-protocol 5_2`() {
        val json = """
        {
          "type": "result",
          "clips": ["obj_chair", "pos_ahead", "steps_2", "move_left_2"],
          "urgent": false,
          "interrupt": false,
          "haptic": "none",
          "mode": "navigate",
          "overlay": [{"label":"chair","box":[0.31,0.40,0.62,0.95],"dist_m":1.4}],
          "english": "Chair ahead, 2 steps, move 2 left",
          "latency_ms": {"detect":18,"depth":44,"engine":2,"total":71}
        }
        """.trimIndent()
        val r = RaahiJson.decodeFromString<ServerResult>(json)
        assertEquals(listOf("obj_chair", "pos_ahead", "steps_2", "move_left_2"), r.clips)
        assertEquals("navigate", r.mode)
        assertEquals("chair", r.overlay[0].label)
        assertEquals(1.4f, r.overlay[0].distM!!, 0.0001f)
        assertEquals(4, r.overlay[0].box.size)
        assertEquals(71, r.latency.total)
        assertEquals("Chair ahead, 2 steps, move 2 left", r.english)
    }

    @Test
    fun `server result tolerates extra overlay keys and missing fields`() {
        val r = RaahiJson.decodeFromString<ServerResult>(
            """{"type":"result","clips":[],"overlay":[{"box":[0,0,1,1],"dist_m":0.5,"zone":"centre","label":"obstacle"}]}"""
        )
        assertTrue(r.clips.isEmpty())
        assertEquals("none", r.haptic)
        assertEquals("obstacle", r.overlay[0].label)
        assertFalse(r.urgent)
    }

    @Test
    fun `hello message matches the contract`() {
        val o = RaahiJson.parseToJsonElement(Protocol.hello()).jsonObject
        assertEquals("hello", o["type"]!!.jsonPrimitive.content)
        assertEquals("android", o["client"]!!.jsonPrimitive.content)
        assertEquals("0.1", o["version"]!!.jsonPrimitive.content)
    }

    @Test
    fun `command messages carry intent and optional target`() {
        val find = RaahiJson.parseToJsonElement(Protocol.command("find", "door")).jsonObject
        assertEquals("command", find["type"]!!.jsonPrimitive.content)
        assertEquals("find", find["intent"]!!.jsonPrimitive.content)
        assertEquals("door", find["target"]!!.jsonPrimitive.content)

        val pause = RaahiJson.parseToJsonElement(Protocol.command("pause")).jsonObject
        assertNull(pause["target"])
    }

    @Test
    fun `command_text is push-to-talk so wake word is not required`() {
        val o = RaahiJson.parseToJsonElement(Protocol.commandText("darwaza dhoondo")).jsonObject
        assertEquals("command_text", o["type"]!!.jsonPrimitive.content)
        assertEquals("darwaza dhoondo", o["text"]!!.jsonPrimitive.content)
        assertEquals("ur", o["lang"]!!.jsonPrimitive.content)
        assertFalse(o["wake_required"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `server url parsing derives http base and ws url`() {
        assertEquals("http://10.0.2.2:8000", ServerConfig.parse("http://10.0.2.2:8000")!!.httpBase)
        assertEquals("ws://10.0.2.2:8000/ws", ServerConfig.parse("http://10.0.2.2:8000")!!.wsUrl)
        assertEquals("ws://192.168.1.20:8000/ws", ServerConfig.parse("192.168.1.20:8000")!!.wsUrl)
        val tls = ServerConfig.parse("wss://api.raahi.app/ws")!!
        assertEquals("https://api.raahi.app", tls.httpBase)
        assertEquals("wss://api.raahi.app/ws", tls.wsUrl)
        assertEquals("https://api.raahi.app/clips/manifest.json", tls.http("/clips/manifest.json"))
        assertNull(ServerConfig.parse(""))
        assertNull(ServerConfig.parse("ftp://x"))
    }

    @Test
    fun `scribe url has token model and repeated keyterms and never the api key`() {
        val url = ScribeClient.buildUrl("sutkn_abc", ScribeConfig(keyterms = listOf("راہی", "darwaza")))
        assertEquals("api.elevenlabs.io", url.host)
        assertEquals("/v1/speech-to-text/realtime", url.encodedPath)
        assertEquals("scribe_v2_realtime", url.queryParameter("model_id"))
        assertEquals("sutkn_abc", url.queryParameter("token"))
        assertEquals("pcm_16000", url.queryParameter("audio_format"))
        assertEquals("ur", url.queryParameter("language_code"))
        assertEquals("vad", url.queryParameter("commit_strategy"))
        assertEquals(listOf("راہی", "darwaza"), url.queryParameterValues("keyterms"))
        assertNull(url.queryParameter("xi-api-key"))
    }

    @Test
    fun `manifest parses clip list`() {
        val m = RaahiJson.decodeFromString<ClipManifest>(
            """{"version":1,"classes":{},"clips":[{"id":"obj_chair","file":"obj_chair.mp3","urdu_text":"کرسی","en":"chair","category":"object","duration_ms":688,"source":"recorded"}]}"""
        )
        assertNotNull(m["obj_chair"])
        assertEquals("chair", m["obj_chair"]!!.en)
        assertNull(m["nope"])
    }
}
