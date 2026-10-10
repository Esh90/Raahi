package com.raahi.app.feature.guidance

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.raahi.app.AppContainer
import com.raahi.app.RaahiApp
import com.raahi.app.core.model.FindTarget
import com.raahi.app.core.permissions.NetworkStatus
import com.raahi.app.data.SessionRecord
import com.raahi.app.domain.SessionKind
import kotlinx.coroutines.launch

enum class StartResult { OK, SERVER_UNAVAILABLE, OFFLINE, FAILED }

val Application.container: AppContainer get() = (this as RaahiApp).container

/** Thin view-model over the app-wide [com.raahi.app.domain.GuidanceSession]: one place the screens talk to. */
class SessionViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    val session = c.session
    val ui = c.session.ui
    val connection = c.session.connection
    val roundTripMs = c.session.roundTripMs
    val settings = c.settings
    val manifest = c.clips.manifest
    val audioProblem = c.player.problem
    val audioPlaying = c.player.isPlaying
    val cameraController = c.camera
    val cameraStatus = c.camera.status
    val lastFinished = c.session.lastFinished
    val events = c.session.events
    val haptics = c.haptics

    fun start(kind: SessionKind, target: FindTarget? = null, onResult: (StartResult) -> Unit) {
        viewModelScope.launch {
            val result = try {
                val ok = session.begin(kind, target)
                when {
                    ok -> StartResult.OK
                    !NetworkStatus.isOnline(getApplication()) -> StartResult.OFFLINE
                    else -> StartResult.SERVER_UNAVAILABLE
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                StartResult.FAILED
            }
            onResult(result)
        }
    }

    fun end(): SessionRecord? = session.end()

    fun reconnect() = c.socket.reconnectNow()
    fun testAudio() {
        c.player.clearProblem()
        c.player.play(listOf("sys_ok"), interrupt = true, remember = false)
    }
}
