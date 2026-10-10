@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.raahi.app.core.designsystem

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.raahi.app.R

/** DM Sans (variable font, weights 100-1000) as in the design; Noto Nastaliq Urdu for Urdu; JetBrains Mono for data. */
val DmSans = FontFamily(
    Font(R.font.dm_sans, FontWeight.Normal),
    Font(R.font.dm_sans, FontWeight.Medium),
    Font(R.font.dm_sans, FontWeight.SemiBold),
    Font(R.font.dm_sans, FontWeight.Bold),
    Font(R.font.dm_sans, FontWeight.ExtraBold),
)

val UrduFont = FontFamily(Font(R.font.noto_nastaliq_urdu, FontWeight.Medium))
val MonoFont = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Medium))

/** Material Symbols Rounded, variable FILL axis: outlined and filled families. */
val SymbolsOutlined = FontFamily(
    Font(
        R.font.material_symbols_rounded,
        FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.Setting("FILL", 0f)),
    )
)
val SymbolsFilled = FontFamily(
    Font(
        R.font.material_symbols_rounded,
        FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.Setting("FILL", 1f)),
    )
)

private val tight = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.None)

/** Text styles from the design's CSS (.h1, .p, .lb, .btn ...). Sizes are sp so user font scale still applies. */
object RT {
    val h1 = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 34.5.sp, letterSpacing = (-0.01).em, lineHeightStyle = tight)
    val h2 = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 31.sp, lineHeightStyle = tight)
    val display = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.ExtraBold, fontSize = 76.sp, lineHeight = 76.sp, letterSpacing = 0.02.em)
    val instruction = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Bold, fontSize = 46.sp, lineHeight = 46.sp, letterSpacing = (-0.02).em)
    val title = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 23.sp)
    val titleL = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp)
    val body = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp)
    val bodyS = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp)
    val caption = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)
    val rowTitle = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp)
    val button = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp)
    val label = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.09.em)
    val eyebrow = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.08.em)
    val mono = TextStyle(fontFamily = MonoFont, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 22.sp)
    val urdu = TextStyle(fontFamily = UrduFont, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 38.sp, textDirection = TextDirection.Rtl)
}
