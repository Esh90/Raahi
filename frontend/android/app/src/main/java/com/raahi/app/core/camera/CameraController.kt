package com.raahi.app.core.camera

import android.content.Context
import android.util.Size
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

sealed interface CameraStatus {
    data object Off : CameraStatus
    data object Starting : CameraStatus
    data object Running : CameraStatus
    data class Failed(val reason: String) : CameraStatus
}

/**
 * Rear-camera preview + frame acquisition via CameraX. Frames are only converted/encoded when
 * [shouldCapture] says the session wants one (socket ready, not paused, phone upright), which keeps
 * CPU/battery use low. Everything is released in [unbind].
 */
class CameraController(private val context: Context) {
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var bindGeneration = 0
    private var lastFrameAt = 0L

    private val _status = MutableStateFlow<CameraStatus>(CameraStatus.Off)
    val status: StateFlow<CameraStatus> = _status.asStateFlow()

    /** Cheap gate evaluated on the analysis thread before any bitmap work. */
    @Volatile var shouldCapture: () -> Boolean = { false }

    /** Receives each encoded frame on the analysis thread. */
    @Volatile var onFrame: (EncodedFrame) -> Unit = {}

    private val selector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO))
        .setResolutionStrategy(
            ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
        )
        .build()

    /** Bind preview (optional) + analysis to [owner]. Must be called on the main thread. */
    fun bind(owner: LifecycleOwner, previewView: PreviewView?) {
        val gen = ++bindGeneration
        _status.value = CameraStatus.Starting
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (gen != bindGeneration) return@addListener
            try {
                val p = future.get()
                provider = p
                p.unbindAll()

                val useCases = mutableListOf<androidx.camera.core.UseCase>()
                if (previewView != null) {
                    val preview = Preview.Builder().setResolutionSelector(selector).build()
                    preview.setSurfaceProvider(previewView.surfaceProvider)
                    useCases += preview
                }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(selector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { image -> analyze(image) }
                useCases += analysis

                val cam = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases.toTypedArray())
                camera = cam
                cam.cameraInfo.cameraState.observe(owner) { st ->
                    if (gen != bindGeneration) return@observe
                    val err = st.error
                    when {
                        err != null -> _status.value = CameraStatus.Failed("camera error ${err.code}")
                        st.type == androidx.camera.core.CameraState.Type.OPEN -> _status.value = CameraStatus.Running
                        else -> Unit
                    }
                }
            } catch (e: Exception) {
                _status.value = CameraStatus.Failed(e.javaClass.simpleName)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun analyze(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastFrameAt < MIN_FRAME_INTERVAL_MS || !shouldCapture()) return
            val encoded = FrameEncoder.encode(image) ?: return
            lastFrameAt = now
            onFrame(encoded)
        } finally {
            image.close()
        }
    }

    /** Release the camera. Safe to call repeatedly. */
    fun unbind() {
        bindGeneration++
        try { provider?.unbindAll() } catch (_: Exception) { }
        camera = null
        _status.value = CameraStatus.Off
    }

    fun shutdown() {
        unbind()
        analysisExecutor.shutdown()
    }

    companion object {
        /** ~12 fps upper bound; the real rate is also limited by the server round trip. */
        const val MIN_FRAME_INTERVAL_MS = 80L
    }
}
