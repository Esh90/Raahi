package com.raahi.app.feature.errors

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.raahi.app.core.designsystem.*
import com.raahi.app.core.model.ConnectionState
import com.raahi.app.core.navigation.ErrorKind
import com.raahi.app.feature.guidance.SessionViewModel
import com.raahi.app.feature.settings.ConnectionStateCard

private class ErrorSpec(val icon: String, val tone: Tone, val title: String, val body: String, val cta: String)
private enum class Tone { WARN, DANGER, NEUTRAL }

private fun spec(kind: ErrorKind, target: String?): ErrorSpec = when (kind) {
    ErrorKind.CAMERA_PERMISSION -> ErrorSpec("no_photography", Tone.WARN, "Camera access needed", "Raahi can’t see obstacles without the camera. Allow it in Android settings.", "Open settings")
    ErrorKind.MIC_PERMISSION -> ErrorSpec("mic_off", Tone.WARN, "Microphone access needed", "Voice commands are off. Guidance still works with buttons.", "Open settings")
    ErrorKind.SERVER_UNAVAILABLE -> ErrorSpec("cloud_off", Tone.DANGER, "Server unavailable", "The Raahi server is not responding. Guidance can’t start right now.", "Try again")
    ErrorKind.CONNECTION -> ErrorSpec("wifi_off", Tone.DANGER, "Connection error", "Check your Wi‑Fi or mobile data, then reconnect.", "Reconnect")
    ErrorKind.CAMERA_FAILED -> ErrorSpec("videocam_off", Tone.DANGER, "Camera not working", "Another app may be using the camera. Close it and try again.", "Restart camera")
    ErrorKind.NO_SOUND -> ErrorSpec("volume_off", Tone.WARN, "No sound", "Raahi can’t play audio. Check volume or Bluetooth headphones.", "Test audio")
    ErrorKind.NOT_FOUND -> ErrorSpec("search_off", Tone.NEUTRAL, "${target ?: "Object"} not found", "Slowly turn left and right to scan the room again.", "Search again")
    ErrorKind.GENERIC -> ErrorSpec("error", Tone.NEUTRAL, "Something went wrong", "An unexpected problem occurred. Your settings are safe.", "Restart Raahi")
}

/** 31a-31h - the approved error screens, one composable driven by [ErrorKind]. */
@Composable
fun ErrorScreen(kind: ErrorKind, target: String?, onPrimary: () -> Unit, onHome: () -> Unit) {
    val c = Raahi.colors
    val e = spec(kind, target)
    val (bg, fg) = when (e.tone) {
        Tone.WARN -> c.cautionSoft to c.cautionText
        Tone.DANGER -> c.dangerSoft to c.dangerText
        Tone.NEUTRAL -> c.sand to c.ink
    }
    PaperScreen {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
        ) {
            Box(Modifier.size(128.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                Sym(e.icon, size = 56.dp, tint = fg)
            }
            Text(
                e.title, style = RT.h2.copy(lineHeight = 31.sp), color = c.ink, textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(e.body, style = RT.body, color = c.muted, textAlign = TextAlign.Center)
        }
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RButton(e.cta, onPrimary, Modifier.fillMaxWidth())
            RButton("Go home", onHome, Modifier.fillMaxWidth(), style = BtnStyle.Secondary)
        }
    }
}

/** 16 - Connection states. The live state is highlighted; the others are shown dimmed as a legend. */
@Composable
fun ConnectionStatusScreen(onBack: () -> Unit, vm: SessionViewModel = viewModel()) {
    val c = Raahi.colors
    val conn by vm.connection.collectAsStateWithLifecycle()
    val rtt by vm.roundTripMs.collectAsStateWithLifecycle()

    val states = listOf<ConnectionState>(
        ConnectionState.Connecting,
        ConnectionState.Connected(System.currentTimeMillis()),
        ConnectionState.Lost(5),
        ConnectionState.ServerUnavailable,
    )
    fun rank(s: ConnectionState) = when (s) {
        ConnectionState.Connecting, ConnectionState.Idle -> 0
        is ConnectionState.Connected -> 1
        is ConnectionState.Lost -> 2
        ConnectionState.ServerUnavailable -> 3
    }
    val live = rank(conn)

    PaperScreen {
        TopBar("Connection", onBack = onBack)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            states.forEachIndexed { i, s ->
                if (i == live) ConnectionStateCard(conn, rtt, Modifier.fillMaxWidth())
                else ConnectionStateCard(s, 0, Modifier.fillMaxWidth().alpha(0.45f))
            }
            Label("Banner variant", Modifier.padding(top = 8.dp))
            if (conn is ConnectionState.Connected || conn == ConnectionState.Idle) {
                ConnectionBanner(ConnectionState.Lost(5), onRetry = {}, modifier = Modifier.alpha(0.45f))
            } else {
                ConnectionBanner(conn, onRetry = vm::reconnect)
            }
            Spacer(Modifier.height(8.dp))
            RButton("Reconnect", vm::reconnect, Modifier.fillMaxWidth(), icon = "sync")
        }
    }
}
