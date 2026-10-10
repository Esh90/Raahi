package com.raahi.app.feature.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.raahi.app.core.model.ConnectionState
import com.raahi.app.core.model.HealthInfo
import com.raahi.app.core.network.ServerConfig
import com.raahi.app.data.AppSettings
import com.raahi.app.feature.guidance.container
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ConnectionTest {
    data object Idle : ConnectionTest
    data object Running : ConnectionTest
    data class Ok(val info: HealthInfo) : ConnectionTest
    data class Failed(val message: String) : ConnectionTest
}

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val repo = c.settingsRepo

    val settings: StateFlow<AppSettings?> = c.settings
    val connection: StateFlow<ConnectionState> = c.socket.state
    val roundTripMs = c.socket.roundTripMs

    private val _test = MutableStateFlow<ConnectionTest>(ConnectionTest.Idle)
    val test: StateFlow<ConnectionTest> = _test.asStateFlow()

    private fun launch(block: suspend () -> Unit) { viewModelScope.launch { block() } }

    fun setTextSize(i: Int) = launch { repo.setTextSize(i) }
    fun setHighContrast(v: Boolean) = launch { repo.setHighContrast(v) }
    fun setReduceMotion(v: Boolean) = launch { repo.setReduceMotion(v) }
    fun setHaptics(v: Boolean) = launch { repo.setHaptics(v) }
    fun setLargeControls(v: Boolean) = launch { repo.setLargeControls(v) }
    fun setLanguage(l: String) = launch { repo.setLanguage(l) }
    fun setUrduVoice(v: Boolean) = launch { repo.setUrduVoice(v) }
    fun setEnglishSubtitles(v: Boolean) = launch { repo.setEnglishSubtitles(v) }
    fun setVolume(v: Float) = launch { repo.setVolume(v) }
    fun setSpeed(v: Int) = launch { repo.setSpeed(v) }
    fun setOverlay(v: Boolean) = launch { repo.setDetectionOverlay(v) }
    fun setKeepAwake(v: Boolean) = launch { repo.setKeepAwake(v) }
    fun setDisplay(v: Int) = launch { repo.setGuidanceDisplay(v) }
    fun setTheme(v: Int) = launch { repo.setTheme(v) }
    fun setHandsFreeVoice(v: Boolean) = launch { repo.setHandsFreeVoice(v) }
    fun setOnboardingDone() = launch { repo.setOnboardingDone(true); c.connectIfReady() }

    /** Plays one real clip from the server's library ("Walk straight"), in the selected voice language. */
    fun playSample() {
        c.player.clearProblem()
        c.player.play(listOf("go_straight"), interrupt = true, remember = false)
    }
    val audioProblem = c.player.problem

    /** Saves the URL (if valid) and asks the server's `/health`. */
    fun saveAndTest(url: String) {
        val parsed = ServerConfig.parse(url)
        if (parsed == null) {
            _test.value = ConnectionTest.Failed("Enter a valid address, e.g. http://192.168.1.20:8000 or https://raahi.example.com")
            return
        }
        viewModelScope.launch {
            repo.setServerUrl(url)
            _test.value = ConnectionTest.Running
            _test.value = try {
                // settings collector updates serverConfig asynchronously; wait until it did
                var tries = 0
                while (c.serverConfig != parsed && tries < 20) { kotlinx.coroutines.delay(50); tries++ }
                ConnectionTest.Ok(c.api.health())
            } catch (e: Exception) {
                ConnectionTest.Failed(e.message ?: "Server unreachable")
            }
        }
    }

    fun clearTest() { _test.value = ConnectionTest.Idle }
    fun reconnect() = c.socket.reconnectNow()
}
