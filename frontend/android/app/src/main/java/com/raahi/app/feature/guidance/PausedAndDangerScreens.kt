package com.raahi.app.feature.guidance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raahi.app.core.designsystem.*
import com.raahi.app.core.model.Fmt

/** 14 - Guidance paused. Swiping left or "Resume" continues; camera and streaming are released while shown. */
@Composable
fun PausedScreen(pausedAt: Long, onResume: () -> Unit, onStop: () -> Unit) {
    val c = Raahi.colors
    val elapsed by rememberElapsed(pausedAt)
    var drag by remember { mutableFloatStateOf(0f) }
    PaperScreen(
        Modifier.pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { drag = 0f },
                onDragEnd = { if (drag < -120f) onResume() },
            ) { _, amount -> drag += amount }
        }
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        ) {
            Box(Modifier.size(168.dp).clip(CircleShape).background(c.cautionSoft), contentAlignment = Alignment.Center) {
                Box(Modifier.size(112.dp).clip(CircleShape).background(c.caution), contentAlignment = Alignment.Center) {
                    Sym("pause", size = 60.dp, tint = if (c.highContrast) Color.Black else Color.White, filled = true)
                }
            }
            Text("Guidance paused", style = RT.h1, color = c.ink, textAlign = TextAlign.Center, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Text("Obstacle alerts are off. Swipe left or tap Resume to continue.", style = RT.body, color = c.muted, textAlign = TextAlign.Center)
            Chip("Paused for ${Fmt.clock(elapsed)}")
        }
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RButton("Resume", onResume, Modifier.fillMaxWidth(), icon = "play_arrow", filledIcon = true, height = 64.dp)
            RButton("Stop session", onStop, Modifier.fillMaxWidth(), style = BtnStyle.DangerOutline, icon = "stop", filledIcon = true, height = 64.dp)
        }
    }
}

/** 15 - Danger alert, drawn over the live guidance screen so the camera keeps streaming underneath. */
@Composable
fun DangerScreen(
    serverEnglish: String,
    urdu: String,
    audioPlaying: Boolean,
    vibrating: Boolean,
    onDismiss: () -> Unit,
) {
    val red = Color(0xFFB3261E)
    ScreenBox(red, Modifier.pointerInput(Unit) { detectTapGestures { } }) { // swallow touches meant for the layer below
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterVertically),
        ) {
            Box(Modifier.size(200.dp).border(2.dp, Color.White.copy(alpha = 0.3f), CircleShape), contentAlignment = Alignment.Center) {
                Box(Modifier.size(150.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Sym("front_hand", size = 84.dp, tint = red, filled = true)
                }
            }
            Text(
                "STOP", style = RT.display, color = Color.White, textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
            Text("Obstacle very close", style = RT.title.copy(fontSize = 22.sp, lineHeight = 29.sp), color = Color.White, textAlign = TextAlign.Center)
            if (serverEnglish.isNotBlank()) Text(serverEnglish, style = RT.body, color = Color.White.copy(alpha = 0.9f), textAlign = TextAlign.Center)
            if (urdu.isNotBlank()) Text(urdu, style = RT.urdu, color = Color.White, textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AlertChip("volume_up", if (audioPlaying) "Alarm" else "Alarm ready")
                AlertChip("vibration", if (vibrating) "Vibrating" else "Vibration off")
            }
        }
        Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
            RButton("I've stopped", onDismiss, Modifier.fillMaxWidth(), style = BtnStyle.OnDanger, height = 64.dp)
        }
    }
}

@Composable
private fun AlertChip(icon: String, text: String) {
    Row(
        Modifier.clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.18f)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Sym(icon, size = 20.dp, tint = Color.White, filled = icon == "volume_up")
        Text(text, style = RT.bodyS.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
    }
}
