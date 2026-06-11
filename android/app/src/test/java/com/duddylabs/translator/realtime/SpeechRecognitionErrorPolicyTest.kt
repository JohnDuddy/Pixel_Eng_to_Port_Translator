package com.duddylabs.translator.realtime

import android.speech.SpeechRecognizer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechRecognitionErrorPolicyTest {
    @Test
    fun transientDisconnectsAndSilenceAreRetried() {
        val transientErrors = listOf(
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        )

        transientErrors.forEach { error ->
            assertTrue(error.toString(), SpeechRecognitionErrorPolicy.isTransient(error))
            assertTrue(error.toString(), SpeechRecognitionErrorPolicy.shouldRetry(error))
        }
    }

    @Test
    fun permissionAndAudioErrorsAreNotAutomaticallyRetried() {
        assertFalse(SpeechRecognitionErrorPolicy.shouldRetry(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertFalse(SpeechRecognitionErrorPolicy.shouldRetry(SpeechRecognizer.ERROR_AUDIO))
    }

    @Test
    fun unavailableOnDeviceLanguageCanFallBackToNetwork() {
        assertTrue(SpeechRecognitionErrorPolicy.shouldRetry(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED))
        assertTrue(SpeechRecognitionErrorPolicy.shouldRetry(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE))
    }
}
