package com.raahi.app.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed mirror of contracts/protocol.md section 5.2 (server -> phone `result` message).
 * Field names match server/engine/types.py `Reply.to_payload` exactly.
 */
@Serializable
data class Latency(
    val detect: Int = 0,
    val depth: Int = 0,
    val engine: Int = 0,
    val total: Int = 0,
)

@Serializable
data class OverlayBox(
    val label: String = "obstacle",
    /** normalised [x1, y1, x2, y2] in 0..1 of the streamed frame */
    val box: List<Float> = emptyList(),
    @SerialName("dist_m") val distM: Float? = null,
)

@Serializable
data class ServerResult(
    val type: String = "result",
    val clips: List<String> = emptyList(),
    val urgent: Boolean = false,
    val interrupt: Boolean = false,
    /** none | short | long | arrived */
    val haptic: String = "none",
    /** navigate | find | paused */
    val mode: String = "navigate",
    val overlay: List<OverlayBox> = emptyList(),
    val english: String = "",
    @SerialName("latency_ms") val latency: Latency = Latency(),
)

object ServerMode {
    const val NAVIGATE = "navigate"
    const val FIND = "find"
    const val PAUSED = "paused"
}

object HapticKind {
    const val NONE = "none"
    const val SHORT = "short"
    const val LONG = "long"
    const val ARRIVED = "arrived"
}

/** Find targets supported by the server (server/config.py FIND_TARGETS). */
enum class FindTarget(val wire: String, val label: String, val icon: String) {
    DOOR("door", "Door", "door_front"),
    CHAIR("chair", "Chair", "chair"),
    TABLE("table", "Table", "table_restaurant"),
    PERSON("person", "Person", "person"),
    SOFA("sofa", "Sofa", "chair_alt"),
    TRASH_BIN("trash_bin", "Trash bin", "delete");

    companion object {
        fun fromWire(w: String?): FindTarget? = entries.firstOrNull { it.wire == w }
    }
}

/** `/clips/manifest.json` (voice_assets/clips/manifest.json). */
@Serializable
data class ClipInfo(
    val id: String,
    val file: String,
    @SerialName("urdu_text") val urduText: String = "",
    val en: String = "",
    val category: String = "",
    @SerialName("duration_ms") val durationMs: Int = 0,
    val source: String = "",
    /** English recording of the same clip (`en/<id>.mp3`); blank = only Urdu exists (the Urdu file is used). */
    @SerialName("en_file") val enFile: String = "",
    @SerialName("en_text") val enText: String = "",
)

@Serializable
data class ClipManifest(
    val version: Int = 1,
    val clips: List<ClipInfo> = emptyList(),
) {
    private val byId: Map<String, ClipInfo> by lazy { clips.associateBy { it.id } }
    operator fun get(id: String): ClipInfo? = byId[id]

    companion object {
        val EMPTY = ClipManifest()
    }
}

/** `/voice/scribe-config` */
@Serializable
data class ScribeConfig(
    @SerialName("model_id") val modelId: String = "scribe_v2_realtime",
    @SerialName("language_code") val languageCode: String = "ur",
    val keyterms: List<String> = emptyList(),
)

/** `/health` */
@Serializable
data class HealthInfo(
    val ok: Boolean = false,
    val ready: Boolean = false,
    val mock: Boolean = false,
    val phone: Boolean = false,
    val mode: String = "",
    val depth: kotlinx.serialization.json.JsonElement? = null,
)

/** Socket connectivity as seen by the UI. */
sealed interface ConnectionState {
    /** No attempt yet / session not running. */
    data object Idle : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val since: Long) : ConnectionState

    /** Dropped after being connected (or while retrying). [retryInSec] counts down to the next attempt. */
    data class Lost(val retryInSec: Int) : ConnectionState

    /** Could not reach the server at all (never opened). */
    data object ServerUnavailable : ConnectionState
}
