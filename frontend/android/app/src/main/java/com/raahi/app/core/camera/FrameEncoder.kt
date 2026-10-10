package com.raahi.app.core.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/** A JPEG ready for the `/ws` socket plus its pixel size (needed to map overlay boxes). */
class EncodedFrame(val jpeg: ByteArray, val width: Int, val height: Int)

/**
 * Turns a CameraX frame into what the server expects (contracts/protocol.md 5.1, PLAN.md):
 * an *upright* JPEG, [STREAM_WIDTH] px wide (server/config.py STREAM_WIDTH=416), quality 60
 * (same as tools/send_test_frame.py). Rotation is applied here so the server always sees the
 * scene the way the user sees it with the phone held upright.
 */
object FrameEncoder {
    const val STREAM_WIDTH = 416
    const val JPEG_QUALITY = 60

    /** Output size for a source frame of [srcW]x[srcH] rotated by [rotationDegrees]. */
    fun targetSize(srcW: Int, srcH: Int, rotationDegrees: Int): Pair<Int, Int> {
        val rotated = rotationDegrees % 180 != 0
        val w = if (rotated) srcH else srcW
        val h = if (rotated) srcW else srcH
        val outH = (h * STREAM_WIDTH.toFloat() / w).toInt().coerceAtLeast(1)
        return STREAM_WIDTH to outH
    }

    /** Requires the analysis use case to output RGBA_8888. Returns null on any conversion problem. */
    fun encode(image: ImageProxy): EncodedFrame? {
        var src: Bitmap? = null
        var out: Bitmap? = null
        return try {
            src = image.toBitmap()
            val rot = image.imageInfo.rotationDegrees
            val (tw, th) = targetSize(src.width, src.height, rot)
            val rotatedW = if (rot % 180 != 0) src.height else src.width
            val m = Matrix().apply {
                postRotate(rot.toFloat())
                val s = STREAM_WIDTH.toFloat() / rotatedW
                postScale(s, s)
            }
            out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            val bos = ByteArrayOutputStream(48 * 1024)
            if (!out.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos)) return null
            EncodedFrame(bos.toByteArray(), out.width.takeIf { it > 0 } ?: tw, out.height.takeIf { it > 0 } ?: th)
        } catch (e: Exception) {
            null
        } finally {
            if (out != null && out !== src) out.recycle()
            src?.recycle()
        }
    }
}
