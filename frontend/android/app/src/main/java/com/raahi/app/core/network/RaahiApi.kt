package com.raahi.app.core.network

import com.raahi.app.core.model.ClipManifest
import com.raahi.app.core.model.HealthInfo
import com.raahi.app.core.model.ScribeConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Thrown for any failed HTTP call; [message] is already user-safe. */
class ApiException(message: String, val code: Int = 0, cause: Throwable? = null) : IOException(message, cause)

val RaahiJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

/** Plain HTTP routes of the existing backend. Nothing is invented here: see server/main.py + server/voice/token.py. */
class RaahiApi(
    private val client: OkHttpClient,
    private val config: () -> ServerConfig?,
) {
    private val shortClient: OkHttpClient = client.newBuilder()
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private fun requireConfig(): ServerConfig =
        config() ?: throw ApiException("No server URL set")

    private suspend fun getText(path: String, http: OkHttpClient = shortClient): String =
        withContext(Dispatchers.IO) {
            val url = requireConfig().http(path)
            val req = try {
                Request.Builder().url(url).get().build()
            } catch (e: IllegalArgumentException) {
                throw ApiException("Invalid server URL", cause = e)
            }
            try {
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw ApiException("Server answered ${resp.code}", resp.code)
                    resp.body?.string() ?: throw ApiException("Empty response")
                }
            } catch (e: ApiException) {
                throw e
            } catch (e: IOException) {
                throw ApiException("Server unreachable", cause = e)
            }
        }

    /** `GET /health` -> {"ok":true,"ready":..,"mock":..,"phone":..,"mode":..,"depth":..} */
    suspend fun health(): HealthInfo = RaahiJson.decodeFromString(getText("/health"))

    /** `GET /scribe-token` -> {"token": "sutkn_..."} (single use, 15 min). Never logged. */
    suspend fun scribeToken(): String {
        val body = getText("/scribe-token")
        val token = RaahiJson.parseToJsonElement(body).jsonObject["token"]?.jsonPrimitive?.content
        return token?.takeIf { it.isNotBlank() } ?: throw ApiException("No token in response")
    }

    /** `GET /voice/scribe-config` -> keyterms + language for the Scribe connection. */
    suspend fun scribeConfig(): ScribeConfig = RaahiJson.decodeFromString(getText("/voice/scribe-config"))

    /** `GET /clips/manifest.json` */
    suspend fun manifest(): ClipManifest = RaahiJson.decodeFromString(getText("/clips/manifest.json"))

    /** `GET /clips/<file>` (also `live/<id>.mp3` for on-demand live-TTS clips). */
    suspend fun clipBytes(file: String): ByteArray = withContext(Dispatchers.IO) {
        val url = requireConfig().http("/clips/$file")
        try {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) throw ApiException("Clip $file: ${resp.code}", resp.code)
                resp.body?.bytes() ?: throw ApiException("Empty clip $file")
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: IOException) {
            throw ApiException("Server unreachable", cause = e)
        }
    }
}
