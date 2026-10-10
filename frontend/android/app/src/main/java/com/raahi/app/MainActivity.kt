package com.raahi.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.raahi.app.core.designsystem.RaahiTheme
import com.raahi.app.core.navigation.RaahiNavHost

class MainActivity : ComponentActivity() {
    private val container get() = (application as RaahiApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { RaahiRoot(container) }
    }

    override fun onStart() {
        super.onStart()
        container.onForeground()
    }

    override fun onStop() {
        container.onBackground()
        super.onStop()
    }
}

@Composable
fun RaahiRoot(container: AppContainer) {
    val settings by container.settings.collectAsStateWithLifecycle()
    val s = settings
    if (s == null) {
        // DataStore has not delivered the first value yet: show the splash colour, never a wrong screen
        Box(Modifier.fillMaxSize().background(Color(0xFF0E5E63)))
        return
    }
    RaahiTheme(s) { RaahiNavHost(s) }
}
