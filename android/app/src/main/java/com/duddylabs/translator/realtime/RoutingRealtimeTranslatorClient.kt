package com.duddylabs.translator.realtime

import com.duddylabs.translator.data.RealtimeEngine
import com.duddylabs.translator.data.SpeakerLanguage
import com.duddylabs.translator.settings.SecureSettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge

/**
 * Forwards realtime calls to whichever engine the user has selected in Settings. The choice
 * is locked in when a session begins ([start]); every later call in that session goes to the
 * same engine. Events from all engines are merged so the ViewModel can subscribe once - only
 * the active engine emits during a session.
 */
class RoutingRealtimeTranslatorClient(
    private val settingsStore: SecureSettingsStore,
    private val engines: Map<RealtimeEngine, RealtimeTranslatorClient>,
) : RealtimeTranslatorClient {

    private val fallback: RealtimeTranslatorClient =
        engines[RealtimeEngine.ANDROID_SPEECH] ?: engines.values.first()

    override val events: Flow<InterpreterEvent> =
        merge(*engines.values.map { it.events }.toTypedArray())

    private var active: RealtimeTranslatorClient = fallback

    private fun selectedEngine(): RealtimeTranslatorClient =
        engines[settingsStore.settings.value.realtimeEngine] ?: fallback

    override suspend fun start(config: ConversationConfig) {
        active = selectedEngine()
        active.start(config)
    }

    override suspend fun beginPushToTalk(speakerLanguage: SpeakerLanguage) =
        active.beginPushToTalk(speakerLanguage)

    override suspend fun endPushToTalk() = active.endPushToTalk()

    override suspend fun startContinuous() = active.startContinuous()

    override suspend fun stopContinuous() = active.stopContinuous()

    override suspend fun speakTranslation(text: String, language: SpeakerLanguage) =
        active.speakTranslation(text, language)

    override suspend fun repeatLastTranslation() = active.repeatLastTranslation()

    override suspend fun close() = active.close()
}
