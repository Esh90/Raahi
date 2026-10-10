package com.raahi.app

import com.raahi.app.core.camera.FrameEncoder
import com.raahi.app.core.camera.Orientation
import com.raahi.app.core.haptics.HapticPatterns
import com.raahi.app.core.model.ClipInfo
import com.raahi.app.core.model.ClipManifest
import com.raahi.app.core.model.FindTarget
import com.raahi.app.core.model.Fmt
import com.raahi.app.core.model.Latency
import com.raahi.app.core.model.OverlayBox
import com.raahi.app.core.model.ServerResult
import com.raahi.app.data.Outcome
import com.raahi.app.data.TimelineType
import com.raahi.app.domain.InstructionParser
import com.raahi.app.domain.Maneuver
import com.raahi.app.domain.SessionRecorder
import com.raahi.app.domain.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

private fun manifest() = ClipManifest(
    clips = listOf(
        ClipInfo("obj_chair", "obj_chair.mp3", "کرسی", "chair"),
        ClipInfo("obj_door", "obj_door.mp3", "دروازہ", "door"),
        ClipInfo("move_left_2", "move_left_2.mp3", "دو قدم بائیں ہٹیں۔", "Move two steps left."),
        ClipInfo("urg_stop", "urg_stop.mp3", "رُک جائیں!", "Stop!"),
    )
)

private fun result(vararg clips: String, urgent: Boolean = false, haptic: String = "none", overlay: List<OverlayBox> = emptyList(), english: String = "") =
    ServerResult(clips = clips.toList(), urgent = urgent, haptic = haptic, overlay = overlay, english = english, latency = Latency())

class InstructionParserTest {
    @Test
    fun `obstacle guidance becomes header plus maneuver`() {
        val i = InstructionParser.parse(result("obj_chair", "pos_ahead", "steps_2", "move_left_2"), manifest(), 1000L)!!
        assertEquals(Maneuver.LEFT, i.maneuver)
        assertEquals("MOVE LEFT", i.title)
        assertEquals("turn_left", i.icon)
        assertEquals("CHAIR AHEAD · 2 STEPS AWAY", i.header)
        assertEquals("chair", i.headerIcon)
        assertEquals("دو قدم بائیں ہٹیں۔", i.urdu)
        assertEquals(Severity.CAUTION, i.severity)
    }

    @Test
    fun `stop clips are danger`() {
        val i = InstructionParser.parse(result("urg_stop", "move_left_2", urgent = true, haptic = "long"), manifest(), 0L)!!
        assertEquals(Maneuver.STOP, i.maneuver)
        assertEquals(Severity.DANGER, i.severity)
        assertTrue(InstructionParser.isStopAlert(result("urg_stop_obstacle", urgent = true)))
    }

    @Test
    fun `empty or description results do not replace the instruction`() {
        assertNull(InstructionParser.parse(result(), manifest(), 0L))
        assertNull(InstructionParser.parse(result("desc_intro", "obj_chair", "pos_ahead"), manifest(), 0L))
        assertNull(InstructionParser.parse(result("sys_connected"), manifest(), 0L))
    }

    @Test
    fun `find events are recognised`() {
        assertTrue(InstructionParser.isFindLost(result("find_lost")))
        assertTrue(InstructionParser.isArrived(result("find_arrived_door", haptic = "arrived")))
        assertTrue(InstructionParser.isArrived(result("sys_ok", haptic = "arrived")))
        assertFalse(InstructionParser.isArrived(result("go_straight")))
        val straight = InstructionParser.parse(result("go_straight"), manifest(), 0L)!!
        assertEquals("GO STRAIGHT", straight.title)
        assertEquals("straight", straight.icon)
    }
}

class SessionRecorderTest {
    private fun rec(kind: String = "guidance", target: FindTarget? = null) = SessionRecorder(kind, target, 0L) { manifest() }

    @Test
    fun `counts objects warnings and stop episodes from real results`() {
        val r = rec()
        val chair = OverlayBox("chair", listOf(0.1f, 0.1f, 0.5f, 0.9f), 1.4f)
        r.onResult(result("obj_chair", "pos_ahead", "steps_2", "move_left_2", overlay = listOf(chair), english = "Chair ahead"), 1_000)
        // same object + same direction within 10 s: not a new warning, still in view: not a new object
        r.onResult(result("obj_chair", "pos_ahead", "steps_2", "move_left_2", overlay = listOf(chair)), 2_000)
        // a stop episode, then the same episode 2 s later
        r.onResult(result("urg_stop", urgent = true, haptic = "long", overlay = listOf(OverlayBox("chair", emptyList(), 0.4f))), 20_000)
        r.onResult(result("urg_stop", urgent = true, haptic = "long"), 22_000)
        val saved = r.finish(60_000)
        assertEquals(2, saved.objects) // chair appears at 1 s, is still in view at 2 s, appears again at 20 s
        assertEquals(1, saved.warnings)
        assertEquals(1, saved.stops)
        assertEquals(Outcome.COMPLETED, saved.outcome)
        assertEquals(TimelineType.START, saved.timeline.first().type)
        assertEquals(TimelineType.END, saved.timeline.last().type)
        assertTrue(saved.timeline.any { it.type == TimelineType.STOP && it.text.contains("0.4") })
        assertEquals(60_000, saved.durationMs)
    }

    @Test
    fun `find session outcome follows found and lost events`() {
        val found = rec("find", FindTarget.DOOR)
        found.onResult(result("find_arrived_door", haptic = "arrived"), 10_000)
        assertEquals(Outcome.FOUND, found.finish(20_000).outcome)
        assertEquals(1, rec("find", FindTarget.DOOR).also { it.onResult(result("find_arrived_door", haptic = "arrived"), 10_000) }.finish(11_000).found)

        val lost = rec("find", FindTarget.CHAIR)
        lost.onResult(result("find_lost"), 5_000)
        assertEquals(Outcome.NOT_FOUND, lost.finish(9_000).outcome)

        assertEquals(Outcome.STOPPED, rec("find", FindTarget.SOFA).finish(1_000).outcome)
    }
}

class FormatAndSensorTest {
    @Test
    fun `clock and minutes`() {
        assertEquals("18:24", Fmt.clock(18 * 60_000L + 24_000L))
        assertEquals("0:42", Fmt.clock(42_000))
        assertEquals("1:02:03", Fmt.clock(3_723_000))
        assertEquals("<1 min", Fmt.minutes(30_000))
        assertEquals("18 min", Fmt.minutes(18 * 60_000L))
    }

    @Test
    fun `day labels`() {
        val zone = ZoneId.of("UTC")
        val today = LocalDate.of(2026, 10, 9)
        val noon = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val yesterday = today.minusDays(1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("Today", Fmt.dayLabel(noon, today, zone))
        assertEquals("Yesterday", Fmt.dayLabel(yesterday, today, zone))
    }

    @Test
    fun `steps use the server step length of 0_6 m`() {
        assertEquals(3, Fmt.steps(1.5f))
        assertEquals(1, Fmt.steps(0.3f))
        assertNull(Fmt.steps(null))
        assertEquals("1.5 m", Fmt.meters(1.5f))
    }

    @Test
    fun `overlay position from normalised box`() {
        assertEquals("Ahead", Fmt.position(listOf(0.4f, 0f, 0.6f, 1f)))
        assertEquals("Left", Fmt.position(listOf(0.1f, 0f, 0.5f, 1f)))
        assertEquals("Far right", Fmt.position(listOf(0.8f, 0f, 1f, 1f)))
        assertEquals("Trash bin", Fmt.prettyLabel("trash_bin"))
    }

    @Test
    fun `upright detection with hysteresis`() {
        // portrait, held upright: gravity along +y
        assertTrue(Orientation.isUpright(0f, 9.8f, 0f, false))
        // flat on a table
        assertFalse(Orientation.isUpright(0f, 0f, 9.8f, true))
        // 40 degrees forward tilt: not enough to *become* upright (35) but enough to *stay* upright (48)
        val a = Math.toRadians(40.0)
        val y = (9.8 * Math.cos(a)).toFloat()
        val z = (9.8 * Math.sin(a)).toFloat()
        assertFalse(Orientation.isUpright(0f, y, z, false))
        assertTrue(Orientation.isUpright(0f, y, z, true))
        // landscape
        assertFalse(Orientation.isUpright(9.8f, 0f, 0f, true))
    }

    @Test
    fun `frame size keeps aspect at 416 px width after rotation`() {
        // sensor 640x480 landscape, rotated 90 -> 480x640 portrait -> 416 x 554
        assertEquals(416 to 554, FrameEncoder.targetSize(640, 480, 90))
        assertEquals(416 to 312, FrameEncoder.targetSize(640, 480, 0))
    }

    @Test
    fun `haptic patterns exist for every server value except none`() {
        assertNotNull(HapticPatterns.forKind("short"))
        assertNotNull(HapticPatterns.forKind("long"))
        assertNotNull(HapticPatterns.forKind("arrived"))
        assertNull(HapticPatterns.forKind("none"))
        assertNull(HapticPatterns.forKind("unknown"))
    }
}
