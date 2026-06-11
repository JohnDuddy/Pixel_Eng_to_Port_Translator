package com.duddylabs.translator.realtime

import com.duddylabs.translator.data.SpeakerLanguage
import com.duddylabs.translator.data.TranslatorMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineMedicalTranslatorTest {
    private val translator = OfflineMedicalTranslator()

    @Test
    fun translate_exactMedicalTermToPortuguese() {
        val result = translator.translate(
            text = "Squamous cell carcinoma",
            sourceLanguage = SpeakerLanguage.ENGLISH,
            targetLanguage = SpeakerLanguage.PORTUGUESE_BRAZIL,
            mode = TranslatorMode.MEDICAL,
        )

        assertEquals("Carcinoma espinocelular (CEC)", result?.polishedTranslation)
        assertEquals("offline-medical-phrasebook", result?.model)
    }

    @Test
    fun translate_exactMedicalTermToEnglish() {
        val result = translator.translate(
            text = "Cirurgia de Mohs",
            sourceLanguage = SpeakerLanguage.PORTUGUESE_BRAZIL,
            targetLanguage = SpeakerLanguage.ENGLISH,
            mode = TranslatorMode.MEDICAL,
        )

        assertEquals("Mohs surgery", result?.polishedTranslation)
    }

    @Test
    fun translate_recognizedTermInsideLongerSentence() {
        val result = translator.translate(
            text = "Were the surgical margins clear?",
            sourceLanguage = SpeakerLanguage.ENGLISH,
            targetLanguage = SpeakerLanguage.PORTUGUESE_BRAZIL,
            mode = TranslatorMode.MEDICAL,
        )

        assertEquals("As margens cirúrgicas estão livres?", result?.polishedTranslation)
    }

    @Test
    fun translate_unknownPhraseUsesSafeInterpreterFallback() {
        val result = translator.translate(
            text = "This sentence is not in the phrasebook",
            sourceLanguage = SpeakerLanguage.ENGLISH,
            targetLanguage = SpeakerLanguage.PORTUGUESE_BRAZIL,
            mode = TranslatorMode.MEDICAL,
        )

        assertTrue(result?.polishedTranslation.orEmpty().contains("intérprete médico qualificado"))
    }

    @Test
    fun translate_nonMedicalModeReturnsNull() {
        val result = translator.translate(
            text = "Squamous cell carcinoma",
            sourceLanguage = SpeakerLanguage.ENGLISH,
            targetLanguage = SpeakerLanguage.PORTUGUESE_BRAZIL,
            mode = TranslatorMode.PUSH_TO_TALK,
        )

        assertEquals(null, result)
    }
}
