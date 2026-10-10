package com.raahi.app.feature.history

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.raahi.app.core.designsystem.*
import com.raahi.app.core.model.FindTarget
import com.raahi.app.core.model.Fmt
import com.raahi.app.data.Outcome
import com.raahi.app.data.SessionRecord
import com.raahi.app.data.TimelineType
import com.raahi.app.feature.guidance.SessionViewModel
import com.raahi.app.feature.guidance.container
import kotlinx.coroutines.launch

class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = app.container.historyRepo
    val sessions = repo.sessions
    suspend fun get(id: String) = repo.get(id)
    fun clear() = viewModelScope.launch { repo.clear() }
}

private fun SessionRecord.title(): String =
    if (kind == "find") "Find ${FindTarget.fromWire(target)?.label?.lowercase() ?: "object"}" else "Guidance"

private fun SessionRecord.modeLabel(): String =
    if (kind == "find") "Find ${FindTarget.fromWire(target)?.label?.lowercase() ?: "object"} mode" else "Guidance mode"

// ------------------------------------------------------------------------------------------------ 17 Session summary

@Composable
fun SessionSummaryScreen(onDone: () -> Unit, onDetails: (String) -> Unit, vm: SessionViewModel = viewModel()) {
    val c = Raahi.colors
    val rec by vm.lastFinished.collectAsStateWithLifecycle()
    PaperScreen {
        TopBar("Session ended", onBack = onDone, backIcon = "close", backLabel = "Close")
        val r = rec
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (r == null) {
                Text("No session to show.", style = RT.body, color = c.muted)
            } else {
                Column(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp)).background(c.teal).padding(22.dp)) {
                    Text("Duration", style = RT.bodyS.copy(fontWeight = FontWeight.Medium), color = c.onTeal.copy(alpha = 0.8f))
                    Text(Fmt.clock(r.durationMs), style = RT.h1.copy(fontSize = 48.sp, lineHeight = 53.sp), color = c.onTeal)
                    Text("${r.modeLabel()} · ${Fmt.dayLabel(r.startedAt)} ${Fmt.timeOfDay(r.startedAt)}", style = RT.bodyS, color = c.onTeal.copy(alpha = 0.85f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard("category", c.teal, r.objects, "Objects detected", Modifier.weight(1f))
                    StatCard("warning", c.caution, r.warnings, "Warnings", Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard("front_hand", c.danger, r.stops, "Stop alerts", Modifier.weight(1f))
                    StatCard("task_alt", c.safe, r.found, "Items found", Modifier.weight(1f))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RButton("Details", { rec?.let { onDetails(it.id) } }, Modifier.weight(1f), style = BtnStyle.Secondary, enabled = rec != null)
            RButton("Done", onDone, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatCard(icon: String, tint: Color, value: Int, label: String, modifier: Modifier) {
    val c = Raahi.colors
    RCard(modifier) {
        Column {
            Sym(icon, tint = tint)
            Text("$value", Modifier.padding(top = 8.dp), style = RT.h1, color = c.ink)
            Text(label, style = RT.bodyS, color = c.muted)
        }
    }
}

// ------------------------------------------------------------------------------------------------ 18 / 18b History

@Composable
fun HistoryScreen(
    onTab: (MainTab) -> Unit,
    onOpen: (String) -> Unit,
    onStartGuidance: () -> Unit,
    vm: HistoryViewModel = viewModel(),
) {
    val c = Raahi.colors
    val sessions by vm.sessions.collectAsStateWithLifecycle(initialValue = null)
    PaperScreen {
        TopBar("History", big = true)
        val list = sessions
        when {
            list == null -> Box(Modifier.weight(1f).fillMaxWidth())
            list.isEmpty() -> Column(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                Box(Modifier.size(120.dp).clip(CircleShape).background(c.sand), contentAlignment = Alignment.Center) {
                    Sym("history", size = 52.dp, tint = c.mutedSoft)
                }
                Text("No sessions yet", style = RT.h2.copy(fontSize = 24.sp), color = c.ink)
                Text("Your guidance sessions will appear here.", style = RT.body, color = c.muted, textAlign = TextAlign.Center)
                RButton("Start guidance", onStartGuidance, Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            else -> Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                var lastDay: String? = null
                list.forEach { r ->
                    val day = Fmt.dayLabel(r.startedAt)
                    if (day != lastDay) {
                        Label(day, Modifier.padding(top = if (lastDay == null) 0.dp else 6.dp))
                        lastDay = day
                    }
                    HistoryCard(r) { onOpen(r.id) }
                }
            }
        }
        MainNavBar(MainTab.HISTORY, onTab)
    }
}

@Composable
private fun HistoryCard(r: SessionRecord, onClick: () -> Unit) {
    val c = Raahi.colors
    RCard(Modifier.fillMaxWidth(), onClick = onClick, onClickLabel = "Open session details") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBubble(if (r.kind == "find") "search" else "navigation")
            Column(Modifier.weight(1f)) {
                Text(r.title(), style = RT.title.copy(fontSize = 17.sp), color = c.ink)
                Text("${Fmt.timeOfDay(r.startedAt)} · ${Fmt.minutes(r.durationMs)}", style = RT.bodyS, color = c.muted)
            }
            val (text, fg, bg) = when {
                r.kind == "find" && r.outcome == Outcome.FOUND -> Triple("Found", c.safe, c.safeSoft)
                r.kind == "find" && r.outcome == Outcome.NOT_FOUND -> Triple("Not found", c.dangerText, c.dangerSoft)
                r.kind == "find" -> Triple("Stopped", c.muted, c.sand)
                r.warnings > 0 -> Triple("${r.warnings} ${if (r.warnings == 1) "warning" else "warnings"}", c.cautionText, c.cautionSoft)
                else -> Triple("Completed", c.safe, c.safeSoft)
            }
            StatusPill(text, fg, bg)
        }
    }
}

// ------------------------------------------------------------------------------------------------ 19 Session details

@Composable
fun SessionDetailsScreen(id: String, onBack: () -> Unit, vm: HistoryViewModel = viewModel()) {
    val c = Raahi.colors
    val record by produceState<SessionRecord?>(null, id) { value = vm.get(id) }
    val r = record
    PaperScreen {
        TopBar(if (r == null) "Session" else if (r.kind == "find") r.title() + " session" else "Guidance session", onBack = onBack)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (r == null) {
                Text("This session is no longer on the phone.", style = RT.body, color = c.muted)
            } else {
                RCard(Modifier.fillMaxWidth(), padding = 14.dp) {
                    Row(Modifier.fillMaxWidth()) {
                        Stat(Fmt.clock(r.durationMs), "Duration", c.ink, Modifier.weight(1f))
                        Box(Modifier.width(1.dp).height(44.dp).background(c.divider))
                        Stat("${r.objects}", "Objects", c.ink, Modifier.weight(1f))
                        Box(Modifier.width(1.dp).height(44.dp).background(c.divider))
                        Stat("${r.warnings}", "Warnings", c.caution, Modifier.weight(1f))
                    }
                }
                Label("Timeline")
                Column {
                    r.timeline.forEachIndexed { i, e ->
                        val (icon, bg, fg) = when (e.type) {
                            TimelineType.START -> Triple("play_arrow", c.safeSoft, c.safe)
                            TimelineType.WARNING -> Triple("warning", c.cautionSoft, c.cautionText)
                            TimelineType.STOP -> Triple("front_hand", c.dangerSoft, c.dangerText)
                            TimelineType.FOUND -> Triple(FindTarget.fromWire(r.target)?.icon ?: "task_alt", c.tealSoft, c.teal)
                            TimelineType.LOST -> Triple("search_off", c.sand, c.muted)
                            else -> Triple("stop", c.sand, c.ink)
                        }
                        val last = i == r.timeline.lastIndex
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(32.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                                    Sym(icon, size = 18.dp, tint = fg)
                                }
                                if (!last) Box(Modifier.weight(1f).width(2.dp).background(c.border))
                            }
                            Column(Modifier.padding(bottom = 16.dp)) {
                                Text(e.text, style = RT.title.copy(fontSize = 16.sp), color = c.ink)
                                Text(Fmt.timeOfDay(e.at), style = RT.caption, color = c.muted)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, valueColor: Color, modifier: Modifier) {
    val c = Raahi.colors
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = RT.title.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold), color = valueColor)
        Text(label, style = RT.caption, color = c.muted)
    }
}
