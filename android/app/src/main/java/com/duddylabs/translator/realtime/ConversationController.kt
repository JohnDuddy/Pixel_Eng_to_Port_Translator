package com.duddylabs.translator.realtime

import android.content.Context
import com.duddylabs.translator.data.SpeakerLanguage
import com.duddylabs.translator.data.TranslationStyle
import com.duddylabs.translator.data.TranslatorMode
import com.duddylabs.translator.data.opposite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ConversationController(
    context: Context,
    private val translationRepository: TranslationRepository,
) : RealtimeTranslatorClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val speechRecognizerClient = SpeechRecognizerClient(context)
    private val ttsPlayer = TtsPlayer(
        context = context,
        onDone = { onSpeechDone() },
        onError = { message -> scope.launch { _events.emit(InterpreterEvent.Error(message)) } },
    )
    private val _events = MutableSharedFlow<InterpreterEvent>(extraBufferCapacity = 32)
    override val events: Flow<InterpreterEvent> = _events.asSharedFlow()

    private var currentConfig = ConversationConfig(
        mode = TranslatorMode.PUSH_TO_TALK,
        sourceLanguage = SpeakerLanguage.ENGLISH,
        targetLanguage = SpeakerLanguage.PORTUGUESE_BRAZIL,
        translationStyle = TranslationStyle.NATURAL,
    )
    private var lastTranslation: InterpreterEvent.Translation? = null
    private var continuousActive = false
    private var continuousLanguage = SpeakerLanguage.ENGLISH
    private var recognitionRetryCount = 0

    override suspend fun start(config: ConversationConfig) {
        currentConfig = config
        ttsPlayer.configure(config.voiceSpeed, config.voiceGender)
        ttsPlayer.ensureStarted()
        _events.emit(InterpreterEvent.Connected)
    }

    override suspend fun beginPushToTalk(speakerLanguage: SpeakerLanguage) {
        continuousActive = false
        recognitionRetryCount = 0
        startListening(speakerLanguage)
    }

    override suspend fun endPushToTalk() {
        speechRecognizerClient.stopListening()
    }

    override suspend fun startContinuous() {
        continuousActive = true
        continuousLanguage = currentConfig.sourceLanguage
        recognitionRetryCount = 0
        startListening(continuousLanguage)
    }

    override suspend fun stopContinuous() {
        continuousActive = false
        speechRecognizerClient.cancel()
        _events.emit(InterpreterEvent.Stopped)
    }

    override suspend fun speakTranslation(text: String, language: SpeakerLanguage) {
        ttsPlayer.speak(text, language)
    }

    override suspend fun repeatLastTranslation() {
        lastTranslation?.let { translation ->
            _events.emit(InterpreterEvent.SpeakingTranslation)
            ttsPlayer.speak(translation.polishedTranslation, translation.targetLanguage)
        }
    }

    override suspend fun close() {
        continuousActive = false
        speechRecognizerClient.close()
        ttsPlayer.close()
        _events.emit(InterpreterEvent.Stopped)
    }

    private suspend fun startListening(speakerLanguage: SpeakerLanguage) {
        val listeningConfig = currentConfig.copy(
            sourceLanguage = speakerLanguage,
            targetLanguage = speakerLanguage.opposite(),
        )
        currentConfig = listeningConfig
        _events.emit(InterpreterEvent.Listening)

        speechRecognizerClient.startListening(speakerLanguage) { event ->
            handleSpeechRecognitionEvent(event, listeningConfig)
        }
    }

    private fun handleSpeechRecognitionEvent(
        event: SpeechRecognitionEvent,
        config: ConversationConfig,
    ) {
        when (event) {
            is SpeechRecognitionEvent.Partial -> scope.launch {
                _events.emit(InterpreterEvent.PartialTranscript(event.language, event.text))
            }
            is SpeechRecognitionEvent.Final -> scope.launch {
                recognitionRetryCount = 0
                translateRecognizedText(event.text, config)
            }
            is SpeechRecognitionEvent.Error -> scope.launch {
                if (continuousActive && event.isTransient) {
                    recognitionRetryCount += 1
                    _events.emit(InterpreterEvent.Listening)
                    delay(continuousRetryDelayMs(recognitionRetryCount))
                    if (continuousActive) {
                        startListening(config.sourceLanguage)
                    }
                    return@launch
                }

                if (event.shouldRetrySameLanguage && recognitionRetryCount < maxRecognitionRetries) {
                    recognitionRetryCount += 1
                    _events.emit(InterpreterEvent.Listening)
                    delay(450)
                    startListening(config.sourceLanguage)
                    return@launch
                }

                recognitionRetryCount = 0
                _events.emit(InterpreterEvent.Error(event.message))
                if (continuousActive && !event.shouldRetrySameLanguage) {
                    continuousActive = false
                }
            }
        }
    }

    private suspend fun translateRecognizedText(text: String, config: ConversationConfig) {
        _events.emit(InterpreterEvent.OriginalTranscript(config.sourceLanguage, text))
        try {
            val result = translationRepository.translate(text, config)
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
            continuousLanguage = config.targetLanguage
            ttsPlayer.speak(result.polishedTranslation, config.targetLanguage)
        } catch (error: Throwable) {
            _events.emit(
                InterpreterEvent.Error(
                    buildTranslationFailureMessage(error),
                ),
            )
            if (continuousActive) {
                startListening(config.sourceLanguage)
            }
        }
    }

    private fun buildTranslationFailureMessage(error: Throwable): String {
        val details = error.message.orEmpty()
        val guidance = when {
            details.contains("OPENAI_API_KEY", ignoreCase = true) ||
                details.contains("OpenAI API key", ignoreCase = true) ->
                "The backend answered, but it does not have OPENAI_API_KEY configured. Check backend\\.env and restart scripts\\run-backend.ps1."
            details.contains("HTTP 401", ignoreCase = true) ||
                details.contains("HTTP 403", ignoreCase = true) ->
                "The backend answered, but the app token was rejected. Check Settings > App Token and backend\\.env ALLOWED_APP_TOKEN."
            details.contains("Network request failed", ignoreCase = true) ||
                details.contains("Failed to connect", ignoreCase = true) ||
                details.contains("Connection refused", ignoreCase = true) ||
                details.contains("No route to host", ignoreCase = true) ||
                details.contains("timed out", ignoreCase = true) ->
                "The app could not reach the backend. If you are out of Wi-Fi range, a local PC/LAN Backend URL will not work over 5G. Use USB, the phone hotspot, or a public HTTPS backend URL."
            else ->
                "Check that the backend is running and that Settings > Backend URL matches USB, Wi-Fi, hotspot, or public 5G mode."
        }

        return "Text translation failed. $guidance\n\nDetails: $details"
    }

    private fun onSpeechDone() {
        if (continuousActive) {
            recognitionRetryCount = 0
            scope.launch { startListening(continuousLanguage) }
        }
    }

    private fun continuousRetryDelayMs(retryCount: Int): Long =
        (continuousRetryBaseDelayMs * retryCount.coerceAtMost(continuousRetryDelayMultiplierLimit))
            .coerceAtMost(continuousRetryMaxDelayMs)

    private companion object {
        private const val maxRecognitionRetries = 2
        private const val continuousRetryBaseDelayMs = 450L
        private const val continuousRetryDelayMultiplierLimit = 5
        private const val continuousRetryMaxDelayMs = 2_250L
    }
}
