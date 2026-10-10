package com.raahi.app.core.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Captures 16 kHz mono PCM16 in ~100 ms chunks. Only runs while the user is talking. */
class MicStreamer(private val scope: CoroutineScope) {
    private var job: Job? = null

    /** Returns false if the microphone could not be opened (permission revoked, in use by another app). */
    @SuppressLint("MissingPermission") // permission is checked by VoiceController before start()
    fun start(onChunk: (ByteArray, Int) -> Unit, onLevel: (Float) -> Unit = {}): Boolean {
        if (job?.isActive == true) return true
        val rate = ScribeClient.SAMPLE_RATE
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val chunkBytes = rate / 10 * 2 // 100 ms
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, chunkBytes * 4),
            )
        } catch (e: Exception) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        try {
            rec.startRecording()
        } catch (e: Exception) {
            rec.release()
            return false
        }
        job = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(chunkBytes)
            try {
                while (isActive) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) {
                        if (n < 0) break
                        continue
                    }
                    onLevel(level(buf, n))
                    onChunk(buf, n)
                }
            } catch (e: Exception) {
                Log.w(TAG, "mic read stopped: ${e.javaClass.simpleName}")
            } finally {
                try { rec.stop() } catch (_: Exception) { }
                rec.release()
            }
        }
        return true
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun level(buf: ByteArray, n: Int): Float {
        var sum = 0.0
        var i = 0
        while (i + 1 < n) {
            val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()
            sum += s.toDouble() * s
            i += 2
        }
        val rms = kotlin.math.sqrt(sum / (n / 2).coerceAtLeast(1))
        return (rms / 6000.0).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        private const val TAG = "MicStreamer"
    }
}
