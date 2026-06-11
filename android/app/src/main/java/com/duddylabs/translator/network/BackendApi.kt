package com.duddylabs.translator.network

import com.duddylabs.translator.data.SpeakerLanguage
import com.duddylabs.translator.data.TranslationStyle
import com.duddylabs.translator.data.TranslatorMode
import com.duddylabs.translator.data.VoiceGender
import com.duddylabs.translator.settings.SecureSettingsStore
import com.duddylabs.translator.settings.TranslatorSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class RealtimeSessionRequest(
    val mode: TranslatorMode,
    val sourceLanguage: SpeakerLanguage,
    val targetLanguage: SpeakerLanguage,
    val translationStyle: TranslationStyle,
    val voiceGender: VoiceGender,
)

data class RealtimeSessionCredentials(
    val clientSecret: String,
    val expiresAt: Long,
    val model: String,
)

data class BackendSessionCredentials(
    val sessionToken: String,
    val expiresAt: Long,
)

data class TextTranslationRequest(
    val text: String,
    val mode: TranslatorMode,
    val sourceLanguage: SpeakerLanguage,
    val targetLanguage: SpeakerLanguage,
    val translationStyle: TranslationStyle,
)

data class TextTranslationResult(
    val originalText: String,
    val literalTranslation: String,
    val polishedTranslation: String,
    val model: String,
)

class BackendApi(
    private val settingsStore: SecureSettingsStore,
    private val networkPreferenceClientFactory: NetworkPreferenceClientFactory? = null,
    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val maxAttempts = 3
    private var cachedSessionCredentials: BackendSessionCredentials? = null
    private var cachedSessionKey: SessionKey? = null

    suspend fun createRealtimeSession(request: RealtimeSessionRequest): RealtimeSessionCredentials =
        withContext(Dispatchers.IO) {
            val settings = settingsStore.settings.value
            val sessionToken = ensureSessionToken(settings)
            val body = JSONObject()
                .put("mode", request.mode.name)
                .put("source_language", request.sourceLanguage.locale)
                .put("target_language", request.targetLanguage.locale)
                .put("translation_style", request.translationStyle.name)
                .put("voice", request.voiceGender.realtimeVoice)
                .toString()
                .toRequestBody(jsonMediaType)

            val payload = executeJsonRequest(settings) {
                Request.Builder()
                    .url("${settings.backendBaseUrl.trimEnd('/')}/api/realtime/client-secret")
                    .header("Authorization", "Bearer $sessionToken")
                    .post(body)
                    .build()
            }
            RealtimeSessionCredentials(
                clientSecret = payload.getString("client_secret"),
                expiresAt = payload.optLong("expires_at", 0L),
                model = payload.optString("model", "gpt-realtime-2"),
            )
        }

    suspend fun translateText(request: TextTranslationRequest): TextTranslationResult =
        withContext(Dispatchers.IO) {
            val settings = settingsStore.settings.value
            val sessionToken = ensureSessionToken(settings)
            val body = JSONObject()
                .put("text", request.text)
                .put("mode", request.mode.name)
                .put("source_language", request.sourceLanguage.locale)
                .put("target_language", request.targetLanguage.locale)
                .put("translation_style", request.translationStyle.name)
                .toString()
                .toRequestBody(jsonMediaType)

            val payload = executeJsonRequest(settings) {
                Request.Builder()
                    .url("${settings.backendBaseUrl.trimEnd('/')}/api/translate/text")
                    .header("Authorization", "Bearer $sessionToken")
                    .post(body)
                    .build()
            }
            TextTranslationResult(
                originalText = payload.optString("original_text", request.text),
                literalTranslation = payload.optString("literal_translation"),
                polishedTranslation = payload.optString("polished_translation"),
                model = payload.optString("model"),
            )
        }

    private suspend fun ensureSessionToken(settings: TranslatorSettings): String {
        if (settings.appToken.isBlank()) {
            error("App token is not set. Open Settings and enter the backend app token.")
        }

        val sessionKey = SessionKey(settings.backendBaseUrl, settings.appToken)
        val now = System.currentTimeMillis() / 1_000
        val cached = cachedSessionCredentials
        if (cached != null && cachedSessionKey == sessionKey && cached.expiresAt > now + 10) {
            return cached.sessionToken
        }

        val payload = executeJsonRequest(settings) {
            Request.Builder()
                .url("${settings.backendBaseUrl.trimEnd('/')}/api/auth/session-token")
                .header("X-Duddy-App-Token", settings.appToken)
                .post(ByteArray(0).toRequestBody(jsonMediaType))
                .build()
        }
        val credentials = BackendSessionCredentials(
            sessionToken = payload.getString("session_token"),
            expiresAt = payload.optLong("expires_at", 0L),
        )
        cachedSessionCredentials = credentials
        cachedSessionKey = sessionKey
        return credentials.sessionToken
    }

    private suspend fun executeJsonRequest(settings: TranslatorSettings, requestFactory: () -> Request): JSONObject {
        var attempt = 1
        var lastError = "Backend request failed."
        val client = clientFor(settings)

        while (attempt <= maxAttempts) {
            try {
                client.newCall(requestFactory()).execute().use { response ->
                    val payload = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        return JSONObject(payload)
                    }

                    lastError = "Backend request failed: HTTP ${response.code} $payload"
                    if (!response.isRetryable() || attempt == maxAttempts) {
                        error(lastError)
                    }
                }
            } catch (error: IOException) {
                lastError = "Network request failed: ${error.message.orEmpty()}"
                if (attempt == maxAttempts) {
                    throw IllegalStateException(lastError, error)
                }
            }

            delay(250L * attempt)
            attempt += 1
        }

        error(lastError)
    }

    private fun clientFor(settings: TranslatorSettings): OkHttpClient =
        networkPreferenceClientFactory?.clientFor(
            baseClient = baseClient,
            preferCellularData = settings.preferCellularData,
            targetUrl = settings.backendBaseUrl,
        ) ?: baseClient

    private fun Response.isRetryable(): Boolean =
        code == 408 || code == 429 || code in 500..599

    private data class SessionKey(
        val backendBaseUrl: String,
        val appToken: String,
    )
}
