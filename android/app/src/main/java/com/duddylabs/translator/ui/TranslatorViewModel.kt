package com.duddylabs.translator.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.duddylabs.translator.AppContainer
import com.duddylabs.translator.data.ConversationEntity
import com.duddylabs.translator.data.MessageEntity
import com.duddylabs.translator.data.SpeakerLanguage
import com.duddylabs.translator.data.ThemePreference
import com.duddylabs.translator.data.TranslationStyle
import com.duddylabs.translator.data.TranslatorMode
import com.duddylabs.translator.data.VoiceGender
import com.duddylabs.translator.data.VoiceSpeed
import com.duddylabs.translator.data.opposite
import com.duddylabs.translator.network.TextTranslationRequest
import com.duddylabs.translator.realtime.ConversationConfig
import com.duddylabs.translator.realtime.InterpreterEvent
import com.duddylabs.translator.settings.TranslatorSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

enum class Screen {
    HOME,
    CONVERSATION,
    SETTINGS,
    HISTORY,
}

data class TranscriptRow(
    val speaker: SpeakerLanguage,
    val target: SpeakerLanguage,
    val original: String,
    val literal: String,
    val polished: String,
    val timestamp: Long,
)

data class TranslatorUiState(
    val screen: Screen = Screen.HOME,
    val mode: TranslatorMode = TranslatorMode.PUSH_TO_TALK,
    val status: String = "Ready",
    val isListening: Boolean = false,
    val currentConversationId: Long? = null,
    val rows: List<TranscriptRow> = emptyList(),
    val recentMessages: List<MessageEntity> = emptyList(),
    val historySearch: String = "",
    val typedText: String = "",
    val error: String = "",
)

class TranslatorViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private val internalState = MutableStateFlow(TranslatorUiState())
    val settings: StateFlow<TranslatorSettings> = container.settingsStore.settings

    val uiState: StateFlow<TranslatorUiState> = combine(
        internalState,
        container.database.conversationDao().observeRecentMessages(),
    ) { state, recentMessages ->
        state.copy(recentMessages = recentMessages)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        TranslatorUiState(),
    )

    init {
        viewModelScope.launch {
            container.realtimeClient.events.collect { event ->
                try {
                    handleInterpreterEvent(event)
                } catch (error: Throwable) {
                    if (error is CancellationException) {
                        throw error
                    }
                    handleRealtimeFailure("process realtime event", error)
                }
            }
        }
    }

    fun navigate(screen: Screen) {
        internalState.update { it.copy(screen = screen, error = "") }
    }

    fun startMode(mode: TranslatorMode) {
        internalState.update {
            it.copy(
                screen = Screen.CONVERSATION,
                mode = mode,
                status = mode.label,
                error = "",
            )
        }
        launchRealtimeAction("start ${mode.label}") {
            ensureConversation(mode)
            container.realtimeClient.start(
                ConversationConfig(
                    mode = mode,
                    sourceLanguage = SpeakerLanguage.ENGLISH,
                    targetLanguage = SpeakerLanguage.PORTUGUESE_BRAZIL,
                    translationStyle = settings.value.translationStyle,
                ),
            )
        }
    }

    fun beginPushToTalk(speaker: SpeakerLanguage) {
        launchRealtimeAction("begin push-to-talk") {
            ensureConversation(uiState.value.mode)
            container.realtimeClient.beginPushToTalk(speaker)
        }
    }

    fun endPushToTalk() {
        launchRealtimeAction("translate speech") {
            container.realtimeClient.endPushToTalk()
        }
    }

    fun startContinuous() {
        launchRealtimeAction("start hands-free mode") {
            ensureConversation(TranslatorMode.CONTINUOUS)
            container.realtimeClient.startContinuous()
        }
    }

    fun stopContinuous() {
        launchRealtimeAction("stop hands-free mode") {
            container.realtimeClient.stopContinuous()
        }
    }

    fun repeatLastTranslation() {
        launchRealtimeAction("repeat translation") {
            container.realtimeClient.repeatLastTranslation()
        }
    }

    fun clearError() {
        internalState.update { it.copy(error = "") }
    }

    fun updateBackendUrl(value: String) {
        container.settingsStore.update { it.copy(backendBaseUrl = value) }
    }

    fun updateAppToken(value: String) {
        container.settingsStore.update { it.copy(appToken = value) }
    }

    fun updateVoiceSpeed(value: VoiceSpeed) {
        container.settingsStore.update { it.copy(voiceSpeed = value) }
    }

    fun updateVoiceGender(value: VoiceGender) {
        container.settingsStore.update { it.copy(voiceGender = value) }
    }

    fun updateTranslationStyle(value: TranslationStyle) {
        container.settingsStore.update { it.copy(translationStyle = value) }
    }

    fun updateTextScale(value: Float) {
        container.settingsStore.update { it.copy(textScale = value) }
    }

    fun updateThemePreference(value: ThemePreference) {
        container.settingsStore.update { it.copy(themePreference = value) }
    }

    fun updateHighContrast(value: Boolean) {
        container.settingsStore.update { it.copy(highContrast = value) }
    }

    fun updateHistorySearch(query: String) {
        internalState.update { it.copy(historySearch = query) }
    }

    fun updateTypedText(value: String) {
        internalState.update { it.copy(typedText = value, error = "") }
    }

    fun translateTypedText(speaker: SpeakerLanguage) {
        launchRealtimeAction("translate typed text") {
            val text = internalState.value.typedText.trim()
            if (text.isBlank()) {
                internalState.update {
                    it.copy(status = "Ready", error = "Enter a phrase to translate.")
                }
                return@launchRealtimeAction
            }

            val mode = internalState.value.mode
            val target = speaker.opposite()
            ensureConversation(mode)
            handleInterpreterEvent(
                InterpreterEvent.OriginalTranscript(
                    language = speaker,
                    text = text,
                ),
            )
            val result = container.backendApi.translateText(
                TextTranslationRequest(
                    text = text,
                    mode = mode,
                    sourceLanguage = speaker,
                    targetLanguage = target,
                    translationStyle = settings.value.translationStyle,
                ),
            )
            handleInterpreterEvent(InterpreterEvent.SpeakingTranslation)
            handleInterpreterEvent(
                InterpreterEvent.Translation(
                    sourceLanguage = speaker,
                    targetLanguage = target,
                    originalText = result.originalText,
                    literalTranslation = result.literalTranslation,
                    polishedTranslation = result.polishedTranslation,
                ),
            )
            container.realtimeClient.speakTranslation(result.polishedTranslation, target)
            internalState.update { it.copy(typedText = "") }
        }
    }

    fun exportMarkdown(): String =
        uiState.value.recentMessages.joinToString(separator = "\n\n") { message ->
            """
            ### ${message.sourceLanguage.label} to ${message.targetLanguage.label}
            Original: ${message.originalText}
            Literal: ${message.literalTranslation}
            Polished: ${message.polishedTranslation}
            """.trimIndent()
        }

    private suspend fun ensureConversation(mode: TranslatorMode) {
        if (internalState.value.currentConversationId != null) {
            return
        }

        val id = container.database.conversationDao().insertConversation(
            ConversationEntity(
                dateTime = System.currentTimeMillis(),
                mode = mode,
            ),
        )
        internalState.update { it.copy(currentConversationId = id) }
    }

    private fun launchRealtimeAction(actionName: String, action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    throw error
                }
                handleRealtimeFailure(actionName, error)
            }
        }
    }

    private suspend fun handleRealtimeFailure(actionName: String, error: Throwable) {
        runCatching { container.realtimeClient.close() }
        internalState.update {
            it.copy(
                status = "Needs attention",
                isListening = false,
                error = buildUserFacingError(actionName, error),
            )
        }
    }

    private fun buildUserFacingError(actionName: String, error: Throwable): String {
        val rawMessage = error.message.orEmpty()
        val message = rawMessage.ifBlank { error::class.java.simpleName }
        val guidance = when {
            rawMessage.contains("Failed to connect", ignoreCase = true) ||
                rawMessage.contains("Connection refused", ignoreCase = true) ->
                "Start scripts\\run-backend.ps1 on the PC, keep the Pixel 9 plugged in, and run scripts\\install-pixel9-debug.ps1 again so adb reverse is active."
            rawMessage.contains("HTTP 401", ignoreCase = true) ||
                rawMessage.contains("HTTP 403", ignoreCase = true) ->
                "Check the app token in Settings and ALLOWED_APP_TOKEN in backend\\.env."
            rawMessage.contains("HTTP 500", ignoreCase = true) ||
                rawMessage.contains("OpenAI API key", ignoreCase = true) ->
                "Check backend\\.env and make sure OPENAI_API_KEY is set, then restart scripts\\run-backend.ps1."
            rawMessage.contains("OpenAI WebRTC SDP exchange failed", ignoreCase = true) ->
                "Check the OpenAI key, model access, and internet connection on the PC backend."
            else ->
                "Check that the backend is running, the Pixel 9 USB tunnel is active, and microphone permission is allowed."
        }

        return "Could not $actionName. $guidance\n\nDetails: $message"
    }

    private suspend fun handleInterpreterEvent(event: InterpreterEvent) {
        when (event) {
            InterpreterEvent.Connecting -> internalState.update { it.copy(status = "Connecting") }
            InterpreterEvent.Connected -> internalState.update { it.copy(status = "Connected") }
            InterpreterEvent.Listening -> internalState.update { it.copy(status = "Listening", isListening = true) }
            InterpreterEvent.SpeakingTranslation -> internalState.update {
                it.copy(status = "Speaking translation", isListening = false)
            }
            InterpreterEvent.Stopped -> internalState.update { it.copy(status = "Stopped", isListening = false) }
            is InterpreterEvent.OriginalTranscript -> internalState.update {
                it.copy(status = "Heard ${event.language.label}")
            }
            is InterpreterEvent.Translation -> {
                val conversationId = internalState.value.currentConversationId ?: return
                val message = MessageEntity(
                    conversationId = conversationId,
                    sourceLanguage = event.sourceLanguage,
                    targetLanguage = event.targetLanguage,
                    originalText = event.originalText,
                    literalTranslation = event.literalTranslation,
                    polishedTranslation = event.polishedTranslation,
                    timestamp = event.timestamp,
                )
                container.database.conversationDao().insertMessage(message)
                internalState.update { state ->
                    state.copy(
                        status = "Speaking ${event.targetLanguage.statusLabel()}",
                        isListening = false,
                        rows = state.rows + TranscriptRow(
                            speaker = event.sourceLanguage,
                            target = event.targetLanguage,
                            original = event.originalText,
                            literal = event.literalTranslation,
                            polished = event.polishedTranslation,
                            timestamp = event.timestamp,
                        ),
                    )
                }
            }
            is InterpreterEvent.Error -> internalState.update {
                it.copy(error = event.message, status = "Needs attention", isListening = false)
            }
        }
    }
}

private fun SpeakerLanguage.statusLabel(): String =
    when (this) {
        SpeakerLanguage.ENGLISH -> "English"
        SpeakerLanguage.PORTUGUESE_BRAZIL -> "Portuguese"
    }

class TranslatorViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        TranslatorViewModel(container) as T
}
