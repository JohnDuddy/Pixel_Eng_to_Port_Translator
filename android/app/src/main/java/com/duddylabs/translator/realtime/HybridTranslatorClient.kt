package com.duddylabs.translator.realtime

import android.content.Context
import com.duddylabs.translator.data.SpeakerLanguage
import com.duddylabs.translator.network.BackendApi
import com.duddylabs.translator.network.NetworkPreferenceClientFactory
import com.duddylabs.translator.settings.SecureSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class HybridTranslatorClient(
    context: Context,
    backendApi: BackendApi,
    translationRepository: TranslationRepository,
    private val settingsStore: SecureSettingsStore,
    networkPreferenceClientFactory: NetworkPreferenceClientFactory,
) : RealtimeTranslatorClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val stableClient = AndroidSpeechTranslatorClient(context, translationRepository)
    private val streamingClient = RealtimeAudioStreamingTranslatorClient(
        context = context,
        backendApi = backendApi,
        settingsStore = settingsStore,
        networkPreferenceClientFactory = networkPreferenceClientFactory,
    )
    private val _events = MutableSharedFlow<InterpreterEvent>(extraBufferCapacity = 64)
    override val events: Flow<InterpreterEvent> = _events.asSharedFlow()

    private var activeClient: RealtimeTranslatorClient = stableClient
    private var lastConfig: ConversationConfig? = null

    init {
        scope.launch {
            stableClient.events.collect { _events.emit(it) }
        }
        scope.launch {
            streamingClient.events.collect { _events.emit(it) }
        }
    }

    override suspend fun start(config: ConversationConfig) {
        lastConfig = config
        activeClient = selectClient()
        activeClient.start(config)
    }

    override suspend fun beginPushToTalk(speakerLanguage: SpeakerLanguage) {
        activeClient = selectClient()
        lastConfig?.let { activeClient.start(it) }
        activeClient.beginPushToTalk(speakerLanguage)
    }

    override suspend fun endPushToTalk() {
        activeClient.endPushToTalk()
    }

    override suspend fun startContinuous() {
        activeClient = stableClient
        lastConfig?.let { stableClient.start(it) }
        stableClient.startContinuous()
    }

    override suspend fun stopContinuous() {
        activeClient.stopContinuous()
    }

    override suspend fun speakTranslation(text: String, language: SpeakerLanguage) {
        activeClient.speakTranslation(text, language)
    }

    override suspend fun repeatLastTranslation() {
        activeClient.repeatLastTranslation()
    }

    override suspend fun close() {
        stableClient.close()
        streamingClient.close()
    }

    private fun selectClient(): RealtimeTranslatorClient =
        if (settingsStore.settings.value.streamingAudioEnabled) {
            streamingClient
        } else {
            stableClient
        }
}
