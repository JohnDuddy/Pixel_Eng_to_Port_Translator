package com.duddylabs.translator

import android.app.Application
import com.duddylabs.translator.data.AppDatabase
import com.duddylabs.translator.network.BackendApi
import com.duddylabs.translator.realtime.AndroidSpeechTranslatorClient
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
    val realtimeClient = AndroidSpeechTranslatorClient(
        context = application,
        backendApi = backendApi,
    )
}
