package com.duddylabs.translator.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

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
