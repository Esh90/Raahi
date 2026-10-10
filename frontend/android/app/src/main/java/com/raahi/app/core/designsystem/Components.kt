package com.raahi.app.core.designsystem

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// ---------------------------------------------------------------- icons (Material Symbols Rounded, ligatures)

/** Renders a Material Symbols ligature, e.g. Sym("turn_left"). Decorative unless [description] is set. */
@Composable
fun Sym(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = Raahi.colors.ink,
    filled: Boolean = false,
    description: String? = null,
) {
    val sp = with(LocalDensity.current) { size.toSp() }
    val sem = if (description != null) Modifier.semantics { contentDescription = description } else Modifier.clearAndSetSemantics { }
    Text(
        text = name,
        modifier = modifier.then(sem).size(size),
        style = TextStyle(
            fontFamily = if (filled) SymbolsFilled else SymbolsOutlined,
            fontWeight = FontWeight.Normal,
            fontSize = sp,
            lineHeight = sp,
            color = tint,
            fontFeatureSettings = "liga",
        ),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
    )
}

// ---------------------------------------------------------------- screen scaffolding

private fun Context.findActivity(): Activity? {
    var c = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Full-bleed screen background + system-bar padding. Chooses light/dark status-bar icons from [bg]. */
@Composable
fun ScreenBox(
    bg: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val view = LocalView.current
    val lightBg = bg.luminance() > 0.5f
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        val c = WindowCompat.getInsetsController(window, view)
        c.isAppearanceLightStatusBars = lightBg
        c.isAppearanceLightNavigationBars = lightBg
    }
    Box(modifier.fillMaxSize().background(bg)) {
        Column(Modifier.fillMaxSize().systemBarsPadding(), content = content)
    }
}

/** Standard screen: paper background. */
@Composable
fun PaperScreen(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    ScreenBox(Raahi.colors.paper, modifier, content)

@Composable
fun TopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backIcon: String = "arrow_back",
    backLabel: String = "Back",
    big: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = Raahi.colors
    Row(
        modifier.fillMaxWidth().height(60.dp).padding(horizontal = if (big) 20.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onBack != null) IconAction(backIcon, backLabel, onBack, tint = c.ink)
        Text(
            title,
            Modifier.weight(1f).semantics { heading() },
            style = if (big) RT.h1.copy(fontSize = 26.sp) else RT.titleL,
            color = c.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        trailing?.invoke(this)
    }
}

/** 48 dp round icon button (minimum touch target). */
@Composable
fun IconAction(
    icon: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Raahi.colors.ink,
    filled: Boolean = false,
    size: Dp = 48.dp,
    bg: Color = Color.Transparent,
    border: Color? = null,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .then(if (border != null) Modifier.border(1.dp, border, CircleShape) else Modifier)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Sym(icon, size = 24.dp, tint = tint, filled = filled)
    }
}

// ---------------------------------------------------------------- cards, chips, labels

@Composable
fun RCard(
    modifier: Modifier = Modifier,
    color: Color = Raahi.colors.surface,
    borderColor: Color = Raahi.colors.border,
    padding: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val bw = if (Raahi.colors.highContrast) 2.dp else 1.dp
    Box(
        modifier
            .clip(shape)
            .background(color)
            .border(bw, borderColor, shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = Raahi.colors.mutedSoft) {
    Text(text.uppercase(), modifier.semantics { heading() }, style = RT.label, color = color)
}

@Composable
fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    dot: Color? = null,
    icon: String? = null,
    iconTint: Color = Raahi.colors.teal,
    bg: Color = Raahi.colors.surface,
    borderColor: Color = Raahi.colors.borderStrong,
    height: Dp = 36.dp,
    textStyle: TextStyle = RT.bodyS.copy(fontWeight = FontWeight.Medium),
    textColor: Color = Raahi.colors.ink,
) {
    Row(
        modifier.height(height).clip(RoundedCornerShape(height / 2)).background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(height / 2)).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot != null) Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        if (icon != null) Sym(icon, size = 18.dp, tint = iconTint)
        Text(text, style = textStyle, color = textColor, maxLines = 1)
    }
}

@Composable
fun StatusPill(text: String, fg: Color, bg: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.clip(RoundedCornerShape(10.dp)).background(bg).padding(horizontal = 10.dp, vertical = 5.dp),
        style = RT.caption.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
        color = fg,
    )
}

@Composable
fun IconBubble(
    icon: String,
    modifier: Modifier = Modifier,
    bg: Color = Raahi.colors.tealSoft,
    fg: Color = Raahi.colors.teal,
    size: Dp = 44.dp,
    radius: Dp = 14.dp,
    iconSize: Dp = 24.dp,
    filled: Boolean = false,
) {
    Box(modifier.size(size).clip(RoundedCornerShape(radius)).background(bg), contentAlignment = Alignment.Center) {
        Sym(icon, size = iconSize, tint = fg, filled = filled)
    }
}

// ---------------------------------------------------------------- buttons

enum class BtnStyle { Primary, Secondary, DangerOutline, OnDanger, Danger }

@Composable
fun RButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: BtnStyle = BtnStyle.Primary,
    icon: String? = null,
    filledIcon: Boolean = false,
    height: Dp = 56.dp,
    enabled: Boolean = true,
    trailingIcon: String? = null,
) {
    val c = Raahi.colors
    val (bg, fg, border) = when (style) {
        BtnStyle.Primary -> Triple(c.teal, c.onTeal, null)
        BtnStyle.Secondary -> Triple(Color.Transparent, c.ink, c.borderStrong)
        BtnStyle.DangerOutline -> Triple(Color.Transparent, c.dangerText, c.dangerBorder)
        BtnStyle.OnDanger -> Triple(Color.White, Color(0xFFB3261E), null)
        BtnStyle.Danger -> Triple(c.danger, Color.White, null)
    }
    val shape = RoundedCornerShape(height / 2)
    val alpha = if (enabled) 1f else 0.45f
    Row(
        modifier
            .heightIn(min = height)
            .clip(shape)
            .background(bg.copy(alpha = bg.alpha * alpha))
            .then(if (border != null) Modifier.border(if (c.highContrast) 2.dp else 1.5.dp, border.copy(alpha = alpha), shape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Sym(icon, size = 24.dp, tint = fg.copy(alpha = alpha), filled = filledIcon)
        Text(text, style = RT.button, color = fg.copy(alpha = alpha), maxLines = 2, textAlign = TextAlign.Center)
        if (trailingIcon != null) Sym(trailingIcon, size = 24.dp, tint = fg.copy(alpha = alpha))
    }
}

// ---------------------------------------------------------------- rows, switches, segmented

@Composable
fun RowGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    RCard(modifier.fillMaxWidth(), padding = 0.dp) { Column(content = content) }
}

@Composable
private fun RowShell(
    modifier: Modifier,
    divider: Boolean,
    content: @Composable RowScope.() -> Unit,
) {
    val c = Raahi.colors
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
    }
}

@Composable
private fun RowTexts(title: String, subtitle: String?, modifier: Modifier = Modifier, titleSize: TextUnit = 16.sp) {
    val c = Raahi.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = RT.rowTitle.copy(fontSize = titleSize), color = c.ink)
        if (subtitle != null) Text(subtitle, style = RT.caption, color = c.mutedSoft)
    }
}

/** Row with a chevron (navigates). */
@Composable
fun NavRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: String? = null,
    iconBubble: Boolean = true,
    bubbleBg: Color = Raahi.colors.tealSoft,
    bubbleFg: Color = Raahi.colors.teal,
    divider: Boolean = true,
) {
    val c = Raahi.colors
    RowShell(modifier.clickable(role = Role.Button, onClick = onClick), divider) {
        if (icon != null) {
            if (iconBubble) IconBubble(icon, bg = bubbleBg, fg = bubbleFg) else Sym(icon, tint = c.teal)
        }
        RowTexts(title, subtitle, Modifier.weight(1f))
        Sym("chevron_right", tint = c.chevron)
    }
}

/** Row with a switch. The whole row is the switch for TalkBack. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    divider: Boolean = true,
) {
    RowShell(modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange), divider) {
        RowTexts(title, subtitle, Modifier.weight(1f))
        SwitchVisual(checked)
    }
}

/** Plain row (value text or custom trailing). */
@Composable
fun InfoRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    divider: Boolean = true,
    minHeight: Dp = 56.dp,
    titleSize: TextUnit = 16.sp,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    RowShell(modifier.heightIn(min = minHeight), divider) {
        RowTexts(title, subtitle, Modifier.weight(1f), titleSize)
        trailing?.invoke(this)
    }
}

@Composable
fun SwitchVisual(checked: Boolean) {
    val c = Raahi.colors
    Box(
        Modifier.width(52.dp).height(32.dp).clip(RoundedCornerShape(16.dp))
            .background(if (checked) c.teal else c.toggleOff)
            .clearAndSetSemantics { },
    ) {
        val knob = if (checked) 24.dp else 20.dp
        val x = if (checked) 52.dp - 4.dp - knob else 6.dp
        Box(
            Modifier.padding(start = x, top = (32.dp - knob) / 2).size(knob).clip(CircleShape)
                .background(if (checked) (if (c.highContrast) Color.Black else Color.White) else c.toggleOffKnob)
        )
    }
}

/** Segmented control (Light/Dark/System, Slow/Normal/Fast, ...). */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    fontSizes: List<TextUnit>? = null,
    descriptions: List<String>? = null,
) {
    val c = Raahi.colors
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.sand).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (on) c.surface else Color.Transparent)
                    .then(if (on && c.highContrast) Modifier.border(2.dp, c.teal, RoundedCornerShape(12.dp)) else Modifier)
                    .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(i) })
                    .semantics { descriptions?.getOrNull(i)?.let { contentDescription = it } },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = RT.button.copy(fontSize = fontSizes?.getOrNull(i) ?: 15.sp, fontWeight = FontWeight.SemiBold),
                    color = if (on) c.ink else c.muted,
                    maxLines = 1,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- bottom navigation + pager dots

enum class MainTab(val label: String, val icon: String) {
    HOME("Home", "home"), HISTORY("History", "history"), SETTINGS("Settings", "settings")
}

@Composable
fun MainNavBar(selected: MainTab, onSelect: (MainTab) -> Unit, modifier: Modifier = Modifier) {
    val c = Raahi.colors
    Column(modifier.fillMaxWidth().background(c.surface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
        Row(Modifier.fillMaxWidth().height(76.dp), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
            MainTab.entries.forEach { tab ->
                val on = tab == selected
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .selectable(selected = on, role = Role.Tab, onClick = { onSelect(tab) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) c.tealMid else Color.Transparent)
                            .padding(horizontal = 20.dp, vertical = 4.dp)
                    ) { Sym(tab.icon, tint = if (on) c.teal else c.mutedSoft, filled = on) }
                    Spacer(Modifier.height(4.dp))
                    Text(tab.label, style = RT.caption.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp), color = if (on) c.teal else c.mutedSoft)
                }
            }
        }
    }
}

@Composable
fun PagerDots(count: Int, index: Int, modifier: Modifier = Modifier) {
    val c = Raahi.colors
    Row(modifier.clearAndSetSemantics { contentDescription = "Step ${index + 1} of $count" }, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            if (i == index) Box(Modifier.width(24.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.teal))
            else Box(Modifier.size(8.dp).clip(CircleShape).background(if (c.isDark) c.toggleOff else Color(0xFFD0C9BC)))
        }
    }
}

// ---------------------------------------------------------------- brand mark

/** The waypoint pin: ring teardrop + warm dot (design system "Logo & app icon"). */
@Composable
fun PinMark(size: Dp, ring: Color, dot: Color, modifier: Modifier = Modifier, strokeFraction: Float = 0.12f) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val r = w * 0.5f * 0.62f
        val c = Offset(w / 2f, w / 2f - 0.207f * r)
        val tip = Offset(c.x, c.y + 1.414f * r)
        val p1 = Offset(c.x - 0.707f * r, c.y + 0.707f * r)
        val path = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(p1.x, p1.y)
            arcTo(Rect(c.x - r, c.y - r, c.x + r, c.y + r), 135f, 270f, false)
            lineTo(tip.x, tip.y)
            close()
        }
        drawPath(path, ring, style = Stroke(width = w * strokeFraction, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(dot, radius = r * 0.27f, center = c)
    }
}

@Composable
fun LogoTile(size: Dp, modifier: Modifier = Modifier, corner: Dp = size * 0.29f) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(corner)).background(Color(0xFF0E5E63)).clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(id = com.raahi.app.R.drawable.raahi_monogram),
            contentDescription = null,
            modifier = Modifier.size(size * 0.78f),
        )
    }
}

@Composable
fun Wordmark(modifier: Modifier = Modifier, size: TextUnit = 24.sp, color: Color = Raahi.colors.ink) {
    Text("raahi", modifier, style = RT.h1.copy(fontSize = size, lineHeight = size, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em), color = color)
}

/** Empty size placeholder. */
@Composable
fun Gap(h: Dp) = Spacer(Modifier.height(h))
