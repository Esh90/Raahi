package com.raahi.app.feature.help

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.raahi.app.BuildConfig
import com.raahi.app.core.designsystem.*
import com.raahi.app.feature.history.HistoryViewModel

// ------------------------------------------------------------------------------------------------ 28 Help & gestures

@Composable
fun HelpScreen(onBack: () -> Unit) {
    val c = Raahi.colors
    PaperScreen {
        TopBar("Help", onBack = onBack)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HelpGesture("touch_double", "Double tap", "Repeat", Modifier.weight(1f))
                HelpGesture("touch_long", "Long press", "Talk", Modifier.weight(1f))
                HelpGesture("swipe_left", "Swipe", "Pause", Modifier.weight(1f))
            }
            Label("Say things like")
            RowGroup {
                InfoRow("“Raahi, settings kholo”", subtitle = "Open settings (any screen: “accessibility”, “history”, “help”…)") { Sym("mic", tint = c.teal) }
                InfoRow("“Raahi, start detecting”", subtitle = "Start guidance · also “pause”, “resume”, “stop”") { Sym("mic", tint = c.teal) }
                InfoRow("“Raahi, dark mode” · “English mein bolo”", subtitle = "Theme, contrast, text size, language, volume, speed") { Sym("mic", tint = c.teal) }
                InfoRow("“Raahi, saamne kya hai?”", subtitle = "What's ahead?") { Sym("mic", tint = c.teal) }
                InfoRow("“Raahi, ruk jao” · “Roko”", subtitle = "Pause guidance") { Sym("mic", tint = c.teal) }
                InfoRow("“Raahi, jari rakho”", subtitle = "Resume guidance") { Sym("mic", tint = c.teal) }
                InfoRow("“Raahi, band karo”", subtitle = "Stop guidance", divider = false) { Sym("mic", tint = c.teal) }
            }
            Text(
                "Hold the phone upright with the camera facing forward. While you're guided, double tap repeats the last instruction, a long press lets you speak, and a swipe left pauses or resumes.",
                style = RT.bodyS, color = c.muted,
            )
        }
    }
}

@Composable
private fun HelpGesture(icon: String, title: String, body: String, modifier: Modifier) {
    val c = Raahi.colors
    RCard(modifier, padding = 6.dp) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Sym(icon, size = 34.dp, tint = c.teal)
            Text(title, style = RT.bodyS.copy(fontWeight = FontWeight.SemiBold), color = c.ink, textAlign = TextAlign.Center)
            Text(body, style = RT.caption.copy(fontSize = 12.sp), color = c.mutedSoft)
        }
    }
}

// ------------------------------------------------------------------------------------------------ 29 Privacy

@Composable
fun PrivacyScreen(onBack: () -> Unit, vm: HistoryViewModel = viewModel()) {
    var confirm by remember { mutableStateOf(false) }
    PaperScreen {
        TopBar("Privacy", onBack = onBack)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PrivacyCard("photo_camera", "Camera", "Used only while guidance is on. Frames are not saved on the phone.")
            PrivacyCard("mic", "Microphone", "Listens when you hold to talk, or - with hands-free on - while the app is open, acting only on sentences that start with “Raahi”. Speech is recognised by ElevenLabs Scribe. Turn hands-free off in Settings › Voice & audio.")
            PrivacyCard("dns", "Server", "Camera frames are sent to the Raahi server for detection and distance estimation. The app does not store them.")
            PrivacyCard("smartphone", "On this phone", "Settings and history stay on your device.")
            RButton("Clear history", { confirm = true }, Modifier.fillMaxWidth(), style = BtnStyle.DangerOutline)
        }
    }
    if (confirm) {
        val c = Raahi.colors
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Clear history?", style = RT.title) },
            text = { Text("All saved sessions on this phone will be deleted. This can't be undone.", style = RT.body) },
            confirmButton = { TextButton(onClick = { vm.clear(); confirm = false }) { Text("Clear", color = c.dangerText, style = RT.button) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel", color = c.teal, style = RT.button) } },
            containerColor = c.surface,
        )
    }
}

@Composable
private fun PrivacyCard(icon: String, title: String, body: String) {
    val c = Raahi.colors
    RCard(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBubble(icon)
            Column(Modifier.weight(1f)) {
                Text(title, style = RT.title.copy(fontSize = 17.sp), color = c.ink)
                Text(body, style = RT.bodyS, color = c.muted)
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ 30 About

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val c = Raahi.colors
    PaperScreen {
        TopBar("About", onBack = onBack)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LogoTile(88.dp, Modifier.padding(top = 12.dp), corner = 26.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Wordmark(size = 30.sp)
                Text("Version ${BuildConfig.VERSION_NAME}", style = RT.mono, color = c.mutedSoft)
            }
            Text(
                "Raahi helps blind and low-vision people move through indoor spaces with obstacle awareness and Urdu voice guidance.",
                style = RT.body, color = c.muted, textAlign = TextAlign.Center,
            )
            RCard(Modifier.fillMaxWidth()) {
                Column {
                    Label("Accessibility", Modifier.padding(bottom = 6.dp))
                    Text("Designed for TalkBack, large text and high contrast. Every control is at least 48 dp.", style = RT.bodyS, color = c.muted)
                }
            }
            RCard(Modifier.fillMaxWidth()) {
                Column {
                    Label("Credits", Modifier.padding(bottom = 6.dp))
                    Text("Urdu voice clips and speech recognition: ElevenLabs. Built with Kotlin, Jetpack Compose, CameraX and OkHttp.", style = RT.bodyS, color = c.muted)
                }
            }
        }
    }
}
