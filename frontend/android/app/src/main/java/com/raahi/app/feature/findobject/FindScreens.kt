package com.raahi.app.feature.findobject

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.raahi.app.core.designsystem.*
import com.raahi.app.core.model.FindTarget
import com.raahi.app.core.model.Fmt
import com.raahi.app.core.model.OverlayBox
import com.raahi.app.core.navigation.ErrorKind
import com.raahi.app.domain.SessionKind
import com.raahi.app.domain.SessionPhase
import com.raahi.app.feature.guidance.CameraFramesOnly
import com.raahi.app.feature.guidance.SessionViewModel
import com.raahi.app.feature.guidance.rememberSessionStarter
import kotlinx.coroutines.delay

/** 12 - Find something. Only targets the server supports (server/config.py FIND_TARGETS) are offered. */
@Composable
fun FindSomethingScreen(
    onBack: () -> Unit,
    onVoice: () -> Unit,
    onStarted: (SessionKind) -> Unit,
    onError: (ErrorKind) -> Unit,
    vm: SessionViewModel = viewModel(),
) {
    val c = Raahi.colors
    var selected by remember { mutableStateOf(FindTarget.DOOR) }
    var query by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }
    val start = rememberSessionStarter(vm, onStarted, onError)

    fun submit() {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return
        val match = FindTarget.entries.firstOrNull { it.wire == q || it.label.lowercase() == q || q.replace(' ', '_') == it.wire }
            ?: FindTarget.entries.firstOrNull { q in it.label.lowercase() || it.wire.startsWith(q) }
        if (match != null) {
            selected = match
            hint = null
        } else {
            hint = "Raahi can find: " + FindTarget.entries.joinToString(", ") { it.label.lowercase() }
        }
    }

    PaperScreen {
        TopBar("Find something", onBack = onBack)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(28.dp)).background(c.surface)
                    .border(1.5.dp, c.borderStrong, RoundedCornerShape(28.dp)).padding(start = 18.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Sym("search", tint = c.mutedSoft)
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text("Search or say an object", style = RT.body, color = c.mutedSoft)
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it; hint = null },
                        singleLine = true,
                        textStyle = RT.body.copy(color = c.ink),
                        cursorBrush = SolidColor(c.teal),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit() }),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search for an object" },
                    )
                }
                IconAction("mic", "Say an object", onVoice, tint = c.onTeal, filled = true, size = 48.dp, bg = c.teal)
            }
            hint?.let { Text(it, style = RT.bodyS, color = c.cautionText) }

            Label("Common objects")
            FindTarget.entries.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { t -> TargetTile(t, t == selected, { selected = t }, Modifier.weight(1f)) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp, top = 8.dp)) {
            RButton("Find ${selected.label.lowercase()}", { start(SessionKind.FIND, selected) }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun TargetTile(t: FindTarget, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = Raahi.colors
    val shape = RoundedCornerShape(20.dp)
    val bg = if (selected) c.teal else c.surface
    val fg = if (selected) c.onTeal else c.ink
    Box(
        modifier.heightIn(min = 96.dp).clip(shape).background(bg)
            .then(if (selected) Modifier else Modifier.border(if (c.highContrast) 2.dp else 1.dp, c.border, shape))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Sym(t.icon, size = 32.dp, tint = if (selected) c.onTeal else c.teal)
            Text(t.label, style = RT.title, color = fg)
        }
        if (selected) Sym("check_circle", Modifier.align(Alignment.TopEnd), size = 22.dp, tint = c.onTeal, filled = true)
    }
}

// ------------------------------------------------------------------------------------------------ 13 What's ahead

private fun iconForLabel(label: String) = when (label.lowercase().replace(' ', '_')) {
    "chair" -> "chair"
    "door", "glass_door" -> "door_front"
    "table", "desk" -> "table_restaurant"
    "person", "child" -> "person"
    "sofa", "bench" -> "chair_alt"
    "bag" -> "backpack"
    "trash_bin" -> "delete"
    "stairs" -> "stairs"
    "bed" -> "bed"
    else -> "category"
}

/**
 * 13 - What's ahead? A short live scan: the camera streams to the server and the boxes it returns (label, distance)
 * are listed nearest first. "Read aloud" asks the server to describe the scene (`describe` command).
 */
@Composable
fun WhatsAheadScreen(
    onBack: () -> Unit,
    onError: (ErrorKind) -> Unit,
    vm: SessionViewModel = viewModel(),
) {
    val c = Raahi.colors
    val ui by vm.ui.collectAsStateWithLifecycle()
    var started by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var snapshot by remember { mutableStateOf<List<OverlayBox>>(emptyList()) }

    val start = rememberSessionStarter(vm, onStarted = { started = true }, onError = { failed = true; onError(it) })
    LaunchedEffect(Unit) { if (!vm.session.isActive) start(SessionKind.SCAN, null) else started = true }
    DisposableEffect(Unit) { onDispose { if (vm.ui.value.kind == SessionKind.SCAN) vm.end() } }

    // take the first non-empty list as the snapshot; "Scan again" replaces it with what is in view now
    LaunchedEffect(ui.overlay, started) {
        if (started && snapshot.isEmpty() && ui.overlay.isNotEmpty()) {
            delay(800)
            snapshot = vm.ui.value.overlay
        }
    }

    val items = snapshot.sortedBy { it.distM ?: Float.MAX_VALUE }
    val scanning = started && ui.phase == SessionPhase.ACTIVE
    if (started && !failed) CameraFramesOnly(vm.cameraController)

    PaperScreen {
        TopBar(
            "What's ahead?", onBack = onBack,
            trailing = { IconAction("volume_up", "Read aloud", { vm.session.requestDescribe() }) },
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val heading = when {
                !started -> "Getting ready…"
                items.isEmpty() -> if (scanning && !ui.upright) "Hold the phone upright, pointing ahead." else "Looking around… hold the phone steady."
                else -> "${items.size} ${if (items.size == 1) "thing" else "things"} in view, nearest first"
            }
            Text(heading, style = RT.body, color = c.muted)
            if (items.isNotEmpty()) {
                RowGroup {
                    items.forEachIndexed { i, o ->
                        val near = (o.distM ?: 99f) < 2.0f
                        InfoRow(
                            title = Fmt.prettyLabel(o.label),
                            subtitle = Fmt.position(o.box),
                            minHeight = 76.dp,
                            divider = i != items.lastIndex,
                        ) {
                            Column(horizontalAlignment = Alignment.End) {
                                if (o.distM != null) Text(Fmt.meters(o.distM), style = RT.title, color = c.ink)
                                val steps = Fmt.steps(o.distM)
                                if (near && steps != null) {
                                    Text(if (steps == 1) "1 step" else "$steps steps", style = RT.caption.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp), color = c.cautionText)
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RButton("Scan again", { snapshot = vm.ui.value.overlay }, Modifier.weight(1f), style = BtnStyle.Secondary, icon = "refresh")
            RButton("Read aloud", { vm.session.requestDescribe() }, Modifier.weight(1f), icon = "replay")
        }
    }
}

// ------------------------------------------------------------------------------------------------ 20 Quick commands

@Composable
fun QuickCommandsScreen(
    onBack: () -> Unit,
    onWhatsAhead: () -> Unit,
    onVoice: () -> Unit,
    onStarted: (SessionKind) -> Unit,
    onError: (ErrorKind) -> Unit,
    vm: SessionViewModel = viewModel(),
) {
    val c = Raahi.colors
    val ui by vm.ui.collectAsStateWithLifecycle()
    val start = rememberSessionStarter(vm, onStarted, onError)
    val active = ui.phase != SessionPhase.IDLE
    val paused = ui.phase == SessionPhase.PAUSED

    PaperScreen {
        TopBar("Quick commands", onBack = onBack)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            QuickRow {
                QuickTile("Find door", "door_front", { start(SessionKind.FIND, FindTarget.DOOR) }, Modifier.weight(1f))
                QuickTile("Find chair", "chair", { start(SessionKind.FIND, FindTarget.CHAIR) }, Modifier.weight(1f))
            }
            QuickRow {
                QuickTile("What's ahead?", "visibility", onWhatsAhead, Modifier.weight(1f))
                QuickTile("Talk", "mic", onVoice, Modifier.weight(1f), primary = true, filled = true)
            }
            QuickRow {
                QuickTile("Pause", "pause", { vm.session.pause() }, Modifier.weight(1f), enabled = active && !paused, tint = c.caution, filled = true)
                QuickTile("Resume", "play_arrow", { vm.session.resume() }, Modifier.weight(1f), enabled = paused, tint = c.safe, filled = true)
            }
            val stopEnabled = active
            Row(
                Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(20.dp)).background(c.dangerSoft)
                    .border(1.dp, c.dangerBorder, RoundedCornerShape(20.dp))
                    .clickable(enabled = stopEnabled, role = Role.Button) { vm.end(); onBack() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Sym("stop", size = 32.dp, tint = c.dangerText.copy(alpha = if (stopEnabled) 1f else 0.45f), filled = true)
                Text("Stop guidance", style = RT.title, color = c.dangerText.copy(alpha = if (stopEnabled) 1f else 0.45f))
            }
            if (!active) Text("Pause, Resume and Stop work while guidance is running.", style = RT.bodyS, color = c.mutedSoft)
        }
    }
}

@Composable
private fun QuickRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(118.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
private fun QuickTile(
    label: String,
    icon: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    filled: Boolean = false,
    tint: androidx.compose.ui.graphics.Color = Raahi.colors.teal,
) {
    val c = Raahi.colors
    val shape = RoundedCornerShape(20.dp)
    val alpha = if (enabled) 1f else 0.45f
    Column(
        modifier.fillMaxHeight().clip(shape).background(if (primary) c.teal else c.surface)
            .then(if (primary) Modifier else Modifier.border(1.dp, c.border, shape))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Sym(icon, size = 32.dp, tint = (if (primary) c.onTeal else tint).copy(alpha = alpha), filled = filled)
        Text(label, style = RT.title, color = (if (primary) c.onTeal else c.ink).copy(alpha = alpha))
    }
}
