// On-device detector for Android (Capacitor plugin, LiteRT/TFLite + GPU delegate).
// Setup: see mobile/native/android/README.md
//
// JS:  const r = await RaahiDetector.detect({ image: base64Jpeg })   // no "data:" prefix
//      r.detections = [{cls:"chair", conf:0.81, box:[x1,y1,x2,y2]}]  box normalised 0-1
//      r.ms = inference time
package com.raahi.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

@CapacitorPlugin(name = "RaahiDetector")
class RaahiDetectorPlugin : Plugin() {

    private var interpreter: Interpreter? = null
    
    private var gpu: GpuDelegate? = null
    private var imgsz = 320
    private var classIds: List<String?> = emptyList()   // model output index -> our class id
    private var minConf: Map<String, Double> = emptyMap()
    private var baseConf = 0.25f
    private var minAreaFrac = 0.02f
    private var usingGpu = false
    private var nchw = true        // ultralytics LiteRT export = [1,3,H,W]; older TFLite exports = [1,H,W,3]
    private val worker = Executors.newSingleThreadExecutor()

    // ---- lifecycle ---------------------------------------------------------

    @PluginMethod
    fun load(call: PluginCall) {
        val model = call.getString("model", "raahi_yoloe_n_320.tflite")!!
        val labels = call.getString("labels", model.replace(Regex("(_int8)?\\.tflite$"), ".labels.json"))!!
        imgsz = call.getInt("imgsz", Regex("_(\\d{3})").findAll(model).lastOrNull()?.groupValues?.get(1)?.toInt() ?: 320)!!
        baseConf = (call.getFloat("conf", 0.25f) ?: 0.25f)
        val useGpu = call.getBoolean("gpu", true)!!
        worker.execute {
            try {
                close()
                val opts = Interpreter.Options()
                usingGpu = false
                if (useGpu && CompatibilityList().isDelegateSupportedOnThisDevice) {
                    gpu = GpuDelegate(CompatibilityList().bestOptionsForThisDevice)
                    opts.addDelegate(gpu)
                    usingGpu = true
                } else {
                    opts.setNumThreads(4)
                }
                interpreter = try {
                    Interpreter(loadAsset("public/models/$model"), opts)
                } catch (e: Exception) {           // GPU can fail on some ops -> CPU
                    gpu?.close(); gpu = null; usingGpu = false
                    Interpreter(loadAsset("public/models/$model"), Interpreter.Options().setNumThreads(4))
                }
                readLabels("public/models/$labels")
                val inShape = interpreter!!.getInputTensor(0).shape()
                nchw = inShape.size == 4 && inShape[1] == 3
                imgsz = if (nchw) inShape[2] else inShape[1]
                val ret = JSObject()
                ret.put("ok", true); ret.put("gpu", usingGpu); ret.put("imgsz", imgsz)
                ret.put("classes", classIds.size)
                ret.put("input", inShape.joinToString("x"))
                ret.put("output", interpreter!!.getOutputTensor(0).shape().joinToString("x"))
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject("load failed: ${e.message}", e)
            }
        }
    }

    private fun close() {
        interpreter?.close(); interpreter = null
        gpu?.close(); gpu = null
    }

    override fun handleOnDestroy() { close(); worker.shutdown() }

    private fun loadAsset(path: String): MappedByteBuffer {
        val fd = context.assets.openFd(path)
        FileInputStream(fd.fileDescriptor).channel.use { ch ->
            return ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }

    private fun readLabels(path: String) {
        val txt = context.assets.open(path).bufferedReader(Charsets.UTF_8).readText()
        val j = JSONObject(txt)
        val ids = j.getJSONArray("class_ids")
        classIds = (0 until ids.length()).map { if (ids.isNull(it)) null else ids.getString(it) }
        val mc = j.optJSONObject("min_conf")
        minConf = mc?.keys()?.asSequence()?.associateWith { mc.getDouble(it) } ?: emptyMap()
    }

    // ---- inference ---------------------------------------------------------

    @PluginMethod
    fun detect(call: PluginCall) {
        val b64 = call.getString("image") ?: return call.reject("image (base64 jpeg) required")
        val interp = interpreter ?: return call.reject("call load() first")
        worker.execute {
            try {
                val t0 = System.nanoTime()
                val bytes = Base64.decode(b64.substringAfter(","), Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                val (input, lb) = letterbox(bmp)
                val outShape = interp.getOutputTensor(0).shape()     // [1,300,6+] or [1,4+nc(+32),N]
                val out = Array(outShape[0]) { Array(outShape[1]) { FloatArray(outShape[2]) } }
                if (interp.outputTensorCount > 1) {
                    val outputs = HashMap<Int, Any>()
                    outputs[0] = out
                    for (i in 1 until interp.outputTensorCount) {
                        val s = interp.getOutputTensor(i).shape()
                        outputs[i] = ByteBuffer.allocateDirect(s.fold(4) { a, b -> a * b }).order(ByteOrder.nativeOrder())
                    }
                    interp.runForMultipleInputsOutputs(arrayOf(input), outputs)
                } else {
                    interp.run(input, out)
                }
                val tInf = System.nanoTime()
                val dets = parse(out[0], outShape, lb, bmp.width, bmp.height)
                val ret = JSObject()
                ret.put("detections", dets)
                ret.put("ms", (tInf - t0) / 1e6)
                ret.put("gpu", usingGpu)
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject("detect failed: ${e.message}", e)
            }
        }
    }

    private data class Letterbox(val scale: Float, val padX: Float, val padY: Float)

    private fun letterbox(src: Bitmap): Pair<ByteBuffer, Letterbox> {
        val s = min(imgsz.toFloat() / src.width, imgsz.toFloat() / src.height)
        val nw = (src.width * s).toInt(); val nh = (src.height * s).toInt()
        val px = (imgsz - nw) / 2f; val py = (imgsz - nh) / 2f
        val canvasBmp = Bitmap.createBitmap(imgsz, imgsz, Bitmap.Config.ARGB_8888)
        val c = Canvas(canvasBmp)
        c.drawColor(Color.rgb(114, 114, 114))
        c.drawBitmap(Bitmap.createScaledBitmap(src, nw, nh, true), px, py, Paint(Paint.FILTER_BITMAP_FLAG))
        val px32 = IntArray(imgsz * imgsz)
        canvasBmp.getPixels(px32, 0, imgsz, 0, 0, imgsz, imgsz)
        val buf = ByteBuffer.allocateDirect(4 * 3 * imgsz * imgsz).order(ByteOrder.nativeOrder())
        if (nchw) {                       // [1,3,H,W] float32 RGB 0..1
            val fb = buf.asFloatBuffer()
            val plane = imgsz * imgsz
            for (i in 0 until plane) {
                val p = px32[i]
                fb.put(i, ((p shr 16) and 0xFF) / 255f)
                fb.put(i + plane, ((p shr 8) and 0xFF) / 255f)
                fb.put(i + 2 * plane, (p and 0xFF) / 255f)
            }
        } else {                          // [1,H,W,3]
            for (p in px32) {
                buf.putFloat(((p shr 16) and 0xFF) / 255f)
                buf.putFloat(((p shr 8) and 0xFF) / 255f)
                buf.putFloat((p and 0xFF) / 255f)
            }
        }
        buf.rewind()
        return buf to Letterbox(s, px, py)
    }

    /** Handles both YOLO26 end2end output [N, 6(+32 mask coeffs)] = x1,y1,x2,y2,score,cls
     *  and classic output [4+nc(+32), N] = cx,cy,w,h,class scores. */
    private fun parse(o: Array<FloatArray>, shape: IntArray, lb: Letterbox, w: Int, h: Int): JSArray {
        val raw = ArrayList<FloatArray>()      // x1,y1,x2,y2 (letterboxed px), score, cls
        val nc = classIds.size
        val endToEnd = shape[2] in 6..(6 + 64) && shape[1] >= shape[2]
        if (endToEnd) {
            for (row in o) {
                if (row[4] >= baseConf) raw.add(floatArrayOf(row[0], row[1], row[2], row[3], row[4], row[5]))
            }
        } else {
            val n = shape[2]
            for (i in 0 until n) {
                var best = 0; var bestS = 0f
                for (k in 0 until nc) { val sc = o[4 + k][i]; if (sc > bestS) { bestS = sc; best = k } }
                if (bestS < baseConf) continue
                val cx = o[0][i]; val cy = o[1][i]; val bw = o[2][i]; val bh = o[3][i]
                raw.add(floatArrayOf(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2, bestS, best.toFloat()))
            }
            nms(raw)
        }
        // coords may be normalised (0-1) or in pixels of the imgsz input
        val norm = raw.isNotEmpty() && raw.maxOf { max(it[2], it[3]) } <= 1.5f
        val k = if (norm) imgsz.toFloat() else 1f
        val res = JSArray()
        val minArea = minAreaFrac * w * h
        for (d in raw) {
            val cls = classIds.getOrNull(d[5].toInt()) ?: continue
            if (d[4] < (minConf[cls] ?: 0.0)) continue
            val x1 = ((d[0] * k - lb.padX) / lb.scale).coerceIn(0f, w.toFloat())
            val y1 = ((d[1] * k - lb.padY) / lb.scale).coerceIn(0f, h.toFloat())
            val x2 = ((d[2] * k - lb.padX) / lb.scale).coerceIn(0f, w.toFloat())
            val y2 = ((d[3] * k - lb.padY) / lb.scale).coerceIn(0f, h.toFloat())
            if ((x2 - x1) * (y2 - y1) < minArea) continue
            val j = JSObject()
            j.put("cls", cls); j.put("conf", d[4].toDouble())
            j.put("box", JSArray(listOf(x1 / w, y1 / h, x2 / w, y2 / h).map { it.toDouble() }))
            res.put(j)
        }
        return res
    }

    private fun nms(d: ArrayList<FloatArray>, iouT: Float = 0.5f) {
        d.sortByDescending { it[4] }
        val keep = ArrayList<FloatArray>()
        for (a in d) {
            if (keep.none { it[5] == a[5] && iou(it, a) > iouT }) keep.add(a)
        }
        d.clear(); d.addAll(keep)
    }

    private fun iou(a: FloatArray, b: FloatArray): Float {
        val ix = max(0f, min(a[2], b[2]) - max(a[0], b[0]))
        val iy = max(0f, min(a[3], b[3]) - max(a[1], b[1]))
        val inter = ix * iy
        val u = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
        return if (u <= 0f) 0f else inter / u
    }
}
