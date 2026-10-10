package com.raahi.app.domain

import com.raahi.app.core.model.ClipManifest
import com.raahi.app.core.model.FindTarget
import com.raahi.app.core.model.ServerResult
import com.raahi.app.data.Outcome
import com.raahi.app.data.SessionRecord
import com.raahi.app.data.TimelineEvent
import com.raahi.app.data.TimelineType
import java.util.Locale
import java.util.UUID

/**
 * Builds the Session Summary / History data from the *real* results the server sent during a session.
 * Counting rules (documented so the numbers are explainable):
 *  - objects : an overlay label that appears after not having been seen for >= [OBJECT_GAP_MS]
 *  - warnings: a guidance result naming an obstacle + a way around it, once per (object, direction) per [WARN_GAP_MS]
 *  - stops   : a `urg_stop*` result that starts a new danger episode (previous one ended > [STOP_GAP_MS] ago)
 *  - found   : arrival at a find target (`haptic == "arrived"` / `find_arrived_*` clip)
 */
class SessionRecorder(
    val kind: String, // "guidance" | "find"
    val target: FindTarget?,
    val startedAt: Long,
    private val manifest: () -> ClipManifest,
) {
    private val timeline = ArrayList<TimelineEvent>()
    private val lastSeen = HashMap<String, Long>()
    private val lastWarn = HashMap<String, Long>()
    private var objects = 0
    private var warnings = 0
    private var stops = 0
    private var found = 0
    private var lost = 0
    private var lastStopAt = 0L
    private var lastFoundAt = 0L

    init {
        add(startedAt, TimelineType.START, "Session started")
    }

    private fun add(at: Long, type: String, text: String) {
        if (timeline.size < MAX_EVENTS) timeline += TimelineEvent(at, type, text)
    }

    fun onResult(r: ServerResult, now: Long) {
        // objects
        for (o in r.overlay) {
            val key = o.label
            val prev = lastSeen[key]
            if (prev == null || now - prev >= OBJECT_GAP_MS) objects++
            lastSeen[key] = now
        }

        if (InstructionParser.isStopAlert(r)) {
            if (now - lastStopAt >= STOP_GAP_MS) {
                stops++
                val near = r.overlay.mapNotNull { it.distM }.minOrNull()
                add(now, TimelineType.STOP, if (near != null) "Stop alert · obstacle ${fmt(near)} m" else "Stop alert")
            }
            lastStopAt = now
            return
        }

        if (InstructionParser.isArrived(r)) {
            if (now - lastFoundAt >= STOP_GAP_MS) {
                found++
                add(now, TimelineType.FOUND, "${target?.label ?: "Target"} found")
            }
            lastFoundAt = now
            return
        }

        if (InstructionParser.isFindLost(r)) {
            lost++
            add(now, TimelineType.LOST, "${target?.label ?: "Target"} lost from view")
            return
        }

        val obj = r.clips.firstOrNull { it.startsWith("obj_") }
        val dir = r.clips.firstOrNull {
            it.startsWith("move_left") || it.startsWith("move_right") || it == "go_straight" ||
                it.startsWith("turn_") || it == "move_back"
        }
        if (obj != null && dir != null && !r.urgent) {
            val key = obj + ":" + dir.substringBeforeLast('_').ifEmpty { dir }
            val prev = lastWarn[key]
            if (prev == null || now - prev >= WARN_GAP_MS) {
                warnings++
                add(now, TimelineType.WARNING, r.english.ifBlank { InstructionParser.objectName(obj, manifest()) })
            }
            lastWarn[key] = now
        }
    }

    fun finish(endedAt: Long): SessionRecord {
        add(endedAt, TimelineType.END, "Session ended")
        val outcome = when {
            kind == "find" && found > 0 -> Outcome.FOUND
            kind == "find" && lost > 0 -> Outcome.NOT_FOUND
            kind == "find" -> Outcome.STOPPED
            else -> Outcome.COMPLETED
        }
        return SessionRecord(
            id = UUID.randomUUID().toString(),
            kind = kind,
            target = target?.wire,
            startedAt = startedAt,
            endedAt = endedAt,
            objects = objects,
            warnings = warnings,
            stops = stops,
            found = found,
            outcome = outcome,
            timeline = timeline.toList(),
        )
    }

    private fun fmt(v: Float) = String.format(Locale.US, "%.1f", v)

    companion object {
        const val OBJECT_GAP_MS = 3000L
        const val WARN_GAP_MS = 10_000L
        const val STOP_GAP_MS = 6000L
        const val MAX_EVENTS = 200
    }
}
