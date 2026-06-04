package com.duddylabs.translator.realtime

import com.duddylabs.translator.data.SpeakerLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class SafePixelRealtimeTranslatorClient : RealtimeTranslatorClient {
    private val _events = MutableSharedFlow<InterpreterEvent>(extraBufferCapacity = 16)
    override val events: Flow<InterpreterEvent> = _events.asSharedFlow()

    override suspend fun start(config: ConversationConfig) {
        Unit
    }

    override suspend fun beginPushToTalk(speakerLanguage: SpeakerLanguage) {
        _events.emit(InterpreterEvent.Listening)
        _events.emit(
            InterpreterEvent.Error(
                "Voice realtime is temporarily disabled on Pixel 9 because the Android WebRTC native library is crashing. The app is stable now; WebRTC needs a separate repair pass before live microphone translation is enabled.",
            ),
        )
    }

    override suspend fun endPushToTalk() {
        _events.emit(InterpreterEvent.Stopped)
    }

    override suspend fun startContinuous() {
        _events.emit(
            InterpreterEvent.Error(
                "Continuous voice mode is temporarily disabled on Pixel 9 while the WebRTC native crash is being repaired.",
            ),
        )
    }

    override suspend fun stopContinuous() {
        _events.emit(InterpreterEvent.Stopped)
    }

    override suspend fun speakTranslation(text: String, language: SpeakerLanguage) {
        _events.emit(InterpreterEvent.Stopped)
    }

    override suspend fun repeatLastTranslation() {
        _events.emit(InterpreterEvent.Stopped)
    }

    override suspend fun close() {
        _events.emit(InterpreterEvent.Stopped)
    }
}
