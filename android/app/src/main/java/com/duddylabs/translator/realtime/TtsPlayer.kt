package com.duddylabs.translator.realtime

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.duddylabs.translator.data.SpeakerLanguage
import com.duddylabs.translator.data.VoiceGender
import com.duddylabs.translator.data.VoiceSpeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class TtsPlayer(
    private val context: Context,
    private val onDone: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: Pair<String, SpeakerLanguage>? = null
    private var voiceSpeed = VoiceSpeed.NORMAL
    private var voiceGender = VoiceGender.FEMALE

    fun configure(speed: VoiceSpeed, gender: VoiceGender) {
        voiceSpeed = speed
        voiceGender = gender
    }

    fun ensureStarted() {
        if (textToSpeech != null) {
            return
        }

        textToSpeech = TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                textToSpeech?.setOnUtteranceProgressListener(utteranceListener)
                pendingSpeech?.let { (text, language) ->
                    pendingSpeech = null
                    speak(text, language)
                }
            }
        }
    }

    suspend fun close() {
        withContext(Dispatchers.Main.immediate) {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
            textToSpeech = null
            ttsReady = false
            pendingSpeech = null
        }
    }

    fun speak(text: String, language: SpeakerLanguage) {
        if (text.isBlank()) {
            return
        }

        if (!ttsReady) {
            pendingSpeech = text to language
            ensureStarted()
            return
        }

        val locale = when (language) {
            SpeakerLanguage.ENGLISH -> Locale.US
            SpeakerLanguage.PORTUGUESE_BRAZIL -> Locale("pt", "BR")
        }

        val availability = textToSpeech?.setLanguage(locale)
        if (
            availability == TextToSpeech.LANG_MISSING_DATA ||
            availability == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            onError("Portuguese voice is not installed on this Pixel 9. Install the Portuguese (Brazil) voice in Android Text-to-speech settings.")
            return
        }

        applyPreferredVoice(locale)
        textToSpeech?.setSpeechRate(speechRate())
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "duddy-translation")
    }

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            onDone()
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            onDone()
        }
    }

    private fun speechRate(): Float =
        when (voiceSpeed) {
            VoiceSpeed.SLOW -> 0.78f
            VoiceSpeed.NORMAL -> 0.95f
            VoiceSpeed.FAST -> 1.12f
        }

    private fun applyPreferredVoice(locale: Locale) {
        val voices = textToSpeech?.voices.orEmpty()
        val localeVoices = voices.filter { voice ->
            voice.locale.language == locale.language &&
                (locale.country.isBlank() || voice.locale.country == locale.country)
        }
        val hints = when (voiceGender) {
            VoiceGender.FEMALE -> listOf("female", "mulher", "feminina", "maria", "luciana", "vitoria", "camila")
            VoiceGender.MALE -> listOf("male", "homem", "masculina", "joao", "felipe", "ricardo")
        }
        val preferred = localeVoices.firstOrNull { voice ->
            val name = voice.name.lowercase()
            hints.any { hint -> name.contains(hint) }
        } ?: localeVoices.firstOrNull()

        if (preferred != null) {
            textToSpeech?.voice = preferred
        }
    }
}
