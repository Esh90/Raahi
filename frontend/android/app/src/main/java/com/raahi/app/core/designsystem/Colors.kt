package com.raahi.app.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Raahi palette from the approved design system (Paper / Surface / Ink / Teal / Safe / Caution / Danger). */
@Immutable
class RaahiColors(
    val isDark: Boolean,
    val highContrast: Boolean,
    val paper: Color,
    val surface: Color,
    val ink: Color,
    val muted: Color,
    val mutedSoft: Color,
    val teal: Color,
    val onTeal: Color,
    val tealSoft: Color,
    val tealMid: Color,
    val tealRing: Color,
    val safe: Color,
    val safeSoft: Color,
    val safeCard: Color,
    val safeBorder: Color,
    val caution: Color,
    val cautionText: Color,
    val cautionSoft: Color,
    val cautionCard: Color,
    val cautionBorder: Color,
    val danger: Color,
    val dangerText: Color,
    val dangerSoft: Color,
    val dangerCard: Color,
    val dangerBorder: Color,
    val border: Color,
    val borderStrong: Color,
    val divider: Color,
    val sand: Color,
    val toggleOff: Color,
    val toggleOffKnob: Color,
    val chevron: Color,
    val navy: Color,
    val gold: Color,
    val camStripeA: Color,
    val camStripeB: Color,
)

val LightColors = RaahiColors(
    isDark = false, highContrast = false,
    paper = Color(0xFFF7F4EE), surface = Color(0xFFFFFDF9), ink = Color(0xFF14213D),
    muted = Color(0xFF4A5368), mutedSoft = Color(0xFF5B6478),
    teal = Color(0xFF0E5E63), onTeal = Color(0xFFFFFFFF), tealSoft = Color(0xFFE2EEEC), tealMid = Color(0xFFD6E8E5),
    tealRing = Color(0xFFEEF5F4),
    safe = Color(0xFF3F7D58), safeSoft = Color(0xFFE3F0E7), safeCard = Color(0xFFEEF5F0), safeBorder = Color(0xFFC8DDCF),
    caution = Color(0xFFB7791F), cautionText = Color(0xFF8A5A12), cautionSoft = Color(0xFFF6E8CC),
    cautionCard = Color(0xFFFDF8EE), cautionBorder = Color(0xFFE7C98F),
    danger = Color(0xFFB3261E), dangerText = Color(0xFFB3261E), dangerSoft = Color(0xFFFBEAE8),
    dangerCard = Color(0xFFFBEFED), dangerBorder = Color(0xFFEFC3BE),
    border = Color(0xFFE6E0D5), borderStrong = Color(0xFFC9C1B3), divider = Color(0xFFEEE9E0), sand = Color(0xFFEFEAE0),
    toggleOff = Color(0xFFD8D2C6), toggleOffKnob = Color(0xFF7A8193), chevron = Color(0xFF7A8193),
    navy = Color(0xFF14213D), gold = Color(0xFFE3B65C),
    camStripeA = Color(0xFFC9C3B6), camStripeB = Color(0xFFC2BCAE),
)

val DarkColors = RaahiColors(
    isDark = true, highContrast = false,
    paper = Color(0xFF11161F), surface = Color(0xFF1A212D), ink = Color(0xFFECEAE4),
    muted = Color(0xFFB4BAC8), mutedSoft = Color(0xFF9AA2B3),
    teal = Color(0xFF5FB3AA), onTeal = Color(0xFF11161F), tealSoft = Color(0xFF1F3534), tealMid = Color(0xFF284846),
    tealRing = Color(0xFF182A2B),
    safe = Color(0xFF7CC59A), safeSoft = Color(0xFF1E3226), safeCard = Color(0xFF1B2B22), safeBorder = Color(0xFF2E4A3A),
    caution = Color(0xFFE3B65C), cautionText = Color(0xFFE3B65C), cautionSoft = Color(0xFF3A3020),
    cautionCard = Color(0xFF2A2518), cautionBorder = Color(0xFF5A4A28),
    danger = Color(0xFFD9473E), dangerText = Color(0xFFF2A49C), dangerSoft = Color(0xFF3A1C1C),
    dangerCard = Color(0xFF2E1A1A), dangerBorder = Color(0xFF5A2B2B),
    border = Color(0xFF2A3342), borderStrong = Color(0xFF3A4457), divider = Color(0xFF232B38), sand = Color(0xFF222A37),
    toggleOff = Color(0xFF3A4457), toggleOffKnob = Color(0xFF9AA2B3), chevron = Color(0xFF9AA2B3),
    navy = Color(0xFF0B0F17), gold = Color(0xFFE3B65C),
    camStripeA = Color(0xFF2A3342), camStripeB = Color(0xFF252D3B),
)

/** High contrast: black, white and signal yellow with strong borders (preview card in the Appearance screen). */
val HighContrastColors = RaahiColors(
    isDark = true, highContrast = true,
    paper = Color(0xFF000000), surface = Color(0xFF0A0A0A), ink = Color(0xFFFFFFFF),
    muted = Color(0xFFFFFFFF), mutedSoft = Color(0xFFE6E6E6),
    teal = Color(0xFFFFE27A), onTeal = Color(0xFF000000), tealSoft = Color(0xFF1A1600), tealMid = Color(0xFF2A2400),
    tealRing = Color(0xFF110E00),
    safe = Color(0xFF7DFFA8), safeSoft = Color(0xFF00220F), safeCard = Color(0xFF001A0B), safeBorder = Color(0xFF7DFFA8),
    caution = Color(0xFFFFB84D), cautionText = Color(0xFFFFB84D), cautionSoft = Color(0xFF2A1B00),
    cautionCard = Color(0xFF1F1400), cautionBorder = Color(0xFFFFB84D),
    danger = Color(0xFFFF5A4F), dangerText = Color(0xFFFF8A80), dangerSoft = Color(0xFF2A0A08),
    dangerCard = Color(0xFF200807), dangerBorder = Color(0xFFFF5A4F),
    border = Color(0xFFFFFFFF), borderStrong = Color(0xFFFFFFFF), divider = Color(0xFF8C8C8C), sand = Color(0xFF1A1A1A),
    toggleOff = Color(0xFF3A3A3A), toggleOffKnob = Color(0xFFFFFFFF), chevron = Color(0xFFFFFFFF),
    navy = Color(0xFF000000), gold = Color(0xFFFFE27A),
    camStripeA = Color(0xFF1A1A1A), camStripeB = Color(0xFF111111),
)

val LocalRaahiColors = staticCompositionLocalOf { LightColors }
