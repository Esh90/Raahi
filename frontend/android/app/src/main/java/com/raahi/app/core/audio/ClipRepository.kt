package com.raahi.app.core.audio

import android.content.Context
import android.util.Log
import com.raahi.app.core.model.ClipManifest
import com.raahi.app.core.network.RaahiApi
import com.raahi.app.core.network.RaahiJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Loads `/clips/manifest.json`, caches the prerecorded mp3 files under filesDir/clips and hands
 * out local files by clip id. Clips are only ever the ones the server lists (or `live_*` ids the
 * server sends: those are fetched from `/clips/live/<id>.mp3`). Nothing is bundled or invented.
 *
 * Two voices share the same clip ids: Urdu (`file`) and English (`en_file`, under `en/`). [language]
 * picks which one plays; an id without an English recording falls back to its Urdu file.
 */
class ClipRepository(context: Context, private val api: RaahiApi) {
    private val dir = File(context.filesDir, "clips").apply { mkdirs() }
    private val manifestFile = File(dir, "manifest.json")
    private val dlLock = Mutex()

    /** "ur" | "en" - which voice [fileFor] / [ensureFile] return. Set from the app settings. */
    @Volatile var language: String = "ur"
        set(value) {
            field = value
            recount()
        }

    private val _manifest = MutableStateFlow(ClipManifest.EMPTY)
    val manifest: StateFlow<ClipManifest> = _manifest.asStateFlow()

    private val _cachedCount = MutableStateFlow(0)
    val cachedCount: StateFlow<Int> = _cachedCount.asStateFlow()

    init {
        loadCachedManifest()
        recount()
    }

    private fun loadCachedManifest() {
        try {
            if (manifestFile.exists()) {
                _manifest.value = RaahiJson.decodeFromString<ClipManifest>(manifestFile.readText())
            }
        } catch (e: Exception) {
            Log.w(TAG, "cached manifest unreadable")
        }
    }

    private fun recount() {
        _cachedCount.value = _manifest.value.clips.count { fileFor(it.id)?.exists() == true }
    }

    /** Fetch the manifest from the server (keeps the cached copy if the server is unreachable). */
    suspend fun refreshManifest(): Boolean = try {
        val m = api.manifest()
        withContext(Dispatchers.IO) { manifestFile.writeText(RaahiJson.encodeToString(ClipManifest.serializer(), m)) }
        _manifest.value = m
        recount()
        true
    } catch (e: Exception) {
        Log.w(TAG, "manifest refresh failed: ${e.message}")
        false
    }

    /** Download every manifest clip that is not cached yet (idempotent, sequential). */
    suspend fun prefetchAll() {
        // both voices, so switching language later works offline; the current language first
        val first = if (language == "en") listOf(true, false) else listOf(false, true)
        for (english in first) {
            for (clip in _manifest.value.clips) {
                val rel = if (english) clip.enFile else clip.file
                if (rel.isBlank()) continue
                download(rel)
            }
        }
        recount()
    }

    private fun relativeFile(id: String): String? {
        _manifest.value[id]?.let { info ->
            if (language == "en" && info.enFile.isNotBlank()) return info.enFile
            return info.file.ifBlank { null }
        }
        // `live_*` clips are generated on demand by the server (server/voice/tts_live.py)
        return if (id.startsWith("live_")) "live/$id.mp3" else null
    }

    /** Local file location for [id] (may not exist yet). Null if the id is unknown. */
    fun fileFor(id: String): File? {
        val rel = relativeFile(id) ?: return null
        return fileAt(rel)
    }

    /** Cache location of a manifest path; null if it would escape the cache directory. */
    private fun fileAt(rel: String): File? = if (rel.contains("..")) null else File(dir, rel)

    /** Make sure [id] is on disk; downloads it when missing. Null when unavailable. */
    suspend fun ensureFile(id: String): File? {
        val rel = relativeFile(id) ?: return null
        val f = fileAt(rel) ?: return null
        if (f.exists() && f.length() > 0) return f
        return if (download(rel)) f else null
    }

    private suspend fun download(rel: String): Boolean = dlLock.withLock {
        downloadLocked(rel)
    }

    private suspend fun downloadLocked(rel: String): Boolean {
        val f = fileAt(rel) ?: return false
        if (f.exists() && f.length() > 0) return true
        return try {
            val bytes = api.clipBytes(rel)
            withContext(Dispatchers.IO) {
                f.parentFile?.mkdirs()
                val tmp = File(f.path + ".part")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(f)) {
                    f.writeBytes(bytes)
                    tmp.delete()
                }
            }
            _cachedCount.value = _manifest.value.clips.count { fileFor(it.id)?.exists() == true }
            true
        } catch (e: Exception) {
            Log.w(TAG, "clip $rel unavailable: ${e.message}")
            false
        }
    }

    fun clipInfo(id: String) = _manifest.value[id]

    companion object {
        private const val TAG = "ClipRepository"
    }
}
