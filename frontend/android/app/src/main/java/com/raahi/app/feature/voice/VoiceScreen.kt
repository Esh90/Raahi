package com.raahi.app.feature.voice

import android.app.Application
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.raahi.app.core.designsystem.*
import com.raahi.app.core.permissions.MICROPHONE
import com.raahi.app.core.permissions.rememberPermissionState
import com.raahi.app.core.voice.VoiceFailure
import com.raahi.app.core.voice.VoiceState
import com.raahi.app.domain.SessionKind
import com.raahi.app.core.navigation.ErrorKind
import com.raahi.app.feature.guidance.SessionViewModel
import com.raahi.app.feature.guidance.container
import com.raahi.app.feature.guidance.rememberSessionStarter
import kotlinx.coroutines.delay

class VoiceViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    val state = c.voice.state
    val level = c.voice.level
    val ui = c.session.ui
    val connection = c.session.connection
    fun start(autoSend: Boolean) = c.voice.startListening(autoSend)
    fun confirm() = c.voice.confirm()
    fun cancel() = c.voice.cancel()
    fun connectSocket() = c.socket.connect()
    fun setSentenceHandler(h: ((String) -> Boolean)?) { c.voice.sentenceHandler = h }
    val sessionActive: Boolean get() = c.session.isActive
    fun sendCommandText(text: String) = c.session.sendCommandText(text)
}

private fun hasArabicScript(s: String) = s.any { it in '؀'..'ۿ' || it in 'ݐ'..'ݿ' }

/**
 * 11 - Voice command content, shared by the Voice screen and the in-guidance overlay (so the camera keeps
 * streaming while the user talks). [autoSend] = hands-free: the sentence goes to the server as soon as Scribe
 * has recognised it; otherwise the user presses Confirm.
 */
@Composable
fun VoiceCommandContent(
    autoSend: Boolean,
    onClose: () -> Unit,
    onMicDenied: () -> Unit,
    modifier: Modifier = Modifier,
    vm: VoiceViewModel = viewModel(),
) {
    val c = Raahi.colors
    val state by vm.state.collectAsStateWithLifecycle()
    val level by vm.level.collectAsStateWithLifecycle()
    val session by vm.ui.collectAsStateWithLifecycle()
    val reduce = LocalUiPrefs.current.reduceMotion
    val mic = rememberPermissionState(MICROPHONE) { granted -> if (granted) vm.start(autoSend) else onMicDenied() }

    LaunchedEffect(Unit) {
        vm.connectSocket()
        if (mic.granted) vm.start(autoSend) else mic.request()
    }
    DisposableEffect(Unit) { onDispose { vm.cancel() } }

    // after a hands-free command was delivered, close shortly after the server reply has been shown
    val sent = (state as? VoiceState.Heard)?.sent == true
    LaunchedEffect(sent) {
        if (sent && autoSend) {
            delay(2200)
            onClose()
        }
    }

    val (eyebrow, line, hint) = when (val st = state) {
        VoiceState.Idle, VoiceState.Connecting -> Triple("Getting ready", "…", "")
        is VoiceState.Listening -> Triple("Listening", st.partial.ifBlank { "Say a command" }, "e.g. “Settings kholo”, “Start detecting” or “Dark mode”")
        is VoiceState.Heard ->
            if (st.sent) Triple("Done", st.text, session.subtitle.ifBlank { "Raahi is on it" })
            else Triple("Did I hear that right?", st.text, "Press Confirm to send")
        is VoiceState.Failed -> when (st.reason) {
            VoiceFailure.PERMISSION_DENIED -> Triple("Microphone off", "Microphone access is needed", "Allow it in Android settings")
            VoiceFailure.OFFLINE -> Triple("Voice unavailable", "Can't reach the voice service", "Use buttons and gestures instead")
            VoiceFailure.MIC_UNAVAILABLE -> Triple("Microphone busy", "Another app is using the microphone", "Close it and try again")
            VoiceFailure.NO_SPEECH -> Triple("Didn't catch that", "Nothing heard", "Tap Try again and speak clearly")
            VoiceFailure.NOT_CONNECTED -> Triple("Not connected", "Can't reach the Raahi server", "Check the connection and try again")
        }
    }
    val failed = state is VoiceState.Failed
    val pulse by animateFloatAsState(if (reduce) 1f else 1f + level * 0.14f, label = "mic-pulse")
    val tealRing = c.tealRing
    val tealMid = c.tealMid

    Column(modifier.fillMaxSize().background(c.paper)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Label(eyebrow, color = if (failed) c.cautionText else c.teal, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Box(Modifier.size(260.dp).clip(CircleShape).background(tealRing), contentAlignment = Alignment.Center) {
                Box(Modifier.size(196.dp).scale(pulse).clip(CircleShape).background(tealMid), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(132.dp).clip(CircleShape).background(if (failed) c.cautionText else c.teal), contentAlignment = Alignment.Center) {
                        Sym(if (failed) "mic_off" else "mic", size = 64.dp, tint = c.onTeal, filled = true)
                    }
                }
            }
            Waveform(level = if (state is VoiceState.Listening) level else 0f)
            val urdu = hasArabicScript(line)
            Text(
                if (line == "…" || !urdu && state is VoiceState.Failed) line else if (urdu) line else "“$line”",
                style = if (urdu) RT.urdu.copy(fontSize = 28.sp, lineHeight = 48.sp) else RT.h1,
                color = c.ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (hint.isNotBlank()) Text(hint, style = RT.body.copy(fontSize = 15.sp), color = c.muted, textAlign = TextAlign.Center)
        }
        val heardUnsent = (state as? VoiceState.Heard)?.sent == false
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 32.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RButton("Cancel", { vm.cancel(); onClose() }, Modifier.weight(1f), style = BtnStyle.Secondary, height = 64.dp)
            when {
                failed -> RButton("Try again", { vm.cancel(); if (mic.granted) vm.start(autoSend) else mic.request() }, Modifier.weight(1f), icon = "refresh", height = 64.dp)
                else -> RButton("Confirm", { vm.confirm() }, Modifier.weight(1f), icon = "check", height = 64.dp, enabled = heardUnsent)
            }
        }
    }
}

@Composable
private fun Waveform(level: Float) {
    val c = Raahi.colors
    val base = listOf(12, 24, 32, 18, 28, 14, 22)
    Row(Modifier.height(32.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        base.forEach { h ->
            val target = (6 + (h - 6) * (0.25f + level * 1.2f)).coerceIn(6f, 32f)
            val animated by animateFloatAsState(target, label = "bar")
            Box(Modifier.width(5.dp).height(animated.dp).clip(RoundedCornerShape(3.dp)).background(c.teal))
        }
    }
}

/**
 * 11 - Voice command screen (reached from Home / Find / Quick commands).
 * The sentence is an app command handled on the phone (core/voice/VoiceCommands + CommandDispatcher):
 * "Settings kholo", "Dark mode", "Start detecting", "English mein bolo", "Saamne kya hai" ... It runs as soon as it
 * is recognised (no Confirm step - a blind user shouldn't need to find a button); navigation commands replace this
 * screen, other commands close it after the spoken "Okay".
 */
@Composable
fun VoiceCommandScreen(
    onStarted: (SessionKind) -> Unit,
    onError: (ErrorKind) -> Unit,
    onClose: () -> Unit,
    onMicDenied: () -> Unit,
    voiceVm: VoiceViewModel = viewModel(),
) {
    // the app-wide command handler (AppContainer) is used; clear any handler a previous screen left behind
    DisposableEffect(Unit) {
        voiceVm.setSentenceHandler(null)
        onDispose { }
    }
    PaperScreen {
        VoiceCommandContent(autoSend = true, onClose = onClose, onMicDenied = onMicDenied)
    }
}
