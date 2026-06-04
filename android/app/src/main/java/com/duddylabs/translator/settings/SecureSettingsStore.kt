package com.duddylabs.translator.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.duddylabs.translator.data.RealtimeEngine
import com.duddylabs.translator.data.ThemePreference
import com.duddylabs.translator.data.TranslationStyle
import com.duddylabs.translator.data.VoiceGender
import com.duddylabs.translator.data.VoiceSpeed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class TranslatorSettings(
    val backendBaseUrl: String = "http://127.0.0.1:8001",
    val appToken: String = "dev-local-token",
    val voiceSpeed: VoiceSpeed = VoiceSpeed.NORMAL,
    val voiceGender: VoiceGender = VoiceGender.FEMALE,
    val translationStyle: TranslationStyle = TranslationStyle.NATURAL,
    val textScale: Float = 1.0f,
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val highContrast: Boolean = false,
    val realtimeEngine: RealtimeEngine = RealtimeEngine.ANDROID_SPEECH,
)

class SecureSettingsStore(context: Context) {
    private val defaultBackendBaseUrl = "http://127.0.0.1:8001"

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val preferences = EncryptedSharedPreferences.create(
        context,
        "secure_settings.xml",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<TranslatorSettings> = _settings

    fun update(transform: (TranslatorSettings) -> TranslatorSettings) {
        _settings.update(transform)
        writeSettings(_settings.value)
    }

    private fun readSettings(): TranslatorSettings =
        TranslatorSettings(
            backendBaseUrl = readBackendBaseUrl(),
            appToken = preferences.getString("appToken", null) ?: "dev-local-token",
            voiceSpeed = enumValueOf(preferences.getString("voiceSpeed", VoiceSpeed.NORMAL.name)!!),
            voiceGender = enumValueOf(preferences.getString("voiceGender", VoiceGender.FEMALE.name)!!),
            translationStyle = enumValueOf(preferences.getString("translationStyle", TranslationStyle.NATURAL.name)!!),
            textScale = preferences.getFloat("textScale", 1.0f),
            themePreference = enumValueOf(preferences.getString("themePreference", ThemePreference.SYSTEM.name)!!),
            highContrast = preferences.getBoolean("highContrast", false),
            realtimeEngine = enumValueOf(preferences.getString("realtimeEngine", RealtimeEngine.ANDROID_SPEECH.name)!!),
        )

    private fun readBackendBaseUrl(): String {
        val stored = preferences.getString("backendBaseUrl", null)
        return if (stored == "http://127.0.0.1:8000") {
            defaultBackendBaseUrl
        } else {
            stored ?: defaultBackendBaseUrl
        }
    }

    private fun writeSettings(settings: TranslatorSettings) {
        preferences.edit()
            .putString("backendBaseUrl", settings.backendBaseUrl)
            .putString("appToken", settings.appToken)
            .putString("voiceSpeed", settings.voiceSpeed.name)
            .putString("voiceGender", settings.voiceGender.name)
            .putString("translationStyle", settings.translationStyle.name)
            .putFloat("textScale", settings.textScale)
            .putString("themePreference", settings.themePreference.name)
            .putBoolean("highContrast", settings.highContrast)
            .putString("realtimeEngine", settings.realtimeEngine.name)
            .apply()
    }
}
