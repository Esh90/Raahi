package com.raahi.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.raahi.app.core.network.RaahiJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

private val Context.historyStore: DataStore<Preferences> by preferencesDataStore(name = "raahi_history")

object TimelineType {
    const val START = "start"
    const val WARNING = "warning"
    const val STOP = "stop"
    const val FOUND = "found"
    const val LOST = "lost"
    const val END = "end"
}

object Outcome {
    const val COMPLETED = "completed"
    const val FOUND = "found"
    const val NOT_FOUND = "not_found"
    const val STOPPED = "stopped"
}

@Serializable
data class TimelineEvent(val at: Long, val type: String, val text: String)

/** One finished session, built from real server results only (see domain/SessionRecorder). */
@Serializable
data class SessionRecord(
    val id: String,
    /** "guidance" or "find" */
    val kind: String,
    val target: String? = null,
    val startedAt: Long,
    val endedAt: Long,
    val objects: Int = 0,
    val warnings: Int = 0,
    val stops: Int = 0,
    val found: Int = 0,
    val outcome: String = Outcome.COMPLETED,
    val timeline: List<TimelineEvent> = emptyList(),
) {
    val durationMs: Long get() = (endedAt - startedAt).coerceAtLeast(0)
}

/** Sessions live only on this phone (Privacy screen: "Settings and history stay on your device"). */
class HistoryRepository(private val context: Context) {
    private val key = stringPreferencesKey("sessions_json")
    private val serializer = ListSerializer(SessionRecord.serializer())

    val sessions: Flow<List<SessionRecord>> = context.historyStore.data.map { p -> decode(p[key]) }

    private fun decode(raw: String?): List<SessionRecord> = try {
        if (raw.isNullOrBlank()) emptyList() else RaahiJson.decodeFromString(serializer, raw)
    } catch (_: Exception) {
        emptyList()
    }

    suspend fun add(record: SessionRecord) {
        context.historyStore.edit { p ->
            val list = (listOf(record) + decode(p[key])).take(MAX_SESSIONS)
            p[key] = RaahiJson.encodeToString(serializer, list)
        }
    }

    suspend fun get(id: String): SessionRecord? = sessions.first().firstOrNull { it.id == id }

    suspend fun clear() {
        context.historyStore.edit { it.remove(key) }
    }

    companion object {
        const val MAX_SESSIONS = 100
    }
}
