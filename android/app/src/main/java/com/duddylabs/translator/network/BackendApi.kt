package com.duddylabs.translator.network

import com.duddylabs.translator.data.SpeakerLanguage
import com.duddylabs.translator.data.TranslationStyle
import com.duddylabs.translator.data.TranslatorMode
import com.duddylabs.translator.data.VoiceGender
import com.duddylabs.translator.settings.SecureSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
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
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun createRealtimeSession(request: RealtimeSessionRequest): RealtimeSessionCredentials =
        withContext(Dispatchers.IO) {
            val settings = settingsStore.settings.value
            val body = JSONObject()
                .put("mode", request.mode.name)
                .put("source_language", request.sourceLanguage.locale)
                .put("target_language", request.targetLanguage.locale)
                .put("translation_style", request.translationStyle.name)
                .put("voice", request.voiceGender.realtimeVoice)
                .toString()
                .toRequestBody(jsonMediaType)

            val httpRequest = Request.Builder()
                .url("${settings.backendBaseUrl.trimEnd('/')}/api/realtime/client-secret")
                .header("X-Duddy-App-Token", settings.appToken)
                .post(body)
                .build()

            client.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    error("Backend session request failed: HTTP ${response.code}")
                }

                val payload = JSONObject(response.body?.string().orEmpty())
                RealtimeSessionCredentials(
                    clientSecret = payload.getString("client_secret"),
                    expiresAt = payload.optLong("expires_at", 0L),
                    model = payload.optString("model", "gpt-realtime"),
                )
            }
        }

    suspend fun translateText(request: TextTranslationRequest): TextTranslationResult =
        withContext(Dispatchers.IO) {
            val settings = settingsStore.settings.value
            val body = JSONObject()
                .put("text", request.text)
                .put("mode", request.mode.name)
                .put("source_language", request.sourceLanguage.locale)
                .put("target_language", request.targetLanguage.locale)
                .put("translation_style", request.translationStyle.name)
                .toString()
                .toRequestBody(jsonMediaType)

            val httpRequest = Request.Builder()
                .url("${settings.backendBaseUrl.trimEnd('/')}/api/translate/text")
                .header("X-Duddy-App-Token", settings.appToken)
                .post(body)
                .build()

            client.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    val detail = response.body?.string().orEmpty()
                    error("Backend text translation failed: HTTP ${response.code} $detail")
                }

                val payload = JSONObject(response.body?.string().orEmpty())
                TextTranslationResult(
                    originalText = payload.optString("original_text", request.text),
                    literalTranslation = payload.optString("literal_translation"),
                    polishedTranslation = payload.optString("polished_translation"),
                    model = payload.optString("model"),
                )
            }
        }
}
