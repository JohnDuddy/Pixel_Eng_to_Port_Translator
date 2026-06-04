package com.duddylabs.translator.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SpeakerLanguageTest {

    @Test
    fun opposite_swapsBetweenTheTwoSupportedLanguages() {
        assertEquals(SpeakerLanguage.PORTUGUESE_BRAZIL, SpeakerLanguage.ENGLISH.opposite())
        assertEquals(SpeakerLanguage.ENGLISH, SpeakerLanguage.PORTUGUESE_BRAZIL.opposite())
    }

    @Test
    fun opposite_isAlwaysDifferentFromInput() {
        SpeakerLanguage.entries.forEach { language ->
            assertNotEquals(language, language.opposite())
        }
    }

    @Test
    fun locale_matchesBcp47TagsTheBackendExpects() {
        assertEquals("en-US", SpeakerLanguage.ENGLISH.locale)
        assertEquals("pt-BR", SpeakerLanguage.PORTUGUESE_BRAZIL.locale)
    }

    @Test
    fun voiceGender_mapsToRealtimeVoiceNames() {
        assertEquals("marin", VoiceGender.FEMALE.realtimeVoice)
        assertEquals("cedar", VoiceGender.MALE.realtimeVoice)
    }
}
