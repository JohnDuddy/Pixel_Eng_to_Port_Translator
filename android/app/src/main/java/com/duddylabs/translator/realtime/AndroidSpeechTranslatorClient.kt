package com.duddylabs.translator.realtime

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import com.duddylabs.translator.data.SpeakerLanguage
import com.duddylabs.translator.data.TranslatorMode
import com.duddylabs.translator.data.opposite
import com.duddylabs.translator.network.BackendApi
import com.duddylabs.translator.network.TextTranslationRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class AndroidSpeechTranslatorClient(
    private val context: Context,
    private val backendApi: BackendApi,
) : RealtimeTranslatorClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _events = MutableSharedFlow<InterpreterEvent>(extraBufferCapacity = 32)
    override val events: Flow<InterpreterEvent> = _events.asSharedFlow()

    private var currentConfig: ConversationConfig = ConversationConfig(
        mode = TranslatorMode.PUSH_TO_TALK,
        sourceLanguage = SpeakerLanguage.ENGLISH,
        targetLanguage = SpeakerLanguage.PORTUGUESE_BRAZIL,
        translationStyle = com.duddylabs.translator.data.TranslationStyle.NATURAL,
    )
    private var recognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: Pair<String, SpeakerLanguage>? = null
    private var lastTranslation: InterpreterEvent.Translation? = null

    override suspend fun start(config: ConversationConfig) {
        currentConfig = config
        ensureTextToSpeech()
    }

    override suspend fun beginPushToTalk(speakerLanguage: SpeakerLanguage) {
        val listeningConfig = currentConfig.copy(
            sourceLanguage = speakerLanguage,
            targetLanguage = speakerLanguage.opposite(),
        )
        currentConfig = listeningConfig
        _events.emit(InterpreterEvent.Listening)

        withContext(Dispatchers.Main.immediate) {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                _events.tryEmit(InterpreterEvent.Error("Speech recognition is not available on this Pixel 9."))
                return@withContext
            }

            val speechRecognizer = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
                recognizer = it
            }
            speechRecognizer.setRecognitionListener(createRecognitionListener(listeningConfig))
            speechRecognizer.startListening(createRecognizerIntent(speakerLanguage))
        }
    }

    override suspend fun endPushToTalk() {
        withContext(Dispatchers.Main.immediate) {
            recognizer?.stopListening()
        }
    }

    override suspend fun startContinuous() {
        beginPushToTalk(currentConfig.sourceLanguage)
    }

    override suspend fun stopContinuous() {
        endPushToTalk()
    }

    override suspend fun speakTranslation(text: String, language: SpeakerLanguage) {
        speak(text, language)
    }

    override suspend fun repeatLastTranslation() {
        lastTranslation?.let {
            _events.emit(it)
            speak(it.polishedTranslation, it.targetLanguage)
        }
    }

    override suspend fun close() {
        withContext(Dispatchers.Main.immediate) {
            recognizer?.cancel()
            recognizer?.destroy()
            recognizer = null
            textToSpeech?.stop()
            textToSpeech?.shutdown()
            textToSpeech = null
            ttsReady = false
            pendingSpeech = null
        }
        _events.emit(InterpreterEvent.Stopped)
    }

    private fun createRecognitionListener(config: ConversationConfig): RecognitionListener =
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onError(error: Int) {
                scope.launch {
                    _events.emit(InterpreterEvent.Error(recognitionErrorMessage(error)))
                }
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()

                if (text.isNullOrBlank()) {
                    scope.launch {
                        _events.emit(InterpreterEvent.Error("I did not catch that. Try speaking again."))
                    }
                    return
                }

                scope.launch {
                    translateRecognizedText(text, config)
                }
            }
        }

    private suspend fun translateRecognizedText(text: String, config: ConversationConfig) {
        _events.emit(InterpreterEvent.OriginalTranscript(config.sourceLanguage, text))
        try {
            val result = backendApi.translateText(
                TextTranslationRequest(
                    text = text,
                    mode = config.mode,
                    sourceLanguage = config.sourceLanguage,
                    targetLanguage = config.targetLanguage,
                    translationStyle = config.translationStyle,
                ),
            )
            val translation = InterpreterEvent.Translation(
                sourceLanguage = config.sourceLanguage,
                targetLanguage = config.targetLanguage,
                originalText = result.originalText,
                literalTranslation = result.literalTranslation,
                polishedTranslation = result.polishedTranslation,
            )
            lastTranslation = translation
            _events.emit(InterpreterEvent.SpeakingTranslation)
            _events.emit(translation)
            speak(result.polishedTranslation, config.targetLanguage)
        } catch (error: Throwable) {
            _events.emit(
                InterpreterEvent.Error(
                    "Text translation failed. Check that the backend is running, OPENAI_API_KEY is set, and the Pixel 9 USB tunnel is active.\n\nDetails: ${error.message.orEmpty()}",
                ),
            )
        }
    }

    private fun createRecognizerIntent(language: SpeakerLanguage): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.locale)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
        }

    private fun recognitionErrorMessage(error: Int): String {
        val reason = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "The microphone audio was not available."
            SpeechRecognizer.ERROR_CLIENT -> "The Pixel 9 speech recognizer stopped the session."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is not allowed."
            SpeechRecognizer.ERROR_NETWORK -> "Speech recognition had a network problem."
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition timed out while using the network."
            SpeechRecognizer.ERROR_NO_MATCH -> "I heard audio but could not turn it into words."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition is already busy."
            SpeechRecognizer.ERROR_SERVER -> "The speech recognition service had a server problem."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I did not hear speech soon enough."
            else -> "Speech recognition stopped with error code $error."
        }

        return "$reason Tap the speaker button again and start speaking right away, or type the phrase below."
    }

    private fun ensureTextToSpeech() {
        if (textToSpeech != null) {
            return
        }
        textToSpeech = TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                pendingSpeech?.let { (text, language) ->
                    pendingSpeech = null
                    speak(text, language)
                }
            }
        }
    }

    private fun speak(text: String, language: SpeakerLanguage) {
        if (text.isBlank()) {
            return
        }
        if (!ttsReady) {
            pendingSpeech = text to language
            ensureTextToSpeech()
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
            scope.launch {
                _events.emit(InterpreterEvent.Error("Portuguese voice is not installed on this Pixel 9. Install the Portuguese (Brazil) voice in Android Text-to-speech settings."))
            }
            return
        }

        textToSpeech?.setSpeechRate(0.95f)
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "duddy-translation")
    }
}
