package com.raahi.app.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.raahi.app.data.AppSettings
import com.raahi.app.data.ThemeChoice

/** Text size choices from Accessibility > Text size ("A A A A"). */
val TextScales = floatArrayOf(0.85f, 1.0f, 1.2f, 1.45f)

/** Extra UI flags that components need (reduce motion, large controls). */
data class UiPrefs(val reduceMotion: Boolean = false, val largeControls: Boolean = false)

val LocalUiPrefs = staticCompositionLocalOf { UiPrefs() }

object Raahi {
    val colors: RaahiColors
        @Composable get() = LocalRaahiColors.current
}

@Composable
fun RaahiTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.theme) {
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK -> true
        else -> isSystemInDarkTheme()
    }
    val colors = when {
        settings.highContrast -> HighContrastColors
        dark -> DarkColors
        else -> LightColors
    }
    val base = LocalDensity.current
    val density = Density(base.density, base.fontScale * TextScales[settings.textSizeIndex.coerceIn(0, 3)])
    val scheme = if (colors.isDark) {
        darkColorScheme(primary = colors.teal, onPrimary = colors.onTeal, background = colors.paper, surface = colors.surface, onSurface = colors.ink, onBackground = colors.ink, error = colors.danger)
    } else {
        lightColorScheme(primary = colors.teal, onPrimary = colors.onTeal, background = colors.paper, surface = colors.surface, onSurface = colors.ink, onBackground = colors.ink, error = colors.danger)
    }
    CompositionLocalProvider(
        LocalRaahiColors provides colors,
        LocalUiPrefs provides UiPrefs(settings.reduceMotion, settings.largeControls),
        LocalDensity provides density,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
