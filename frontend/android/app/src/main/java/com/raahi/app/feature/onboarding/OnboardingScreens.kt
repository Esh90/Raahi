package com.raahi.app.feature.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.raahi.app.R
import com.raahi.app.core.designsystem.BtnStyle
import com.raahi.app.core.designsystem.Chip
import com.raahi.app.core.designsystem.IconBubble
import com.raahi.app.core.designsystem.LogoTile
import com.raahi.app.core.designsystem.Label
import com.raahi.app.core.designsystem.PagerDots
import com.raahi.app.core.designsystem.PaperScreen
import com.raahi.app.core.designsystem.RButton
import com.raahi.app.core.designsystem.RCard
import com.raahi.app.core.designsystem.RT
import com.raahi.app.core.designsystem.Raahi
import com.raahi.app.core.designsystem.ScreenBox
import com.raahi.app.core.designsystem.Sym
import com.raahi.app.core.permissions.CAMERA
import com.raahi.app.core.permissions.MICROPHONE
import com.raahi.app.core.permissions.openAppSettings
import com.raahi.app.core.permissions.rememberPermissionState
import com.raahi.app.core.haptics.Haptics
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------------------------- 01 Splash

@Composable
fun SplashScreen(onDone: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(1400)
        onDone()
    }
    val teal = Color(0xFF0E5E63)
    val paper = Color(0xFFF7F4EE)
    ScreenBox(teal) {
        Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically)) {
            Image(painterResource(R.drawable.raahi_monogram), contentDescription = null, modifier = Modifier.width(140.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Raahi", style = RT.h1.copy(fontSize = 48.sp, lineHeight = 48.sp), color = paper)
                Text("آپ کے سفر کا ساتھی", style = RT.urdu, color = paper, modifier = Modifier.padding(top = 8.dp))
            }
        }
        Text(
            "Your surroundings, understood.",
            Modifier.fillMaxWidth().padding(start = 40.dp, end = 40.dp, bottom = 56.dp),
            style = RT.button.copy(fontWeight = FontWeight.Medium),
            color = paper.copy(alpha = 0.9f),
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------------------------------- 02-05 onboarding

enum class OnboardingPage(val index: Int) { WELCOME(0), VOICE(1), SURROUNDINGS(2), GESTURES(3) }

@Composable
fun OnboardingScreen(page: OnboardingPage, onNext: () -> Unit, onSkip: () -> Unit) {
    val c = Raahi.colors
    PaperScreen {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onSkip).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Skip", style = RT.button.copy(fontSize = 15.sp), color = c.teal) }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            when (page) {
                OnboardingPage.WELCOME -> {
                    WelcomeIllustration()
                    TextBlock("Welcome", "Walk with more confidence", "Raahi describes what is in front of you and guides you around obstacles, in Urdu.")
                }
                OnboardingPage.VOICE -> {
                    VoiceIllustration()
                    TextBlock("Voice guidance", "Spoken clearly, in Urdu", "Short instructions you can act on. English subtitles appear on screen for companions.")
                }
                OnboardingPage.SURROUNDINGS -> {
                    SurroundingsIllustration()
                    TextBlock("Awareness", "Know what is ahead", "Your camera spots people, furniture and doors, and tells you how far they are.")
                }
                OnboardingPage.GESTURES -> GesturesBody()
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            PagerDots(4, page.index)
            RButton("Next", onNext, Modifier.width(140.dp), trailingIcon = "arrow_forward")
        }
    }
}

@Composable
private fun TextBlock(eyebrow: String, title: String, body: String) {
    val c = Raahi.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Label(eyebrow, color = c.teal)
        Text(title, style = RT.h1, color = c.ink)
        Text(body, style = RT.body, color = c.muted)
    }
}

@Composable
private fun IllustrationFrame(bg: Color, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(24.dp)).background(bg).clearAndSetSemantics { }, contentAlignment = Alignment.Center, content = content)
}

@Composable
private fun WelcomeIllustration() {
    val c = Raahi.colors
    IllustrationFrame(c.tealSoft) {
        Box(Modifier.size(200.dp).clip(CircleShape).background(c.tealMid), contentAlignment = Alignment.Center) {
            Sym("directions_walk", size = 112.dp, tint = c.teal)
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(28.dp).size(56.dp).clip(RoundedCornerShape(18.dp)).background(c.teal), contentAlignment = Alignment.Center) {
            Sym("smartphone", size = 30.dp, tint = c.onTeal, filled = true)
        }
    }
}

@Composable
private fun VoiceIllustration() {
    val c = Raahi.colors
    IllustrationFrame(c.tealSoft) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.size(96.dp).clip(CircleShape).background(c.teal), contentAlignment = Alignment.Center) {
                Sym("graphic_eq", size = 44.dp, tint = c.onTeal, filled = true)
            }
            RCard(padding = 12.dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("آگے کرسی ہے، بائیں مڑیں", style = RT.urdu.copy(lineHeight = 36.sp), color = c.ink, textAlign = TextAlign.Center)
                    Text("“Chair ahead, turn left”", style = RT.caption, color = c.mutedSoft)
                }
            }
        }
    }
}

@Composable
private fun SurroundingsIllustration() {
    val c = Raahi.colors
    val ringA = if (c.isDark) Color(0xFF3A5C5A) else Color(0xFF9FBFBB)
    val ringB = if (c.isDark) Color(0xFF2C4442) else Color(0xFFC9DAD7)
    Box(
        Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(24.dp)).background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(24.dp)).clearAndSetSemantics { }
            .drawBehind {
                val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                val cx = size.width / 2
                drawCircle(ringA, radius = 150.dp.toPx(), center = Offset(cx, size.height + 0.dp.toPx()), style = Stroke(1.dp.toPx(), pathEffect = dash))
                drawCircle(ringB, radius = 240.dp.toPx(), center = Offset(cx, size.height + 0.dp.toPx()), style = Stroke(1.dp.toPx(), pathEffect = dash))
            },
    ) {
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).size(40.dp).clip(CircleShape).background(c.teal), contentAlignment = Alignment.Center) {
            Sym("navigation", tint = c.onTeal, filled = true)
        }
        Chip("Chair · 2 steps", Modifier.align(Alignment.TopStart).offset(28.dp, 70.dp), icon = "chair")
        Chip("Door · right", Modifier.align(Alignment.TopEnd).offset((-24).dp, 140.dp), icon = "door_front")
        Chip(
            "Step down", Modifier.align(Alignment.TopStart).offset(96.dp, 24.dp), icon = "warning", iconTint = c.caution,
            bg = c.cautionCard, borderColor = c.cautionBorder,
        )
    }
}

@Composable
private fun GesturesBody() {
    val c = Raahi.colors
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Label("Gestures", color = c.teal)
        Text("Anywhere on screen", style = RT.h1, color = c.ink)
        GestureCard("touch_double", "Double tap", "Repeat last instruction")
        GestureCard("touch_long", "Long press", "Speak a command")
        GestureCard("swipe_left", "Swipe left", "Pause or resume guidance")
    }
}

@Composable
fun GestureCard(icon: String, title: String, body: String) {
    val c = Raahi.colors
    RCard(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBubble(icon, size = 64.dp, radius = 20.dp, iconSize = 32.dp)
            Column {
                Text(title, style = RT.title, color = c.ink)
                Text(body, style = RT.body.copy(fontSize = 15.sp), color = c.muted)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- 06 Get started

@Composable
fun GetStartedScreen(onStart: () -> Unit, onReplay: () -> Unit) {
    val c = Raahi.colors
    PaperScreen {
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterVertically),
        ) {
            LogoTile(112.dp, corner = 32.dp)
            Text("You're ready,\nlet's go", style = RT.h1, color = c.ink, textAlign = TextAlign.Center)
            Text("We'll ask for camera and microphone access next. You can change this anytime.", style = RT.body, color = c.muted, textAlign = TextAlign.Center)
        }
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 36.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RButton("Get started", onStart, Modifier.fillMaxWidth(), height = 64.dp)
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onReplay), contentAlignment = Alignment.Center) {
                Text("Replay introduction", style = RT.button.copy(fontSize = 15.sp), color = c.teal)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- 07 Permissions

@Composable
fun PermissionsScreen(onContinue: () -> Unit) {
    val c = Raahi.colors
    val context = LocalContext.current
    val camera = rememberPermissionState(CAMERA)
    val mic = rememberPermissionState(MICROPHONE)
    val canVibrate = remember(context) { Haptics(context).isAvailable }

    PaperScreen {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Allow access", style = RT.h2, color = c.ink)
            Text("Raahi needs these to guide you.", style = RT.body, color = c.muted)
            PermissionCard("photo_camera", "Camera", "To see obstacles ahead", camera.granted, camera.permanentlyDenied, camera::request) { context.openAppSettings() }
            PermissionCard("mic", "Microphone", "To hear your commands", mic.granted, mic.permanentlyDenied, mic::request) { context.openAppSettings() }
            if (canVibrate) {
                PermissionCard("vibration", "Vibration", "Danger alerts buzz your phone", true, false, {}, {})
            } else {
                RCard(Modifier.fillMaxWidth(), color = c.cautionCard, borderColor = c.cautionBorder) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        IconBubble("vibration", bg = c.cautionSoft, fg = c.cautionText)
                        Column(Modifier.weight(1f)) {
                            Text("Vibration", style = RT.title.copy(fontSize = 17.sp), color = c.ink)
                            Text("This phone can't vibrate. Danger alerts will be audio only.", style = RT.bodyS, color = c.muted)
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 32.dp, top = 8.dp)) {
            RButton("Continue", onContinue, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PermissionCard(
    icon: String,
    title: String,
    body: String,
    granted: Boolean,
    permanentlyDenied: Boolean,
    onAllow: () -> Unit,
    onSettings: () -> Unit,
) {
    val c = Raahi.colors
    RCard(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            IconBubble(icon)
            Column(Modifier.weight(1f)) {
                Text(title, style = RT.title.copy(fontSize = 17.sp), color = c.ink)
                Text(body, style = RT.bodyS, color = c.muted)
            }
            if (granted) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Sym("check_circle", size = 20.dp, tint = c.safe, filled = true)
                    Text("Allowed", style = RT.caption.copy(fontWeight = FontWeight.SemiBold), color = c.safe)
                }
            } else {
                Box(
                    Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(c.teal)
                        .clickable(role = Role.Button, onClick = if (permanentlyDenied) onSettings else onAllow).padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(if (permanentlyDenied) "Settings" else "Allow", style = RT.bodyS.copy(fontWeight = FontWeight.SemiBold), color = c.onTeal) }
            }
        }
    }
}
