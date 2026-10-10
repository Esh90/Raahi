package com.raahi.app.feature.guidance

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.raahi.app.core.camera.CameraStatus
import com.raahi.app.core.designsystem.*
import com.raahi.app.core.model.ConnectionState
import com.raahi.app.core.model.Fmt
import com.raahi.app.core.model.ServerMode
import com.raahi.app.core.navigation.ErrorKind
import com.raahi.app.data.AppSettings
import com.raahi.app.data.GuidanceDisplay
import com.raahi.app.domain.Instruction
import com.raahi.app.domain.SessionEvent
import com.raahi.app.domain.SessionKind
import com.raahi.app.domain.SessionPhase
import com.raahi.app.domain.SessionUi
import com.raahi.app.domain.Severity
import com.raahi.app.feature.voice.VoiceCommandContent

/**
 * 09 Active guidance / 10 Expanded camera / 14 Paused / 15 Danger - one host so the camera stays bound while the
 * danger alert and the voice overlay are shown on top of it.
 */
@Composable
fun GuidanceScreen(
    onFinished: (hasSummary: Boolean) -> Unit,
    onError: (ErrorKind, String?) -> Unit,
    vm: SessionViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val s = settings ?: AppSettings()
    val camStatus by vm.cameraStatus.collectAsStateWithLifecycle()
    val audioProblem by vm.audioProblem.collectAsStateWithLifecycle()

    var expanded by remember(s.guidanceDisplay) { mutableStateOf(s.guidanceDisplay == GuidanceDisplay.CAMERA) }
    var showVoice by remember { mutableStateOf(false) }

    // keep the screen on while guiding (Settings > Guidance > Keep screen awake)
    val view = LocalView.current
    DisposableEffect(s.keepAwake) {
        view.keepScreenOn = s.keepAwake
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            if (e is SessionEvent.FindNotFound) onError(ErrorKind.NOT_FOUND, e.target?.label)
        }
    }
    LaunchedEffect(camStatus) {
        if (camStatus is CameraStatus.Failed) onError(ErrorKind.CAMERA_FAILED, null)
    }
    var finishing by remember { mutableStateOf(false) }
    LaunchedEffect(ui.phase) {
        if (ui.phase == SessionPhase.IDLE && !finishing) onFinished(false)
    }

    fun stop() {
        finishing = true
        val rec = vm.end()
        onFinished(rec != null)
    }

    when (ui.phase) {
        SessionPhase.PAUSED -> {
            BackHandler { stop() }
            PausedScreen(ui.pausedAt, onResume = vm.session::resume, onStop = ::stop)
        }
        SessionPhase.IDLE -> Unit
        SessionPhase.ACTIVE -> {
            BackHandler {
                when {
                    showVoice -> showVoice = false
                    expanded && s.guidanceDisplay != GuidanceDisplay.CAMERA -> expanded = false
                    else -> vm.session.pause()
                }
            }
            Box(Modifier.fillMaxSize()) {
                ActiveLayout(
                    vm = vm,
                    ui = ui,
                    s = s,
                    expanded = expanded,
                    onToggleExpanded = { expanded = !expanded },
                    onTalk = { showVoice = true },
                    onStop = ::stop,
                    audioProblem = audioProblem != null,
                    onAudioProblem = { onError(ErrorKind.NO_SOUND, null) },
                )
                if (ui.danger) {
                    val urdu = vm.manifest.collectAsStateWithLifecycle().value["urg_stop"]?.urduText.orEmpty()
                    val playing by vm.audioPlaying.collectAsStateWithLifecycle()
                    DangerScreen(
                        serverEnglish = ui.subtitle,
                        urdu = urdu,
                        audioPlaying = playing,
                        vibrating = s.haptics && vm.haptics.isAvailable,
                        onDismiss = vm.session::dismissDanger,
                    )
                }
                if (showVoice) {
                    PaperScreen(Modifier.pointerInput(Unit) { detectTapGestures { } }) { // swallow touches meant for the layer below
                        VoiceCommandContent(
                            autoSend = true,
                            onClose = { showVoice = false },
                            onMicDenied = { showVoice = false; onError(ErrorKind.MIC_PERMISSION, null) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveLayout(
    vm: SessionViewModel,
    ui: SessionUi,
    s: AppSettings,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onTalk: () -> Unit,
    onStop: () -> Unit,
    audioProblem: Boolean,
    onAudioProblem: () -> Unit,
) {
    val c = Raahi.colors
    val conn by vm.connection.collectAsStateWithLifecycle()
    val elapsed by rememberElapsed(ui.startedAt)
    val session = vm.session

    ScreenBox(c.navy) {
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { session.repeatLast() }, onLongPress = { onTalk() }) }
                .pointerInput(Unit) {
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = { if (total < -140f) session.pause() },
                    ) { _, amount -> total += amount }
                }
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction("Repeat last instruction") { session.repeatLast(); true },
                        CustomAccessibilityAction("Talk to Raahi") { onTalk(); true },
                        CustomAccessibilityAction("Pause guidance") { session.pause(); true },
                    )
                },
        ) {
            val split = s.guidanceDisplay == GuidanceDisplay.SPLIT
            val camHeight: Dp = when {
                expanded -> maxHeight
                split -> maxHeight * 0.5f
                else -> 300.dp
            }
            // ---------------------------------------------------------------- camera
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(camHeight).background(c.camStripeA)) {
                CameraPreviewSurface(vm.cameraController, Modifier.fillMaxSize())
                if (s.detectionOverlay) BoxesOverlay(ui.overlay, ui.frameWidth, ui.frameHeight, Modifier.fillMaxSize())
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    if (expanded) {
                        IconAction("close_fullscreen", "Collapse camera", onToggleExpanded, bg = c.surface, border = c.borderStrong, size = 48.dp)
                        Chip(
                            "${ui.overlay.size} ${if (ui.overlay.size == 1) "object" else "objects"}",
                            height = 36.dp, textStyle = RT.caption.copy(fontWeight = FontWeight.Medium),
                        )
                    } else {
                        val label = if (ui.kind == SessionKind.FIND && ui.target != null) "Finding ${ui.target.label}" else "Guiding"
                        Chip(
                            "$label · ${Fmt.clock(elapsed)}", dot = Color(0xFFB3261E), height = 32.dp,
                            textStyle = RT.caption.copy(fontWeight = FontWeight.Medium),
                        )
                        IconAction("open_in_full", "Expand camera", onToggleExpanded, bg = c.surface, border = c.borderStrong, size = 48.dp)
                    }
                }
            }

            // ---------------------------------------------------------------- instruction sheet / expanded card
            if (!expanded) {
                Column(
                    Modifier.fillMaxSize().padding(top = camHeight - 24.dp)
                        .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(c.paper)
                        .padding(start = 20.dp, end = 20.dp, top = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        StatusStrip(conn, ui, audioProblem, { vm.reconnect() }, onAudioProblem)
                        InstructionBlock(ui, s)
                    }
                    ActionRow(s.largeControls, onTalk, { session.repeatLast() }, { session.pause() }, onStop)
                }
            } else {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusStrip(conn, ui, audioProblem, { vm.reconnect() }, onAudioProblem, compact = true)
                    ExpandedInstructionCard(ui) { session.pause() }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ pieces

@Composable
private fun StatusStrip(
    conn: ConnectionState,
    ui: SessionUi,
    audioProblem: Boolean,
    onRetry: () -> Unit,
    onAudioProblem: () -> Unit,
    compact: Boolean = false,
) {
    val c = Raahi.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ConnectionBanner(conn, onRetry)
        if (!ui.upright) {
            RCard(Modifier.fillMaxWidth(), color = c.cautionCard, borderColor = c.cautionBorder, padding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Sym("smartphone", tint = c.cautionText)
                    Text("Hold the phone upright to keep guiding", style = RT.bodyS.copy(fontWeight = FontWeight.Medium), color = c.ink)
                }
            }
        }
        if (audioProblem) {
            RCard(Modifier.fillMaxWidth(), color = c.cautionCard, borderColor = c.cautionBorder, padding = 12.dp, onClick = onAudioProblem, onClickLabel = "Open sound help") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Sym("volume_off", tint = c.cautionText)
                    Text("Can't play audio · tap for help", style = RT.bodyS.copy(fontWeight = FontWeight.Medium), color = c.ink)
                }
            }
        }
        if (ui.foundBanner != null) {
            RCard(Modifier.fillMaxWidth(), color = c.safeCard, borderColor = c.safeBorder, padding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Sym("check_circle", tint = c.safe, filled = true)
                    Text(ui.foundBanner, style = RT.bodyS.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
                }
            }
        }
    }
}

private fun titleBreak(title: String): String {
    val words = title.split(' ')
    return if (words.size == 2) words.joinToString("\n") else title
}

@Composable
private fun InstructionBlock(ui: SessionUi, s: AppSettings) {
    val c = Raahi.colors
    val ins: Instruction? = ui.instruction
    val eyebrowColor = when (ins?.severity) {
        Severity.DANGER -> c.dangerText
        Severity.CAUTION -> c.cautionText
        Severity.OK -> c.safe
        else -> c.mutedSoft
    }
    val finding = ui.kind == SessionKind.FIND || ui.serverMode == ServerMode.FIND
    val findLine = ui.target?.let { "LOOKING FOR ${it.label.uppercase()}" } ?: "SEARCHING"
    val header = when {
        ins != null && ins.header.isNotBlank() -> ins.header
        finding -> findLine
        else -> if (ins == null) "WATCHING YOUR PATH" else ""
    }
    val headerIcon = when {
        ins != null && ins.header.isNotBlank() -> ins.headerIcon
        finding -> "search"
        else -> "sensors"
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (header.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Sym(headerIcon, size = 20.dp, tint = eyebrowColor, filled = true)
                Text(header, style = RT.eyebrow, color = eyebrowColor)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val dangerous = ins?.severity == Severity.DANGER
            Box(
                Modifier.size(76.dp).clip(RoundedCornerShape(24.dp)).background(if (dangerous) c.danger else c.teal),
                contentAlignment = Alignment.Center,
            ) { Sym(ins?.icon ?: "sensors", size = 48.dp, tint = if (dangerous) Color.White else c.onTeal) }
            val title = ins?.title ?: "SCANNING"
            Text(
                titleBreak(title),
                style = RT.instruction.copy(fontSize = if (title.length > 14) 32.sp else 46.sp, lineHeight = if (title.length > 14) 34.sp else 46.sp),
                color = c.ink,
            )
        }
        if (ins != null && ins.urdu.isNotBlank() && s.urduVoice) {
            Text(ins.urdu, style = RT.urdu.copy(fontSize = 19.sp, lineHeight = 36.sp), color = c.muted, textAlign = TextAlign.Left)
        }
        if (s.englishSubtitles && ui.subtitle.isNotBlank()) {
            Text(ui.subtitle, style = RT.body.copy(fontSize = 17.sp), color = c.muted)
        } else if (ins == null) {
            Text("I'll tell you when something is in your way.", style = RT.body, color = c.muted)
        }
    }
}

@Composable
private fun ActionRow(large: Boolean, onTalk: () -> Unit, onRepeat: () -> Unit, onPause: () -> Unit, onStop: () -> Unit) {
    val c = Raahi.colors
    val size = if (large) 76.dp else 64.dp
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionButton("Talk", "mic", onTalk, size, bg = c.teal, fg = c.onTeal, filled = true, modifier = Modifier.weight(1f))
        ActionButton("Repeat", "replay", onRepeat, size, bg = c.surface, fg = c.ink, border = c.borderStrong, modifier = Modifier.weight(1f))
        ActionButton("Pause", "pause", onPause, size, bg = c.surface, fg = c.ink, border = c.borderStrong, filled = true, modifier = Modifier.weight(1f))
        ActionButton("Stop", "stop", onStop, size, bg = c.dangerSoft, fg = c.dangerText, border = c.dangerBorder, filled = true, labelColor = c.dangerText, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ActionButton(
    label: String,
    icon: String,
    onClick: () -> Unit,
    size: Dp,
    bg: Color,
    fg: Color,
    modifier: Modifier = Modifier,
    border: Color? = null,
    filled: Boolean = false,
    labelColor: Color = Raahi.colors.ink,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(22.dp)).background(bg)
                .then(if (border != null) Modifier.border(1.dp, border, RoundedCornerShape(22.dp)) else Modifier)
                .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Sym(icon, size = 28.dp, tint = fg, filled = filled) }
        Text(label, style = RT.caption.copy(fontWeight = FontWeight.SemiBold), color = labelColor)
    }
}

/** Bottom card of the expanded camera (design 10). */
@Composable
private fun ExpandedInstructionCard(ui: SessionUi, onPause: () -> Unit) {
    val c = Raahi.colors
    val ins = ui.instruction
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.paper).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val dangerous = ins?.severity == Severity.DANGER
        IconBubble(ins?.icon ?: "sensors", bg = if (dangerous) c.danger else c.teal, fg = if (dangerous) Color.White else c.onTeal, size = 56.dp, radius = 18.dp, iconSize = 36.dp)
        Column(Modifier.weight(1f)) {
            Text(
                (ins?.title ?: "Scanning").lowercase().replaceFirstChar { it.uppercase() },
                style = RT.h2.copy(lineHeight = 28.sp), color = c.ink,
            )
            val sub = ins?.header?.takeIf { it.isNotBlank() }?.lowercase()?.replaceFirstChar { it.uppercase() }
                ?: if (ui.subtitle.isNotBlank()) ui.subtitle else "Watching your path"
            Text(sub, style = RT.bodyS.copy(fontWeight = FontWeight.Medium), color = c.cautionText, modifier = Modifier.padding(top = 4.dp))
        }
        IconAction("pause", "Pause guidance", onPause, filled = true, border = c.borderStrong)
    }
}

