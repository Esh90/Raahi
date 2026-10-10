package com.raahi.app.core.network

import java.net.URI

/**
 * One central place that turns whatever the user typed ("192.168.1.5:8000", "wss://api.raahi.app",
 * "http://10.0.2.2:8000/ws") into the HTTP base URL and the WebSocket URL used by the backend
 * (`GET /health`, `/scribe-token`, `/clips/...` and `WS /ws`).
 */
data class ServerConfig(val httpBase: String, val wsUrl: String) {

    val displayUrl: String get() = httpBase

    fun http(path: String): String = httpBase.trimEnd('/') + "/" + path.trimStart('/')

    companion object {
        /** Returns null when [input] is blank or cannot be understood as a host. */
        fun parse(input: String): ServerConfig? {
            val raw = input.trim()
            if (raw.isEmpty()) return null
            val withScheme = if ("://" in raw) raw else "http://$raw"
            val uri = try {
                URI(withScheme)
            } catch (_: Exception) {
                return null
            }
            val host = uri.host ?: return null
            val secure = when (uri.scheme?.lowercase()) {
                "https", "wss" -> true
                "http", "ws" -> false
                else -> return null
            }
            val port = if (uri.port != -1) ":${uri.port}" else ""
            val hostPart = if (host.contains(':')) "[$host]" else host
            val authority = "$hostPart$port"
            return ServerConfig(
                httpBase = (if (secure) "https://" else "http://") + authority,
                wsUrl = (if (secure) "wss://" else "ws://") + authority + "/ws",
            )
        }
    }
}
