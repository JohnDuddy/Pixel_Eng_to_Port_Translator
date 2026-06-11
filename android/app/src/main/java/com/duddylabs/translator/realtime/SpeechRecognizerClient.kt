package com.duddylabs.translator.realtime

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.duddylabs.translator.data.SpeakerLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface SpeechRecognitionEvent {
    data class Partial(val language: SpeakerLanguage, val text: String) : SpeechRecognitionEvent
    data class Final(val language: SpeakerLanguage, val text: String) : SpeechRecognitionEvent
    data class Error(
        val message: String,
        val shouldRetrySameLanguage: Boolean,
        val isTransient: Boolean = false,
    ) : SpeechRecognitionEvent
}

class SpeechRecognizerClient(
    private val context: Context,
) {
    private var recognizer: SpeechRecognizer? = null
    private var recognizerGeneration = 0
    private var preferOnDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    suspend fun startListening(
        language: SpeakerLanguage,
        onEvent: (SpeechRecognitionEvent) -> Unit,
    ) {
        withContext(Dispatchers.Main.immediate) {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                onEvent(
                    SpeechRecognitionEvent.Error(
                        message = "Speech recognition is not available on this Pixel 9.",
                        shouldRetrySameLanguage = false,
                    ),
                )
                return@withContext
            }

            try {
                val handle = createFreshRecognizer()
                handle.recognizer.setRecognitionListener(
                    createRecognitionListener(
                        language = language,
                        onDevice = handle.onDevice,
                        generation = handle.generation,
                        onEvent = onEvent,
                    ),
                )
                handle.recognizer.startListening(createRecognizerIntent(language, handle.onDevice))
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    throw error
                }
                resetRecognizer()
                onEvent(
                    SpeechRecognitionEvent.Error(
                        message = "Speech recognition could not start. Details: ${error.message.orEmpty()} Type the phrase below if voice is unreliable.",
                        shouldRetrySameLanguage = true,
                        isTransient = true,
                    ),
                )
            }
        }
    }

    suspend fun stopListening() {
        withContext(Dispatchers.Main.immediate) {
            runCatching { recognizer?.stopListening() }
        }
    }

    suspend fun cancel() {
        withContext(Dispatchers.Main.immediate) {
            resetRecognizer()
        }
    }

    suspend fun close() {
        withContext(Dispatchers.Main.immediate) {
            resetRecognizer()
        }
    }

    private fun createRecognitionListener(
        language: SpeakerLanguage,
        onDevice: Boolean,
        generation: Int,
        onEvent: (SpeechRecognitionEvent) -> Unit,
    ): RecognitionListener =
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                if (generation != recognizerGeneration) {
                    return
                }
                val text = firstRecognizedText(partialResults)
                if (text.isNotBlank()) {
                    onEvent(SpeechRecognitionEvent.Partial(language, text))
                }
            }

            override fun onError(error: Int) {
                if (generation != recognizerGeneration) {
                    return
                }

                val shouldFallBackToNetwork = onDevice &&
                    (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                        error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
                if (shouldFallBackToNetwork) {
                    preferOnDevice = false
                } else if (!onDevice &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(context) &&
                    SpeechRecognitionErrorPolicy.isNetworkOrServiceFailure(error)
                ) {
                    preferOnDevice = true
                }
                resetRecognizer()

                onEvent(
                    SpeechRecognitionEvent.Error(
                        message = if (shouldFallBackToNetwork) {
                            "The on-device ${language.label} speech model is unavailable, so I switched to the network recognizer."
                        } else {
                            recognitionErrorMessage(error)
                        },
                        shouldRetrySameLanguage = SpeechRecognitionErrorPolicy.shouldRetry(error),
                        isTransient = shouldFallBackToNetwork || SpeechRecognitionErrorPolicy.isTransient(error),
                    ),
                )
            }

            override fun onResults(results: Bundle?) {
                if (generation != recognizerGeneration) {
                    return
                }
                val text = firstRecognizedText(results)
                if (text.isBlank()) {
                    onEvent(
                        SpeechRecognitionEvent.Error(
                            message = "I did not catch that. Try speaking again.",
                            shouldRetrySameLanguage = true,
                            isTransient = true,
                        ),
                    )
                    return
                }

                onEvent(SpeechRecognitionEvent.Final(language, text))
            }
        }

    private fun firstRecognizedText(results: Bundle?): String =
        results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()

    private fun createRecognizerIntent(language: SpeakerLanguage, onDevice: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.locale)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, onDevice)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
        }

    private fun createFreshRecognizer(): RecognizerHandle {
        resetRecognizer()
        val onDevice = preferOnDevice && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val freshRecognizer = if (onDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = freshRecognizer
        return RecognizerHandle(
            recognizer = freshRecognizer,
            generation = recognizerGeneration,
            onDevice = onDevice,
        )
    }

    private fun resetRecognizer() {
        recognizerGeneration += 1
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun recognitionErrorMessage(error: Int): String {
        val reason = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "The microphone audio was not available."
            SpeechRecognizer.ERROR_CLIENT -> "The Pixel 9 speech recognizer stopped the session, so I reset it."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is not allowed."
            SpeechRecognizer.ERROR_NETWORK -> "Speech recognition had a network problem."
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition timed out while using the network."
            SpeechRecognizer.ERROR_NO_MATCH -> "I heard audio but could not turn it into words."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition was busy, so I reset it."
            SpeechRecognizer.ERROR_SERVER -> "The speech recognition service had a server problem."
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "The Pixel 9 speech recognition service briefly disconnected, so I am reconnecting it."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I did not hear speech soon enough."
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Speech recognition was temporarily rate limited."
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "The selected speech language is not supported by this recognizer."
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "The selected speech language model is not installed."
            else -> "Speech recognition stopped with error code $error."
        }

        return "$reason Tap the speaker button again and start speaking right away, or type the phrase below."
    }

    private data class RecognizerHandle(
        val recognizer: SpeechRecognizer,
        val generation: Int,
        val onDevice: Boolean,
    )
}

object SpeechRecognitionErrorPolicy {
    fun shouldRetry(error: Int): Boolean =
        isTransient(error) ||
            error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
            error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE

    fun isTransient(error: Int): Boolean =
        error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
            error == SpeechRecognizer.ERROR_NETWORK ||
            error == SpeechRecognizer.ERROR_SERVER ||
            error == SpeechRecognizer.ERROR_CLIENT ||
            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
            error == SpeechRecognizer.ERROR_NO_MATCH ||
            error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
            error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS ||
            error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED

    fun isNetworkOrServiceFailure(error: Int): Boolean =
        error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
            error == SpeechRecognizer.ERROR_NETWORK ||
            error == SpeechRecognizer.ERROR_SERVER ||
            error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS ||
            error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED
}
