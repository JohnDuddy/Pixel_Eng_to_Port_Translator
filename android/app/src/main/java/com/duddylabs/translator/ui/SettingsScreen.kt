package com.duddylabs.translator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.duddylabs.translator.data.ThemePreference
import com.duddylabs.translator.data.TranslationStyle
import com.duddylabs.translator.data.VoiceGender
import com.duddylabs.translator.data.VoiceSpeed
import com.duddylabs.translator.settings.TranslatorSettings

@Composable
fun SettingsScreen(settings: TranslatorSettings, viewModel: TranslatorViewModel) {
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
            Row {
                Text("Save Conversation History", modifier = Modifier.weight(1f))
                Switch(checked = settings.saveHistory, onCheckedChange = viewModel::updateSaveHistory)
            }
        }
        item {
            Row {
                Text("Prefer Cellular Data", modifier = Modifier.weight(1f))
                Switch(checked = settings.preferCellularData, onCheckedChange = viewModel::updatePreferCellularData)
            }
        }
        item {
            Row {
                Text("Offline Medical Fallback", modifier = Modifier.weight(1f))
                Switch(
                    checked = settings.offlineMedicalFallbackEnabled,
                    onCheckedChange = viewModel::updateOfflineMedicalFallbackEnabled,
                )
            }
        }
        item {
            Row {
                Text("Streaming Speech Engine", modifier = Modifier.weight(1f))
                Switch(
                    checked = settings.streamingAudioEnabled,
                    onCheckedChange = viewModel::updateStreamingAudioEnabled,
                )
            }
        }
        item {
            OutlinedButton(onClick = { viewModel.navigate(Screen.HOME) }) {
                Text("Back")
            }
        }
    }
}
