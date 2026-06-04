package com.duddylabs.translator

import android.app.Application
import com.duddylabs.translator.data.AppDatabase
import com.duddylabs.translator.data.RealtimeEngine
import com.duddylabs.translator.network.BackendApi
import com.duddylabs.translator.realtime.AndroidSpeechTranslatorClient
import com.duddylabs.translator.realtime.OpenAIRealtimeWebSocketClient
import com.duddylabs.translator.realtime.RoutingRealtimeTranslatorClient
import com.duddylabs.translator.settings.SecureSettingsStore

class DuddyTranslatorApplication : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
    }
}

class AppContainer(application: Application) {
    val database = AppDatabase.create(application)
    val settingsStore = SecureSettingsStore(application)
    val backendApi = BackendApi(settingsStore)

    // Both realtime engines are built up front; the routing client picks the one selected in
    // Settings when each session starts. On-device speech stays the default safe path.
    val realtimeClient = RoutingRealtimeTranslatorClient(
        settingsStore = settingsStore,
        engines = mapOf(
            RealtimeEngine.ANDROID_SPEECH to AndroidSpeechTranslatorClient(
                context = application,
                backendApi = backendApi,
            ),
            RealtimeEngine.OPENAI_REALTIME to OpenAIRealtimeWebSocketClient(
                context = application,
                backendApi = backendApi,
                settingsStore = settingsStore,
            ),
        ),
    )
}
