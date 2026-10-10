package com.raahi.app.core.network

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Phone -> server JSON messages, exactly as in contracts/protocol.md 5.1 and
 * server/main.py `ws_phone` / `_intent_from_message`.
 */
object Protocol {
    const val CLIENT = "android"
    const val VERSION = "0.1"

    /** {"type":"hello","client":"android","version":"0.1"} */
    fun hello(): String = buildJsonObject {
        put("type", "hello")
        put("client", CLIENT)
        put("version", VERSION)
    }.toString()

    /** {"type":"command","intent":"find","target":"door"} ; intents the server handles: find|describe|stop|pause|resume */
    fun command(intent: String, target: String? = null): String = buildJsonObject {
        put("type", "command")
        put("intent", intent)
        if (target != null) put("target", target)
    }.toString()

    /** {"type":"command_text","text":"...","lang":"ur","wake_required":false} (push-to-talk => no wake word needed) */
    fun commandText(text: String, wakeRequired: Boolean = false): String = buildJsonObject {
        put("type", "command_text")
        put("text", text)
        put("lang", "ur")
        put("wake_required", wakeRequired)
    }.toString()
}
