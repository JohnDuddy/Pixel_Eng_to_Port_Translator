@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.duddylabs.translator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.duddylabs.translator.data.SpeakerLanguage
import com.duddylabs.translator.data.TranslatorMode

@Composable
fun ConversationScreen(state: TranslatorUiState, viewModel: TranslatorViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TranslatorMode.entries.forEach { mode ->
                FilterChip(
                    selected = state.mode == mode,
                    onClick = { viewModel.startMode(mode) },
                    label = { Text(mode.label) },
                )
            }
        }

        if (state.mode == TranslatorMode.MEDICAL) {
            MedicalDisclaimer()
        }

        if (state.mode == TranslatorMode.CONTINUOUS) {
            ContinuousControls(viewModel)
        } else {
            PushToTalkControls(viewModel)
        }

        if (state.partialTranscript.isNotBlank()) {
            PartialTranscriptCard(state)
        }

        OutlinedTextField(
            value = state.typedText,
            onValueChange = viewModel::updateTypedText,
            label = { Text("Backup phrase") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { viewModel.translateTypedText(SpeakerLanguage.ENGLISH) }) {
                Text("English to Portuguese")
            }
            OutlinedButton(onClick = { viewModel.translateTypedText(SpeakerLanguage.PORTUGUESE_BRAZIL) }) {
                Text("Portuguese to English")
            }
            OutlinedButton(onClick = viewModel::repeatLastTranslation) {
                Text("Repeat Last Voice")
            }
        }
        if (state.mode == TranslatorMode.MEDICAL) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { viewModel.translateTypedTextOffline(SpeakerLanguage.ENGLISH) }) {
                    Text("Offline English to Portuguese")
                }
                OutlinedButton(onClick = { viewModel.translateTypedTextOffline(SpeakerLanguage.PORTUGUESE_BRAZIL) }) {
                    Text("Offline Portuguese to English")
                }
            }
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(state.rows, key = { row -> row.messageId ?: row.timestamp }) { row ->
                TranscriptCard(
                    row = row,
                    medical = state.mode == TranslatorMode.MEDICAL,
                    onReplay = { viewModel.replayTranscript(row) },
                    onRetry = { viewModel.retryTranscript(row) },
                    onCorrect = { viewModel.correctTranscript(row) },
                )
            }
        }

        if (state.error.isNotBlank()) {
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text("Needs attention", fontWeight = FontWeight.Bold)
                    Text(state.error)
                }
            }
        }

        OutlinedButton(onClick = { viewModel.navigate(Screen.HOME) }) {
            Text("Back")
        }
    }
}

@Composable
private fun MedicalDisclaimer() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Medical use disclaimer", fontWeight = FontWeight.Bold)
            Text(
                "This is an automated aid, not a certified medical interpreter. " +
                    "Translations may contain errors. For diagnosis, consent, medication, " +
                    "or any clinical decision, use a qualified human interpreter and confirm " +
                    "critical details directly with the patient or clinician.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ContinuousControls(viewModel: TranslatorViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = viewModel::startContinuous) {
            Text("Start Hands-Free")
        }
        OutlinedButton(onClick = viewModel::stopContinuous) {
            Text("Stop")
        }
    }
}

@Composable
private fun PushToTalkControls(viewModel: TranslatorViewModel) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp),
    ) {
        Button(
            onClick = { viewModel.beginPushToTalk(SpeakerLanguage.ENGLISH) },
            modifier = Modifier
                .weight(1f)
                .fillMaxSize(),
        ) {
            Text("Speak English")
        }
        Button(
            onClick = { viewModel.beginPushToTalk(SpeakerLanguage.PORTUGUESE_BRAZIL) },
            modifier = Modifier
                .weight(1f)
                .fillMaxSize(),
        ) {
            Text("Falar Portugues")
        }
    }
    OutlinedButton(
        onClick = viewModel::endPushToTalk,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Finish Listening")
    }
}

@Composable
private fun PartialTranscriptCard(state: TranslatorUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(12.dp),
        ) {
            Text("Hearing ${state.partialLanguage?.label.orEmpty()}", fontWeight = FontWeight.Bold)
            Text(state.partialTranscript)
        }
    }
}
