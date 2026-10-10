package com.raahi.app.feature.guidance

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.raahi.app.core.model.FindTarget
import com.raahi.app.core.navigation.ErrorKind
import com.raahi.app.core.permissions.CAMERA
import com.raahi.app.core.permissions.rememberPermissionState
import com.raahi.app.domain.SessionKind

/**
 * Returns `start(kind, target)`: asks for the camera permission when needed, connects to the server and begins
 * the session, then reports either [onStarted] or the matching error screen ([onError]).
 */
@Composable
fun rememberSessionStarter(
    vm: SessionViewModel,
    onStarted: (SessionKind) -> Unit,
    onError: (ErrorKind) -> Unit,
): (SessionKind, FindTarget?) -> Unit {
    var pending by remember { mutableStateOf<Pair<SessionKind, FindTarget?>?>(null) }

    fun go(kind: SessionKind, target: FindTarget?) {
        vm.start(kind, target) { result ->
            when (result) {
                StartResult.OK -> onStarted(kind)
                StartResult.SERVER_UNAVAILABLE -> onError(ErrorKind.SERVER_UNAVAILABLE)
                StartResult.OFFLINE -> onError(ErrorKind.CONNECTION)
                StartResult.FAILED -> onError(ErrorKind.GENERIC)
            }
        }
    }

    val camera = rememberPermissionState(CAMERA) { granted ->
        val p = pending
        pending = null
        if (p != null) {
            if (granted) go(p.first, p.second) else onError(ErrorKind.CAMERA_PERMISSION)
        }
    }

    return { kind, target ->
        if (camera.granted) {
            go(kind, target)
        } else if (camera.permanentlyDenied) {
            onError(ErrorKind.CAMERA_PERMISSION)
        } else {
            pending = kind to target
            camera.request()
        }
    }
}
