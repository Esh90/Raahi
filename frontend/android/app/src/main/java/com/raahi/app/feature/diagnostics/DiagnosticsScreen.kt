package com.raahi.app.feature.diagnostics

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.raahi.app.BuildConfig
import com.raahi.app.core.camera.CameraStatus
import com.raahi.app.core.designsystem.*
import com.raahi.app.core.model.ConnectionState
import com.raahi.app.core.model.HealthInfo
import com.raahi.app.core.permissions.CAMERA
import com.raahi.app.core.permissions.MICROPHONE
import com.raahi.app.core.permissions.hasPermission
import com.raahi.app.feature.guidance.container
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.compose.ui.platform.LocalContext

sealed interface BackendState {
    data object Checking : BackendState
    data class Online(val info: HealthInfo) : BackendState
    data object Offline : BackendState
}

class DiagnosticsViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val _backend = MutableStateFlow<BackendState>(BackendState.Checking)
    val backend: StateFlow<BackendState> = _backend.asStateFlow()

    val session = c.session.ui
    val connection = c.socket.state
    val roundTrip = c.socket.roundTripMs
    val camera = c.camera.status
    val audioProblem = c.player.problem
    val clips = c.clips.manifest
    val cached = c.clips.cachedCount
    val haptics = c.haptics
    val malformed: Int get() = c.socket.malformedCount

    suspend fun pollHealth() {
        while (true) {
            _backend.value = try {
                BackendState.Online(c.api.health())
            } catch (_: Exception) {
                BackendState.Offline
            }
            delay(3000)
        }
    }
}

/** 27 - Developer diagnostics: every value is measured live; nothing here is a placeholder. */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit, vm: DiagnosticsViewModel = viewModel()) {
    val c = Raahi.colors
    val context = LocalContext.current
    val backend by vm.backend.collectAsStateWithLifecycle()
    val ui by vm.session.collectAsStateWithLifecycle()
    val conn by vm.connection.collectAsStateWithLifecycle()
    val rtt by vm.roundTrip.collectAsStateWithLifecycle()
    val cam by vm.camera.collectAsStateWithLifecycle()
    val audio by vm.audioProblem.collectAsStateWithLifecycle()
    val manifest by vm.clips.collectAsStateWithLifecycle()
    val cached by vm.cached.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.pollHealth() }

    val ok = c.safe
    val warn = c.cautionText
    val bad = c.dangerText

    PaperScreen {
        TopBar("Diagnostics", onBack = onBack)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric("Round trip", if (rtt > 0) "$rtt" else "–", "ms", Modifier.weight(1f))
                Metric("Server", if (ui.serverTotalMs > 0) "${ui.serverTotalMs}" else "–", "ms", Modifier.weight(1f))
                Metric("FPS", if (ui.fps > 0) "${ui.fps}" else "–", "", Modifier.weight(1f))
            }
            RowGroup {
                val (bt, bc) = when (val b = backend) {
                    BackendState.Checking -> "Checking…" to warn
                    BackendState.Offline -> "Offline" to bad
                    is BackendState.Online -> (if (b.info.ready) "Online" else "Loading models") to (if (b.info.ready) ok else warn)
                }
                StatusRow("Backend", bt, bc)
                val (wt, wc) = when (conn) {
                    is ConnectionState.Connected -> "Open" to ok
                    ConnectionState.Connecting -> "Connecting" to warn
                    is ConnectionState.Lost -> "Lost" to warn
                    ConnectionState.ServerUnavailable -> "Unreachable" to bad
                    ConnectionState.Idle -> "Closed" to warn
                }
                StatusRow("WebSocket", wt, wc)
                val (ct, cc) = when (cam) {
                    CameraStatus.Running -> "Active" to ok
                    CameraStatus.Starting -> "Starting" to warn
                    CameraStatus.Off -> (if (context.hasPermission(CAMERA)) "Idle" else "Not allowed") to warn
                    is CameraStatus.Failed -> "Error" to bad
                }
                StatusRow("Camera", ct, cc)
                StatusRow("Microphone", if (context.hasPermission(MICROPHONE)) "Ready" else "Not allowed", if (context.hasPermission(MICROPHONE)) ok else warn)
                val total = manifest.clips.count { it.file.isNotBlank() }
                StatusRow(
                    "Audio output",
                    if (audio != null) "Problem" else if (total == 0) "No clips yet" else "OK · $cached/$total clips",
                    if (audio != null) bad else if (total == 0 || cached < total) warn else ok,
                )
                val h = vm.haptics
                StatusRow("Haptics", if (!h.isAvailable) "No vibrator" else if (h.isEnabled) "On" else "Off", if (h.isAvailable && h.isEnabled) ok else warn, divider = false)
            }
            RCard(Modifier.fillMaxWidth()) {
                val depth = (backend as? BackendState.Online)?.info?.depth?.toString()?.take(60) ?: "unknown"
                Text(
                    "mode: ${ui.serverMode.uppercase()}\nmalformed msgs: ${vm.malformed}\ndepth: $depth\nbuild: ${BuildConfig.VERSION_NAME} (${if (BuildConfig.DEBUG) "debug" else "release"})",
                    style = RT.mono.copy(fontSize = 13.sp, lineHeight = 22.sp), color = c.muted,
                )
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, unit: String, modifier: Modifier) {
    val c = Raahi.colors
    RCard(modifier, padding = 12.dp) {
        Column {
            Text(label.uppercase(), style = RT.label.copy(fontSize = 10.sp), color = c.mutedSoft, maxLines = 1)
            Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                Text(value, style = RT.mono.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp), color = c.ink)
                if (unit.isNotEmpty()) Text(unit, style = RT.mono.copy(fontSize = 12.sp), color = c.ink)
            }
        }
    }
}

@Composable
private fun StatusRow(title: String, status: String, color: Color, divider: Boolean = true) {
    InfoRow(title, minHeight = 48.dp, titleSize = 15.sp, divider = divider) {
        Text("● $status", style = RT.caption.copy(fontWeight = FontWeight.SemiBold), color = color)
    }
}
