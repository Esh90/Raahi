package com.raahi.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.raahi.app.BuildConfig
import com.raahi.app.core.designsystem.*
import com.raahi.app.core.model.ConnectionState
import com.raahi.app.core.model.Fmt
import com.raahi.app.data.AppSettings
import com.raahi.app.data.GuidanceDisplay
import com.raahi.app.data.SpeechSpeed
import com.raahi.app.data.ThemeChoice

// ------------------------------------------------------------------------------------------------ 21 Overview

@Composable
fun SettingsOverviewScreen(
    onTab: (MainTab) -> Unit,
    onAccessibility: () -> Unit,
    onVoice: () -> Unit,
    onGuidance: () -> Unit,
    onConnection: () -> Unit,
    onAppearance: () -> Unit,
    onDiagnostics: () -> Unit,
    onHelp: () -> Unit,
    onPrivacy: () -> Unit,
    onAbout: () -> Unit,
    vm: SettingsViewModel = viewModel(),
) {
    val c = Raahi.colors
    val st by vm.settings.collectAsStateWithLifecycle()
    val s = st ?: AppSettings()
    val conn by vm.connection.collectAsStateWithLifecycle()
    val (connText, _) = connectionPill(conn)
    val speed = listOf("Slow speed", "Normal speed", "Fast speed")[s.speed]
    val theme = listOf("Light", "Dark", "System")[s.theme]

    PaperScreen {
        TopBar("Settings", big = true)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RowGroup {
                NavRow("Accessibility", onAccessibility, icon = "accessibility_new", subtitle = "Text size, contrast, haptics")
                NavRow("Voice & audio", onVoice, icon = "record_voice_over", subtitle = "${if (s.language == "ur") "اردو" else "English"} · $speed")
                NavRow("Guidance", onGuidance, icon = "navigation", subtitle = "Overlay, gestures")
                NavRow(
                    "Connection", onConnection, icon = "wifi", subtitle = connText,
                )
                NavRow("Appearance", onAppearance, icon = "contrast", subtitle = theme, divider = false)
            }
            RowGroup {
                NavRow("Diagnostics", onDiagnostics, icon = "monitoring", bubbleBg = c.sand, bubbleFg = c.ink)
                NavRow("Help & gestures", onHelp, icon = "help", bubbleBg = c.sand, bubbleFg = c.ink)
                NavRow("Privacy", onPrivacy, icon = "lock", bubbleBg = c.sand, bubbleFg = c.ink)
                NavRow("About Raahi", onAbout, icon = "info", bubbleBg = c.sand, bubbleFg = c.ink, divider = false)
            }
        }
        MainNavBar(MainTab.SETTINGS, onTab)
    }
}

// ------------------------------------------------------------------------------------------------ 22 Accessibility

@Composable
fun AccessibilitySettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val c = Raahi.colors
    val s = (vm.settings.collectAsStateWithLifecycle().value) ?: AppSettings()
    PaperScreen {
        TopBar("Accessibility", onBack = onBack)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Label("Text size")
            Segmented(
                listOf("A", "A", "A", "A"), s.textSizeIndex, vm::setTextSize,
                fontSizes = listOf(14.sp, 17.sp, 20.sp, 24.sp),
                descriptions = listOf("Small text", "Default text", "Large text", "Extra large text"),
            )
            RCard(Modifier.fillMaxWidth()) {
                Text("Chair ahead, two steps away.", style = RT.body.copy(fontSize = 19.sp, lineHeight = 27.sp, fontWeight = FontWeight.Medium), color = c.ink)
            }
            RowGroup {
                SwitchRow("High contrast", s.highContrast, vm::setHighContrast, subtitle = "Stronger borders and text")
                SwitchRow("Reduce motion", s.reduceMotion, vm::setReduceMotion)
                SwitchRow("Haptic feedback", s.haptics, vm::setHaptics, subtitle = "Vibrate on warnings")
                SwitchRow("Large controls", s.largeControls, vm::setLargeControls, subtitle = "Bigger buttons in guidance", divider = false)
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ 23 Voice & audio

@Composable
fun VoiceAudioSettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val c = Raahi.colors
    val s = (vm.settings.collectAsStateWithLifecycle().value) ?: AppSettings()
    val problem by vm.audioProblem.collectAsStateWithLifecycle()
    var volume by remember(s.volume) { mutableFloatStateOf(s.volume) }
    PaperScreen {
        TopBar("Voice & audio", onBack = onBack)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Label("Language")
            Segmented(listOf("اردو", "English"), if (s.language == "ur") 0 else 1, { vm.setLanguage(if (it == 0) "ur" else "en") })
            RowGroup {
                SwitchRow("Voice guidance", s.urduVoice, vm::setUrduVoice, subtitle = if (s.language == "en") "Spoken in English" else "Spoken in Urdu")
                SwitchRow("Hands-free voice control", s.handsFreeVoice, vm::setHandsFreeVoice, subtitle = "Say “Raahi” then a command, e.g. “Raahi, settings”")
                SwitchRow("English subtitles", s.englishSubtitles, vm::setEnglishSubtitles, subtitle = "Shown under instructions", divider = false)
            }
            RCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Volume", style = RT.rowTitle, color = c.ink)
                        Text("${(volume * 100).toInt()}%", style = RT.rowTitle.copy(fontWeight = FontWeight.SemiBold), color = c.teal)
                    }
                    Slider(
                        value = volume,
                        onValueChange = { volume = it },
                        onValueChangeFinished = { vm.setVolume(volume) },
                        colors = SliderDefaults.colors(thumbColor = c.teal, activeTrackColor = c.teal, inactiveTrackColor = c.border),
                        modifier = Modifier.semantics { contentDescription = "Volume" },
                    )
                    Text("Speech speed", style = RT.rowTitle, color = c.ink)
                    Segmented(listOf("Slow", "Normal", "Fast"), s.speed, vm::setSpeed)
                }
            }
            RButton("Play sample", vm::playSample, Modifier.fillMaxWidth(), style = BtnStyle.Secondary, icon = "volume_up")
            if (problem != null) {
                Text("Couldn't play the sample. The voice clips download from the Raahi server, so check the connection.", style = RT.bodyS, color = c.dangerText)
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ 24 Guidance

@Composable
fun GuidanceSettingsScreen(onBack: () -> Unit, onHelp: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val s = (vm.settings.collectAsStateWithLifecycle().value) ?: AppSettings()
    PaperScreen {
        TopBar("Guidance", onBack = onBack)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            RowGroup {
                SwitchRow("Detection overlay", s.detectionOverlay, vm::setOverlay, subtitle = "Boxes on camera preview")
                SwitchRow("Keep screen awake", s.keepAwake, vm::setKeepAwake, divider = false)
            }
            Label("Gestures")
            RowGroup {
                NavRow("Double tap", onHelp, icon = "touch_double", subtitle = "Repeat", iconBubble = false)
                NavRow("Long press", onHelp, icon = "touch_long", subtitle = "Talk", iconBubble = false)
                NavRow("Swipe left", onHelp, icon = "swipe_left", subtitle = "Pause / resume", iconBubble = false, divider = false)
            }
            Label("Display")
            Segmented(
                listOf("Instruction", "Camera", "Split"), s.guidanceDisplay, vm::setDisplay,
                descriptions = listOf("Instruction layout", "Camera layout", "Split layout"),
            )
        }
    }
}

// ------------------------------------------------------------------------------------------------ 25 Connection

@Composable
fun ConnectionSettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val c = Raahi.colors
    val st by vm.settings.collectAsStateWithLifecycle()
    val s = st ?: AppSettings()
    val conn by vm.connection.collectAsStateWithLifecycle()
    val rtt by vm.roundTripMs.collectAsStateWithLifecycle()
    val test by vm.test.collectAsStateWithLifecycle()
    var url by remember(s.serverUrl) { mutableStateOf(s.serverUrl) }

    PaperScreen {
        TopBar("Connection", onBack = onBack)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ConnectionStateCard(conn, rtt, Modifier.fillMaxWidth())
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Server URL", style = RT.bodyS.copy(fontWeight = FontWeight.Medium), color = c.muted)
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp)).background(c.surface)
                        .border(1.5.dp, c.teal, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (url.isEmpty()) Text("https://your-server.example.com", style = RT.mono.copy(fontSize = 15.sp), color = c.mutedSoft)
                    BasicTextField(
                        value = url,
                        onValueChange = { url = it; vm.clearTest() },
                        singleLine = true,
                        textStyle = RT.mono.copy(fontSize = 15.sp, color = c.ink),
                        cursorBrush = SolidColor(c.teal),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { vm.saveAndTest(url) }),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Server URL" },
                    )
                }
                val u = url.trim().lowercase()
                if (!BuildConfig.DEBUG && (u.startsWith("http://") || u.startsWith("ws://"))) {
                    Text("Release builds only connect over https:// or wss://.", style = RT.caption, color = c.cautionText)
                }
            }
            RButton("Test connection", { vm.saveAndTest(url) }, Modifier.fillMaxWidth(), icon = "network_check")
            RButton("Reconnect", vm::reconnect, Modifier.fillMaxWidth(), style = BtnStyle.Secondary, icon = "sync")
            when (val t = test) {
                ConnectionTest.Idle -> Unit
                ConnectionTest.Running -> Text("Testing…", style = RT.body, color = c.muted)
                is ConnectionTest.Ok -> RCard(Modifier.fillMaxWidth(), color = c.safeCard, borderColor = c.safeBorder) {
                    val msg = buildString {
                        append("Server reachable")
                        append(if (t.info.ready) " · ready" else " · still loading models")
                        if (t.info.mock) append(" · mock mode")
                    }
                    Text(msg, style = RT.body.copy(fontWeight = FontWeight.Medium), color = c.ink)
                }
                is ConnectionTest.Failed -> RCard(Modifier.fillMaxWidth(), color = c.dangerCard, borderColor = c.dangerBorder) {
                    Text(t.message, style = RT.body.copy(fontWeight = FontWeight.Medium), color = c.ink)
                }
            }
        }
    }
}

/** Status card shared by Connection settings and the Connection screen (design 25 / 16). */
@Composable
fun ConnectionStateCard(conn: ConnectionState, rttMs: Int, modifier: Modifier = Modifier) {
    val c = Raahi.colors
    val (icon, title, detail, bg, border, ibg, ifg) = when (conn) {
        is ConnectionState.Connected -> CardSpec("check_circle", "Connected", (if (rttMs > 0) "$rttMs ms · " else "") + "since ${Fmt.timeOfDay(conn.since)}", c.safeCard, c.safeBorder, c.safeSoft, c.safe)
        ConnectionState.Connecting -> CardSpec("sync", "Connecting…", "Reaching Raahi server", c.surface, c.border, c.tealSoft, c.teal)
        is ConnectionState.Lost -> CardSpec("wifi_off", "Connection lost", "Retrying in ${conn.retryInSec} s", c.cautionCard, c.cautionBorder, c.cautionSoft, c.cautionText)
        ConnectionState.ServerUnavailable -> CardSpec("cloud_off", "Server unavailable", "Guidance can't start", c.dangerCard, c.dangerBorder, c.dangerSoft, c.dangerText)
        ConnectionState.Idle -> CardSpec("wifi_off", "Not connected", "Open the app's guidance to connect", c.surface, c.border, c.sand, c.muted)
    }
    RCard(modifier, color = bg, borderColor = border) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBubble(icon, bg = ibg, fg = ifg, filled = icon == "check_circle")
            Column(Modifier.weight(1f)) {
                Text(title, style = RT.title.copy(fontSize = 17.sp), color = c.ink)
                Text(detail, style = RT.bodyS, color = c.muted)
            }
        }
    }
}

private data class CardSpec(val icon: String, val title: String, val detail: String, val bg: Color, val border: Color, val ibg: Color, val ifg: Color)

// ------------------------------------------------------------------------------------------------ 26 Appearance

@Composable
fun AppearanceSettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val c = Raahi.colors
    val s = (vm.settings.collectAsStateWithLifecycle().value) ?: AppSettings()
    PaperScreen {
        TopBar("Appearance", onBack = onBack)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Label("Theme")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ThemeTile("Light", ThemeChoice.LIGHT, s.theme, Modifier.weight(1f), vm::setTheme) { ThemeMini(Color(0xFFF7F4EE), Color(0xFF0E5E63), Color(0xFFE6E0D5)) }
                ThemeTile("Dark", ThemeChoice.DARK, s.theme, Modifier.weight(1f), vm::setTheme) { ThemeMini(Color(0xFF11161F), Color(0xFF5FB3AA), Color(0xFF2A3342)) }
                ThemeTile("System", ThemeChoice.SYSTEM, s.theme, Modifier.weight(1f), vm::setTheme) {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFFF7F4EE)))
                        Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF11161F)))
                    }
                }
            }
            Label("Preview")
            RCard(Modifier.fillMaxWidth()) {
                Column {
                    Text("Move left", style = RT.h2, color = c.ink)
                    Text("Chair · 2 steps ahead", style = RT.body, color = c.muted)
                }
            }
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.Black).border(1.dp, Color.Black, RoundedCornerShape(20.dp)).padding(16.dp)) {
                Column {
                    Text("Move left", style = RT.h2, color = Color(0xFFFFE27A))
                    Text("High contrast · Chair · 2 steps", style = RT.body.copy(fontWeight = FontWeight.Medium), color = Color.White)
                }
            }
            Text("Turn on High contrast under Accessibility to use this style.", style = RT.bodyS, color = c.mutedSoft)
        }
    }
}

@Composable
private fun ThemeTile(label: String, value: Int, selected: Int, modifier: Modifier, onSelect: (Int) -> Unit, preview: @Composable BoxScope.() -> Unit) {
    val c = Raahi.colors
    val on = value == selected
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).clickable(role = androidx.compose.ui.semantics.Role.RadioButton) { onSelect(value) }
            .semantics { this.selected = on },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(16.dp))
                .border(if (on) 2.5.dp else 1.dp, if (on) c.teal else c.borderStrong, RoundedCornerShape(16.dp)),
            content = preview,
        )
        Text(label, style = RT.bodyS.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
    }
}

@Composable
private fun BoxScope.ThemeMini(bg: Color, accent: Color, line: Color) {
    Column(Modifier.fillMaxSize().background(bg).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(8.dp)).background(accent))
        Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(5.dp)).background(line))
        Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(5.dp)).background(line))
    }
}
