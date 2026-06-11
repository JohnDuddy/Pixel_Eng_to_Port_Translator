package com.duddylabs.translator.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.duddylabs.translator.data.SpeakerLanguage
import org.junit.Rule
import org.junit.Test

class TranscriptCardTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun transcriptCardShowsPerMessageActions() {
        val row = TranscriptRow(
            speaker = SpeakerLanguage.ENGLISH,
            target = SpeakerLanguage.PORTUGUESE_BRAZIL,
            original = "Where is the pharmacy?",
            literal = "Onde e a farmacia?",
            polished = "Onde fica a farmacia?",
            timestamp = 1L,
        )

        composeTestRule.setContent {
            TranscriptCard(
                row = row,
                medical = false,
                onReplay = {},
                onRetry = {},
                onCorrect = {},
            )
        }

        composeTestRule.onNodeWithText("Replay").assertIsDisplayed()
        composeTestRule.onNodeWithText("Retry").assertIsDisplayed()
        composeTestRule.onNodeWithText("Correct").assertIsDisplayed()
    }
}
