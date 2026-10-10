package com.raahi.app.domain

import com.raahi.app.core.model.ClipManifest
import com.raahi.app.core.model.ServerResult

enum class Maneuver { LEFT, RIGHT, STRAIGHT, TURN_LEFT, TURN_RIGHT, BACK, SLOW, STOP, CLEAR, SEARCH, ARRIVED, NOTHING }

enum class Severity { INFO, CAUTION, DANGER, OK }

/** What the big instruction panel shows. Derived only from the clip ids the server sent (protocol clip list). */
data class Instruction(
    val maneuver: Maneuver,
    /** e.g. "MOVE LEFT" */
    val title: String,
    /** Material Symbols ligature name */
    val icon: String,
    val urdu: String,
    /** e.g. "CHAIR AHEAD · 2 STEPS AWAY" ("" when the server named no object) */
    val header: String,
    val headerIcon: String,
    val severity: Severity,
    val english: String,
    val receivedAt: Long,
)

object InstructionParser {
    private val OBJ_ICONS = mapOf(
        "chair" to "chair", "door" to "door_front", "glass_door" to "door_front", "table" to "table_restaurant",
        "desk" to "table_restaurant", "person" to "person", "sofa" to "chair_alt", "bag" to "backpack",
        "trash_bin" to "delete", "stairs" to "stairs", "wall" to "wall_art", "bed" to "bed",
    )

    private fun maneuverFor(id: String): Pair<Maneuver, String>? = when {
        id.startsWith("move_left_") -> Maneuver.LEFT to "MOVE LEFT"
        id.startsWith("move_right_") -> Maneuver.RIGHT to "MOVE RIGHT"
        id == "go_straight" -> Maneuver.STRAIGHT to "GO STRAIGHT"
        id == "turn_left_slight" -> Maneuver.TURN_LEFT to "TURN SLIGHTLY LEFT"
        id == "turn_right_slight" -> Maneuver.TURN_RIGHT to "TURN SLIGHTLY RIGHT"
        id == "turn_left" -> Maneuver.TURN_LEFT to "TURN LEFT"
        id == "turn_right" -> Maneuver.TURN_RIGHT to "TURN RIGHT"
        id == "turn_slowly" -> Maneuver.SEARCH to "TURN SLOWLY"
        id == "move_back" -> Maneuver.BACK to "STEP BACK"
        id == "slow_down" -> Maneuver.SLOW to "SLOW DOWN"
        id.startsWith("urg_stop") -> Maneuver.STOP to "STOP"
        id == "sys_path_clear" -> Maneuver.CLEAR to "PATH CLEAR"
        id == "sys_nothing_ahead" -> Maneuver.NOTHING to "NOTHING AHEAD"
        id == "find_searching" || id == "find_lost" -> Maneuver.SEARCH to "SEARCHING"
        id.startsWith("find_arrived_") -> Maneuver.ARRIVED to "YOU'RE THERE"
        else -> null
    }

    fun iconFor(m: Maneuver, clipId: String): String = when (m) {
        Maneuver.LEFT -> "turn_left"
        Maneuver.RIGHT -> "turn_right"
        Maneuver.STRAIGHT -> "straight"
        Maneuver.TURN_LEFT -> if (clipId.endsWith("slight")) "turn_slight_left" else "turn_left"
        Maneuver.TURN_RIGHT -> if (clipId.endsWith("slight")) "turn_slight_right" else "turn_right"
        Maneuver.BACK -> "arrow_downward"
        Maneuver.SLOW -> "speed"
        Maneuver.STOP -> "front_hand"
        Maneuver.CLEAR -> "check_circle"
        Maneuver.SEARCH -> "search"
        Maneuver.ARRIVED -> "flag"
        Maneuver.NOTHING -> "visibility"
    }

    fun objectName(clipId: String, manifest: ClipManifest): String =
        manifest[clipId]?.en?.takeIf { it.isNotBlank() } ?: clipId.removePrefix("obj_").replace('_', ' ')

    /**
     * Null when the result carries no guidance (empty clips, or only system/confirmation/description clips) so the
     * UI keeps the previous instruction until it goes stale.
     */
    fun parse(result: ServerResult, manifest: ClipManifest, now: Long): Instruction? {
        if (result.clips.isEmpty() || result.clips.any { it == "desc_intro" }) return null

        var obj: String? = null
        var pos: String? = null
        var steps: String? = null
        var man: Triple<Maneuver, String, String>? = null // maneuver, title, clip id

        for (id in result.clips) {
            when {
                id.startsWith("obj_") && obj == null -> obj = objectName(id, manifest)
                id.startsWith("pos_ahead") -> pos = "AHEAD"
                id.startsWith("pos_left") -> pos = "LEFT"
                id.startsWith("pos_right") -> pos = "RIGHT"
                id == "steps_far" -> steps = "FAR AWAY"
                id.startsWith("steps_") -> steps = id.removePrefix("steps_").toIntOrNull()?.let { if (it == 1) "1 STEP AWAY" else "$it STEPS AWAY" }
                else -> if (man == null) maneuverFor(id)?.let { man = Triple(it.first, it.second, id) }
            }
        }
        val m = man ?: return null
        val o = obj
        val p = pos
        val st = steps
        val header = if (o == null) "" else buildString {
            append(o.uppercase())
            if (p != null) append(' ').append(p)
            if (st != null) append(" · ").append(st)
        }
        val headerIcon = o?.let { OBJ_ICONS[it.replace(' ', '_')] } ?: "warning"
        val severity = when (m.first) {
            Maneuver.STOP -> Severity.DANGER
            Maneuver.CLEAR, Maneuver.ARRIVED -> Severity.OK
            Maneuver.NOTHING, Maneuver.SEARCH -> Severity.INFO
            else -> Severity.CAUTION
        }
        return Instruction(
            maneuver = m.first,
            title = m.second,
            icon = iconFor(m.first, m.third),
            urdu = manifest[m.third]?.urduText.orEmpty(),
            header = header,
            headerIcon = headerIcon,
            severity = severity,
            english = result.english,
            receivedAt = now,
        )
    }

    /** True for results announcing that a "find" target is no longer visible (server clip `find_lost`). */
    fun isFindLost(result: ServerResult) = result.clips.contains("find_lost")

    fun isArrived(result: ServerResult) =
        result.haptic == "arrived" || result.clips.any { it.startsWith("find_arrived_") }

    fun isStopAlert(result: ServerResult) = result.urgent && result.clips.any { it.startsWith("urg_stop") }
}
