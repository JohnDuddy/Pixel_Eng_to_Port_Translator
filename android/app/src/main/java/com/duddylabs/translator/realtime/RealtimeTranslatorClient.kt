package com.duddylabs.translator.realtime

import com.duddylabs.translator.data.SpeakerLanguage
import kotlinx.coroutines.flow.Flow

interface RealtimeTranslatorClient {
    val events: Flow<InterpreterEvent>

    suspend fun start(config: ConversationConfig)
    suspend fun beginPushToTalk(speakerLanguage: SpeakerLanguage)
    suspend fun endPushToTalk()
    suspend fun startContinuous()
    suspend fun stopContinuous()
    suspend fun speakTranslation(text: String, language: SpeakerLanguage)
    suspend fun repeatLastTranslation()
    suspend fun close()
}
