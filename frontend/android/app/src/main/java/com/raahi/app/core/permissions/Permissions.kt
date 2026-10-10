package com.raahi.app.core.permissions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

const val CAMERA = Manifest.permission.CAMERA
const val MICROPHONE = Manifest.permission.RECORD_AUDIO

fun Context.hasPermission(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

/** Opens this app's page in Android Settings (recovery for permanently denied permissions). */
fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

fun Context.activityOrNull(): Activity? {
    var c: Context = this
    while (c is android.content.ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

object NetworkStatus {
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}

@Stable
class PermissionState(
    private val context: Context,
    val permission: String,
    private val launch: () -> Unit,
) {
    var granted by mutableStateOf(context.hasPermission(permission))
        internal set

    /** True once the system dialog has been shown and the user said no. */
    var denied by mutableStateOf(false)
        internal set

    /** Denied and Android will no longer show the dialog: only system Settings can fix it. */
    val permanentlyDenied: Boolean
        get() {
            val a = context.activityOrNull()
            return denied && !granted && a != null && !ActivityCompat.shouldShowRequestPermissionRationale(a, permission)
        }

    fun refresh() { granted = context.hasPermission(permission) }
    fun request() { if (!granted) launch() }
}

/** Compose permission helper. [onResult] runs after the system dialog closes. */
@Composable
fun rememberPermissionState(permission: String, onResult: (Boolean) -> Unit = {}): PermissionState {
    val context = LocalContext.current
    var stateRef: PermissionState? = null
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        stateRef?.let {
            it.granted = ok
            it.denied = !ok
        }
        onResult(ok)
    }
    val state = remember(permission) { PermissionState(context, permission) { launcher.launch(permission) } }
    stateRef = state
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state.refresh() }
    return state
}
