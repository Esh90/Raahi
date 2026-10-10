package com.raahi.app.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

/** Small, pure formatting helpers shared by the screens (unit tested). */
object Fmt {
    /** 1104000 -> "18:24" ; 42000 -> "0:42" ; hours -> "1:02:03" */
    fun clock(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** "18 min" / "1 min" / "<1 min" */
    fun minutes(ms: Long): String {
        val m = (ms / 60_000).toInt()
        return if (m < 1) "<1 min" else "$m min"
    }

    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)

    fun timeOfDay(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).format(timeFmt)

    /** "Today" / "Yesterday" / "Mon 6 Oct" */
    fun dayLabel(epochMs: Long, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val d = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        return when (d) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> d.format(dateFmt)
        }
    }

    fun meters(m: Float?): String = if (m == null) "" else String.format(Locale.US, "%.1f m", m)

    /** The server's step length is 0.6 m (server/config.py STEP_M). */
    fun steps(distM: Float?): Int? = distM?.let { ceil(it / 0.6f).toInt().coerceAtLeast(1) }

    /** Position of an overlay box from its normalised x extents (thirds, like the server's L/C/R zones). */
    fun position(box: List<Float>): String {
        if (box.size < 4) return "Ahead"
        val cx = (box[0] + box[2]) / 2f
        return when {
            cx < 0.25f -> "Far left"
            cx < 0.40f -> "Left"
            cx <= 0.60f -> "Ahead"
            cx <= 0.75f -> "Right"
            else -> "Far right"
        }
    }

    fun prettyLabel(label: String): String =
        label.replace('_', ' ').replaceFirstChar { it.uppercase() }
}
