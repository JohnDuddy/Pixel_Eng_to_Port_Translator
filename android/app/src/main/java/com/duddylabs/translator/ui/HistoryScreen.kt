@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.duddylabs.translator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HistoryScreen(state: TranslatorUiState, viewModel: TranslatorViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = state.historySearch,
            onValueChange = viewModel::updateHistorySearch,
            label = { Text("Search history") },
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { viewModel.exportMarkdown() }) {
                Text("Export Markdown")
            }
            OutlinedButton(onClick = { }) {
                Text("Export TXT")
            }
            OutlinedButton(onClick = { }) {
                Text("Export PDF")
            }
            OutlinedButton(onClick = viewModel::clearHistory) {
                Text("Clear History")
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(
                state.recentMessages.filter {
                    state.historySearch.isBlank() ||
                        it.originalText.contains(state.historySearch, ignoreCase = true) ||
                        it.polishedTranslation.contains(state.historySearch, ignoreCase = true)
                },
                key = { message -> message.messageId },
            ) { message ->
                val row = TranscriptRow(
                    messageId = message.messageId,
                    speaker = message.sourceLanguage,
                    target = message.targetLanguage,
                    original = message.originalText,
                    literal = message.literalTranslation,
                    polished = message.polishedTranslation,
                    timestamp = message.timestamp,
                )
                TranscriptCard(
                    row = row,
                    medical = true,
                    onReplay = { viewModel.replayTranscript(row) },
                    onRetry = { viewModel.retryTranscript(row) },
                    onCorrect = { viewModel.correctTranscript(row) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { viewModel.navigate(Screen.HOME) }) {
            Text("Back")
        }
    }
}
