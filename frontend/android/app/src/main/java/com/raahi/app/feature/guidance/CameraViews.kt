package com.raahi.app.feature.guidance

import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.raahi.app.core.camera.CameraController
import com.raahi.app.core.designsystem.DmSans
import com.raahi.app.core.designsystem.Raahi
import com.raahi.app.core.model.Fmt
import com.raahi.app.core.model.OverlayBox
import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * Live rear-camera preview. Binding the camera is tied to this composable's lifetime: when it leaves the
 * composition (paused screen, other screen, app in background) the camera is released.
 */
@Composable
fun CameraPreviewSurface(controller: CameraController, modifier: Modifier = Modifier) {
    val owner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    DisposableEffect(owner) {
        controller.bind(owner, previewView)
        onDispose { controller.unbind() }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

/** Camera binding for screens that need frames but show no preview (e.g. "What's ahead?" scan). */
@Composable
fun CameraFramesOnly(controller: CameraController) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        controller.bind(owner, null)
        onDispose { controller.unbind() }
    }
}

/** Draws the server's overlay boxes (normalised 0..1 of the streamed frame) on top of a FILL_CENTER preview. */
@Composable
fun BoxesOverlay(
    overlay: List<OverlayBox>,
    frameWidth: Int,
    frameHeight: Int,
    modifier: Modifier = Modifier,
) {
    val c = Raahi.colors
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontFamily = DmSans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Color.White)
    Canvas(modifier.clearAndSetSemantics { }) {
        if (overlay.isEmpty() || frameWidth <= 0 || frameHeight <= 0) return@Canvas
        val vw = size.width
        val vh = size.height
        val scale = max(vw / frameWidth, vh / frameHeight)
        val offX = (vw - frameWidth * scale) / 2f
        val offY = (vh - frameHeight * scale) / 2f
        for (o in overlay) {
            if (o.box.size < 4) continue
            val x1 = o.box[0] * frameWidth * scale + offX
            val y1 = o.box[1] * frameHeight * scale + offY
            val x2 = o.box[2] * frameWidth * scale + offX
            val y2 = o.box[3] * frameHeight * scale + offY
            val d = o.distM
            val color = when {
                d != null && d < 0.8f -> c.danger
                d != null && d < 2.0f -> c.caution
                else -> if (c.highContrast) c.teal else Color(0xFF0E5E63)
            }
            val tl = Offset(x1.coerceAtLeast(0f), y1.coerceAtLeast(0f))
            val sz = Size((x2 - x1).coerceAtLeast(4f), (y2 - y1).coerceAtLeast(4f))
            drawRoundRect(color, tl, sz, CornerRadius(10.dp.toPx()), style = Stroke(2.5.dp.toPx()))

            val text = Fmt.prettyLabel(o.label) + (if (d != null) " · " + Fmt.meters(d) else "")
            val layout = measurer.measure(text, style)
            val padX = 8.dp.toPx()
            val padY = 4.dp.toPx()
            val lw = layout.size.width + padX * 2
            val lh = layout.size.height + padY * 2
            val lx = tl.x.coerceIn(0f, (vw - lw).coerceAtLeast(0f))
            val ly = (tl.y - lh - 2.dp.toPx()).coerceAtLeast(0f)
            drawRoundRect(color, Offset(lx, ly), Size(lw, lh), CornerRadius(7.dp.toPx()))
            drawText(layout, topLeft = Offset(lx + padX, ly + padY))
        }
    }
}

/** Milliseconds since [startedAt], ticking once a second. */
@Composable
fun rememberElapsed(startedAt: Long, until: Long = 0L): State<Long> =
    produceState(initialValue = 0L, startedAt, until) {
        while (true) {
            value = ((if (until > 0) until else System.currentTimeMillis()) - startedAt).coerceAtLeast(0)
            delay(1000)
        }
    }
