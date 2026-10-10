package com.raahi.app

import android.app.Application
import com.raahi.app.core.audio.ClipPlayer
import com.raahi.app.core.audio.ClipRepository
import com.raahi.app.core.camera.CameraController
import com.raahi.app.core.camera.UprightMonitor
import com.raahi.app.core.haptics.Haptics
import com.raahi.app.core.model.ConnectionState
import com.raahi.app.core.network.GuidanceSocket
import com.raahi.app.core.network.RaahiApi
import com.raahi.app.core.network.ServerConfig
import android.util.Log
import com.raahi.app.core.voice.CommandDispatcher
import com.raahi.app.core.voice.HandsFreeListener
import com.raahi.app.core.voice.MicStreamer
import com.raahi.app.core.voice.ScribeClient
import com.raahi.app.core.voice.VoiceCommands
import com.raahi.app.core.voice.VoiceController
import com.raahi.app.core.voice.VoiceState
import com.raahi.app.data.AppSettings
import com.raahi.app.data.HistoryRepository
import com.raahi.app.data.SettingsRepository
import com.raahi.app.data.SpeechSpeed
import com.raahi.app.domain.GuidanceSession
import com.raahi.app.domain.SessionPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Hand-wired dependency graph (small app; keeps everything in one obvious place). */
class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settingsRepo = SettingsRepository(app)
    val historyRepo = HistoryRepository(app)

    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile var serverConfig: ServerConfig? = null
        private set

    val api = RaahiApi(okHttp) { serverConfig }
    val clips = ClipRepository(app, api)
    val player = ClipPlayer(app, clips, scope)
    val haptics = Haptics(app)
    val camera = CameraController(app)
    val upright = UprightMonitor(app)
    val socket = GuidanceSocket(okHttp, scope, config = { serverConfig })
    val session = GuidanceSession(scope, socket, player, haptics, camera, upright, historyRepo, { clips.manifest.value })
    val voice = VoiceController(
        app, api, ScribeClient(okHttp), MicStreamer(scope), player, scope,
        sendText = { socket.sendText(it) },
        languageCode = { voiceLanguage() },
    )

    /** null until DataStore delivered the first value (so the UI never flashes onboarding by mistake). */
    private val _settings = MutableStateFlow<AppSettings?>(null)
    val settings: StateFlow<AppSettings?> = _settings.asStateFlow()

    /** On-device voice command grammar (assets/voice_commands.json); null only if the asset is broken. */
    val voiceCommands: VoiceCommands? = try {
        VoiceCommands(app.assets.open("voice_commands.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
    } catch (e: Exception) {
        Log.w("AppContainer", "voice_commands.json unreadable: ${e.message}")
        null
    }

    /** Executes voice commands (navigation requests are collected by RaahiNavHost). */
    val commands = CommandDispatcher(scope, settingsRepo, settings, session, player)

    /** "Raahi, <command>" while the app is open (Settings > Voice & audio > Hands-free). */
    val handsFree = HandsFreeListener(
        app, api, ScribeClient(okHttp), MicStreamer(scope), player,
        commands = { voiceCommands }, dispatcher = commands, scope = scope,
        languageCode = { voiceLanguage() },
    )

    private fun voiceLanguage(): String = if (_settings.value?.language == "en") "en" else "ur"

    private var inForeground = false
    private var prefetched = false

    init {
        // push-to-talk sentences are voice commands handled on the phone (no wake word needed)
        voice.commandHandler = { text ->
            val grammar = voiceCommands
            if (grammar == null) {
                socket.sendText(com.raahi.app.core.network.Protocol.commandText(text, wakeRequired = false))
            } else {
                val id = grammar.match(text, requireWake = false) ?: VoiceCommands.UNKNOWN
                if (id == VoiceCommands.WAKE_ONLY) {
                    player.play(listOf("sys_listening"), interrupt = true, remember = false)
                } else {
                    commands.execute(id)
                }
                true
            }
        }
        // one microphone: hands-free pauses while push-to-talk is listening
        scope.launch {
            voice.state.collect { st ->
                if (st is VoiceState.Connecting || st is VoiceState.Listening) handsFree.stop() else updateHandsFree()
            }
        }
        scope.launch {
            settingsRepo.settings.collect { s ->
                val newCfg = ServerConfig.parse(s.serverUrl)
                val changed = newCfg != serverConfig
                serverConfig = newCfg
                val languageChanged = _settings.value?.language.let { it != null && it != s.language }
                player.setVolume(s.volume)
                player.setSpeed(SpeechSpeed.factor(s.speed))
                player.setEnabled(s.urduVoice)
                clips.language = s.language
                if (languageChanged) player.stopAll()
                haptics.setEnabled(s.haptics)
                _settings.value = s
                if (languageChanged) handsFree.restart()
                updateHandsFree()
                if (inForeground && s.onboardingDone && newCfg != null) {
                    if (changed) socket.reconnectNow() else socket.connect()
                }
            }
        }
        // after the first successful connection, make sure the Urdu clips are on the phone
        scope.launch {
            socket.state.collect { st ->
                if (st is ConnectionState.Connected && !prefetched) {
                    prefetched = true
                    val ok = clips.refreshManifest()
                    if (ok) clips.prefetchAll() else prefetched = false
                }
            }
        }
    }

    /** Hands-free listens only while the app is visible, onboarding is done, it is enabled and push-to-talk is idle. */
    private fun updateHandsFree() {
        val s = _settings.value
        val pttBusy = voice.state.value.let { it is VoiceState.Connecting || it is VoiceState.Listening }
        if (inForeground && s != null && s.onboardingDone && s.handsFreeVoice && !pttBusy) handsFree.start() else handsFree.stop()
    }

    fun onForeground() {
        inForeground = true
        val s = _settings.value
        if (s != null && s.onboardingDone && serverConfig != null) socket.connect()
        updateHandsFree()
    }

    fun onBackground() {
        inForeground = false
        handsFree.stop()
        if (session.ui.value.phase == SessionPhase.ACTIVE) session.pause() // the camera is released with the screen
        voice.cancel()
        if (!session.isActive) socket.disconnect()
    }

    /** Called when onboarding finishes so the connection starts without waiting for the next onStart. */
    fun connectIfReady() {
        if (inForeground && serverConfig != null) socket.connect()
    }
}
