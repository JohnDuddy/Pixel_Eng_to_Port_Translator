package com.duddylabs.translator

import android.app.Application
import com.duddylabs.translator.data.AppDatabase
import com.duddylabs.translator.network.BackendApi
import com.duddylabs.translator.network.NetworkPreferenceClientFactory
import com.duddylabs.translator.realtime.HybridTranslatorClient
import com.duddylabs.translator.realtime.OfflineMedicalTranslator
import com.duddylabs.translator.realtime.TranslationRepository
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
    val networkPreferenceClientFactory = NetworkPreferenceClientFactory(application)
    val backendApi = BackendApi(settingsStore, networkPreferenceClientFactory)
    val offlineMedicalTranslator = OfflineMedicalTranslator()
    val translationRepository = TranslationRepository(
        backendApi = backendApi,
        settingsStore = settingsStore,
        offlineMedicalTranslator = offlineMedicalTranslator,
    )
    val realtimeClient = HybridTranslatorClient(
        context = application,
        backendApi = backendApi,
        translationRepository = translationRepository,
        settingsStore = settingsStore,
        networkPreferenceClientFactory = networkPreferenceClientFactory,
    )
}
