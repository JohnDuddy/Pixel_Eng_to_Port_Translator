@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.duddylabs.translator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.duddylabs.translator.data.SpeakerLanguage
import com.duddylabs.translator.data.ThemePreference
import com.duddylabs.translator.data.TranslationStyle
import com.duddylabs.translator.data.TranslatorMode
import com.duddylabs.translator.data.VoiceGender
import com.duddylabs.translator.data.VoiceSpeed
import com.duddylabs.translator.data.opposite

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuddyTranslatorApp(viewModel: TranslatorViewModel) {
    val state by viewModel.uiState.collectAsState()
    val settings by viewModel.settings.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Duddy Translator", fontWeight = FontWeight.Bold)
                        Text(state.status, style = MaterialTheme.typography.labelMedium)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            when (state.screen) {
                Screen.HOME -> HomeScreen(viewModel)
                Screen.CONVERSATION -> ConversationScreen(state, viewModel)
                Screen.SETTINGS -> SettingsScreen(settings, viewModel)
                Screen.HISTORY -> HistoryScreen(state, viewModel)
            }
        }
    }
}

@Composable
private fun HomeScreen(viewModel: TranslatorViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "English and Brazilian Portuguese voice translator",
            style = MaterialTheme.typography.titleMedium,
        )
        Button(
            onClick = { viewModel.startMode(TranslatorMode.PUSH_TO_TALK) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Start Conversation")
        }
        OutlinedButton(
            onClick = { viewModel.startMode(TranslatorMode.TRAVEL) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Travel Mode")
        }
        OutlinedButton(
            onClick = { viewModel.startMode(TranslatorMode.MEDICAL) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Medical Mode")
        }
        OutlinedButton(
            onClick = { viewModel.navigate(Screen.SETTINGS) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Settings")
        }
        OutlinedButton(
            onClick = { viewModel.navigate(Screen.HISTORY) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Conversation History")
        }
    }
}

@Composable
private fun ConversationScreen(state: TranslatorUiState, viewModel: TranslatorViewModel) {
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

        if (state.mode == TranslatorMode.CONTINUOUS) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::startContinuous) {
                    Text("Start Hands-Free")
                }
                OutlinedButton(onClick = viewModel::stopContinuous) {
                    Text("Stop")
                }
            }
        } else {
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
                    Text("Falar Português")
                }
            }
            OutlinedButton(
                onClick = viewModel::endPushToTalk,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Finish Listening")
            }
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
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = viewModel::repeatLastTranslation) {
                Text("Repeat Voice")
            }
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(state.rows) { row ->
                TranscriptCard(row = row, medical = state.mode == TranslatorMode.MEDICAL)
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
private fun SpeakerButton(
    label: String,
    language: SpeakerLanguage,
    viewModel: TranslatorViewModel,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Button(
            onClick = { viewModel.beginPushToTalk(language) },
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp),
        ) {
            Text(label)
        }
        OutlinedButton(
            onClick = viewModel::endPushToTalk,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Translate")
        }
    }
}

@Composable
private fun TranscriptCard(row: TranscriptRow, medical: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(14.dp),
        ) {
            val spokenLanguage = row.target.label.substringBefore(" (")
            val heardLanguage = row.speaker.label.substringBefore(" (")
            Text("$spokenLanguage voice played", fontWeight = FontWeight.Bold)
            Text("$heardLanguage heard: ${row.original}")
            if (medical) {
                Text("Literal: ${row.literal}")
                Text("Polished: ${row.polished}")
            } else {
                Text("$spokenLanguage: ${row.polished.ifBlank { row.literal }}")
            }
            Text("Timestamp: ${row.timestamp}", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SettingsScreen(settings: com.duddylabs.translator.settings.TranslatorSettings, viewModel: TranslatorViewModel) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            OutlinedTextField(
                value = settings.backendBaseUrl,
                onValueChange = viewModel::updateBackendUrl,
                label = { Text("Backend URL") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                value = settings.appToken,
                onValueChange = viewModel::updateAppToken,
                label = { Text("App Token") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            SettingChips("Voice Speed", VoiceSpeed.entries, settings.voiceSpeed, viewModel::updateVoiceSpeed)
        }
        item {
            SettingChips("Voice Gender", VoiceGender.entries, settings.voiceGender, viewModel::updateVoiceGender)
        }
        item {
            SettingChips("Translation Style", TranslationStyle.entries, settings.translationStyle, viewModel::updateTranslationStyle)
        }
        item {
            Text("Text Size")
            Slider(
                value = settings.textScale,
                onValueChange = viewModel::updateTextScale,
                valueRange = 0.85f..1.4f,
            )
        }
        item {
            SettingChips("Dark Mode", ThemePreference.entries, settings.themePreference, viewModel::updateThemePreference)
        }
        item {
            Row {
                Text("High Contrast", modifier = Modifier.weight(1f))
                Switch(checked = settings.highContrast, onCheckedChange = viewModel::updateHighContrast)
            }
        }
        item {
            OutlinedButton(onClick = { viewModel.navigate(Screen.HOME) }) {
                Text("Back")
            }
        }
    }
}

@Composable
private fun <T> SettingChips(
    title: String,
    values: List<T>,
    selected: T,
    onSelected: (T) -> Unit,
) where T : Enum<T> {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontWeight = FontWeight.Bold)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            values.forEach { value ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelected(value) },
                    label = { Text(value.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }
    }
}

@Composable
private fun HistoryScreen(state: TranslatorUiState, viewModel: TranslatorViewModel) {
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
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(
                state.recentMessages.filter {
                    state.historySearch.isBlank() ||
                        it.originalText.contains(state.historySearch, ignoreCase = true) ||
                        it.polishedTranslation.contains(state.historySearch, ignoreCase = true)
                },
            ) { message ->
                TranscriptCard(
                    row = TranscriptRow(
                        speaker = message.sourceLanguage,
                        target = message.targetLanguage,
                        original = message.originalText,
                        literal = message.literalTranslation,
                        polished = message.polishedTranslation,
                        timestamp = message.timestamp,
                    ),
                    medical = true,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { viewModel.navigate(Screen.HOME) }) {
            Text("Back")
        }
    }
}
