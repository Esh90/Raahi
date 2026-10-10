package com.raahi.app.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.raahi.app.core.designsystem.*
import com.raahi.app.core.navigation.ErrorKind
import com.raahi.app.data.AppSettings
import com.raahi.app.domain.SessionKind
import com.raahi.app.feature.guidance.SessionViewModel
import com.raahi.app.feature.guidance.rememberSessionStarter

/** 08 - Home. */
@Composable
fun HomeScreen(
    onStarted: (SessionKind) -> Unit,
    onError: (ErrorKind) -> Unit,
    onFind: () -> Unit,
    onWhatsAhead: () -> Unit,
    onQuickCommands: () -> Unit,
    onVoice: () -> Unit,
    onConnection: () -> Unit,
    onTab: (MainTab) -> Unit,
    vm: SessionViewModel = viewModel(),
) {
    val c = Raahi.colors
    val conn by vm.connection.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val s = settings ?: AppSettings()
    val start = rememberSessionStarter(vm, onStarted, onError)

    PaperScreen(
        Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { onVoice() }) } // "Hold anywhere to talk"
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LogoTile(36.dp, corner = 11.dp)
            }
            ConnectionPill(conn, onClick = onConnection)
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConnectionBanner(conn, onRetry = vm::reconnect)

            // Start guidance hero
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.teal)
                    .clickable(role = Role.Button, onClickLabel = "Start guidance") { start(SessionKind.GUIDANCE, null) }
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text("Salaam, ready when you are", style = RT.bodyS.copy(fontWeight = FontWeight.Medium), color = c.onTeal.copy(alpha = 0.8f))
                        Text("Start guidance", Modifier.padding(top = 6.dp), style = RT.h1.copy(fontSize = 28.sp, lineHeight = 32.sp), color = c.onTeal)
                    }
                    Box(Modifier.size(56.dp).clip(CircleShape).background(if (c.highContrast) Color.Black else c.paper), contentAlignment = Alignment.Center) {
                        Sym("play_arrow", size = 32.dp, tint = c.teal, filled = true)
                    }
                }
                val voice = if (s.urduVoice) "Urdu voice" else "Subtitles only"
                val haptics = if (s.haptics) "on" else "off"
                Text("Obstacle alerts · $voice · Haptics $haptics", style = RT.bodyS, color = c.onTeal.copy(alpha = 0.85f))
            }

            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HomeTile("search", "Find something", onFind, Modifier.weight(1f))
                HomeTile("visibility", "What's ahead?", onWhatsAhead, Modifier.weight(1f))
            }

            RCard(Modifier.fillMaxWidth(), onClick = onQuickCommands, onClickLabel = "Quick commands") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    IconBubble("bolt", bg = c.sand, fg = c.ink)
                    Column(Modifier.weight(1f)) {
                        Text("Quick commands", style = RT.title.copy(fontSize = 17.sp), color = c.ink)
                        Text("Find door, pause, repeat…", style = RT.bodyS, color = c.muted)
                    }
                    Sym("chevron_right", tint = c.mutedSoft)
                }
            }

            RCard(Modifier.fillMaxWidth(), onClick = onVoice, onClickLabel = "Talk to Raahi") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    IconBubble("mic", bg = c.sand, fg = c.ink)
                    Column(Modifier.weight(1f)) {
                        Text("Hold anywhere to talk", style = RT.title.copy(fontSize = 17.sp), color = c.ink)
                        Text("or say “Raahi, settings” · “Raahi, start detecting”", style = RT.bodyS, color = c.muted)
                    }
                }
            }
        }
        MainNavBar(MainTab.HOME, onTab)
    }
}

@Composable
private fun HomeTile(icon: String, title: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Raahi.colors
    RCard(modifier.heightIn(min = 108.dp), onClick = onClick, onClickLabel = title) {
        Column(Modifier.fillMaxWidth().heightIn(min = 76.dp), verticalArrangement = Arrangement.SpaceBetween) {
            IconBubble(icon)
            Text(title, Modifier.padding(top = 22.dp), style = RT.title.copy(fontSize = 17.sp), color = c.ink)
        }
    }
}
