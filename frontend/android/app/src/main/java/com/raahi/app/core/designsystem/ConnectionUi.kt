package com.raahi.app.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raahi.app.core.model.ConnectionState

/** Short status for the header pill: label + dot colour. */
@Composable
fun connectionPill(state: ConnectionState): Pair<String, Color> {
    val c = Raahi.colors
    return when (state) {
        is ConnectionState.Connected -> "Connected" to c.safe
        ConnectionState.Connecting -> "Connecting…" to c.caution
        ConnectionState.Idle -> "Not connected" to c.mutedSoft
        is ConnectionState.Lost -> "Connection lost" to c.caution
        ConnectionState.ServerUnavailable -> "Server unavailable" to c.danger
    }
}

@Composable
fun ConnectionPill(state: ConnectionState, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val (text, dot) = connectionPill(state)
    Chip(
        text,
        modifier
            .semantics { contentDescription = "Server status: $text" }
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        dot = dot,
        height = 32.dp,
        textStyle = RT.caption.copy(fontWeight = FontWeight.Medium),
    )
}

/** The dark "banner variant" from the Connection states design. Shown whenever the server link is down. */
@Composable
fun ConnectionBanner(state: ConnectionState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    if (state is ConnectionState.Connected || state == ConnectionState.Idle) return
    val c = Raahi.colors
    val message = when (state) {
        ConnectionState.Connecting -> "Connecting to Raahi server…"
        is ConnectionState.Lost -> "Connection lost · retrying in ${state.retryInSec} s"
        else -> "Server unavailable"
    }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (c.isDark && !c.highContrast) Color(0xFF232B38) else Color(0xFF14213D))
            .padding(horizontal = 16.dp, vertical = 4.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Sym(if (state == ConnectionState.ServerUnavailable) "cloud_off" else "wifi_off", tint = Color(0xFFE3B65C))
        Text(message, Modifier.weight(1f), style = RT.body.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium), color = Color.White)
        Box(
            Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onRetry).heightIn(min = 48.dp).padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Retry", style = RT.body.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), color = Color(0xFF9ED3CD))
        }
    }
}
