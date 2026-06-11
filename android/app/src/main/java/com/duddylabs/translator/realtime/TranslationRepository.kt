package com.duddylabs.translator.realtime

import com.duddylabs.translator.network.BackendApi
import com.duddylabs.translator.network.TextTranslationRequest
import com.duddylabs.translator.network.TextTranslationResult
import com.duddylabs.translator.settings.SecureSettingsStore

class TranslationRepository(
    private val backendApi: BackendApi,
    private val settingsStore: SecureSettingsStore,
    private val offlineMedicalTranslator: OfflineMedicalTranslator,
) {
    suspend fun translate(text: String, config: ConversationConfig): TextTranslationResult {
        val request = TextTranslationRequest(
            text = text,
            mode = config.mode,
            sourceLanguage = config.sourceLanguage,
            targetLanguage = config.targetLanguage,
            translationStyle = config.translationStyle,
        )
        return try {
            backendApi.translateText(request)
        } catch (error: Throwable) {
            val fallback = translateOffline(text, config)
            if (settingsStore.settings.value.offlineMedicalFallbackEnabled && fallback != null) {
                fallback
            } else {
                throw error
            }
        }
    }

    fun translateOffline(text: String, config: ConversationConfig): TextTranslationResult? =
        offlineMedicalTranslator.translate(
            text = text,
            sourceLanguage = config.sourceLanguage,
            targetLanguage = config.targetLanguage,
            mode = config.mode,
        )
}
