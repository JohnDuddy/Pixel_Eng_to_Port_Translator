package com.duddylabs.translator.realtime

import com.duddylabs.translator.data.SpeakerLanguage
import com.duddylabs.translator.data.TranslationStyle
import com.duddylabs.translator.data.TranslatorMode

data class ConversationConfig(
    val mode: TranslatorMode,
    val sourceLanguage: SpeakerLanguage,
    val targetLanguage: SpeakerLanguage,
    val translationStyle: TranslationStyle,
)

sealed interface InterpreterEvent {
    data object Connecting : InterpreterEvent
    data object Connected : InterpreterEvent
    data object Listening : InterpreterEvent
    data object SpeakingTranslation : InterpreterEvent
    data object Stopped : InterpreterEvent
    data class OriginalTranscript(
        val language: SpeakerLanguage,
        val text: String,
        val timestamp: Long = System.currentTimeMillis(),
    ) : InterpreterEvent
    data class Translation(
        val sourceLanguage: SpeakerLanguage,
        val targetLanguage: SpeakerLanguage,
        val originalText: String,
        val literalTranslation: String,
        val polishedTranslation: String,
        val timestamp: Long = System.currentTimeMillis(),
    ) : InterpreterEvent
    data class Error(val message: String) : InterpreterEvent
}
