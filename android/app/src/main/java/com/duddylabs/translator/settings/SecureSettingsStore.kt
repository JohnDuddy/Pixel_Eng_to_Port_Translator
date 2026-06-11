package com.duddylabs.translator.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.duddylabs.translator.BuildConfig
import com.duddylabs.translator.data.ThemePreference
import com.duddylabs.translator.data.TranslationStyle
import com.duddylabs.translator.data.VoiceGender
import com.duddylabs.translator.data.VoiceSpeed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.net.URI

data class TranslatorSettings(
    val backendBaseUrl: String = BuildConfig.DEFAULT_BACKEND_URL,
    val appToken: String = "",
    val voiceSpeed: VoiceSpeed = VoiceSpeed.NORMAL,
    val voiceGender: VoiceGender = VoiceGender.FEMALE,
    val translationStyle: TranslationStyle = TranslationStyle.NATURAL,
    val textScale: Float = 1.0f,
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val highContrast: Boolean = false,
    val saveHistory: Boolean = true,
    val preferCellularData: Boolean = BuildConfig.DEFAULT_PREFER_CELLULAR_DATA,
    val offlineMedicalFallbackEnabled: Boolean = true,
    val streamingAudioEnabled: Boolean = false,
)

class SecureSettingsStore(context: Context) {
    private val defaultBackendBaseUrl = BuildConfig.DEFAULT_BACKEND_URL
    private val defaultAppToken = BuildConfig.DEFAULT_APP_TOKEN

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

    private val backendUrlDefaults = readBackendUrlDefaults()
    private val criticalMedicalDefaultsApplied = preferences.getBoolean("criticalMedicalDefaultsApplied", false)
    private val initialSettings = readSettings()
    private val _settings = MutableStateFlow(initialSettings)
    val settings: StateFlow<TranslatorSettings> = _settings

    init {
        writeSettings(initialSettings)
    }

    fun update(transform: (TranslatorSettings) -> TranslatorSettings) {
        _settings.update(transform)
        _settings.update { it.withTravelSafeBackendUrl() }
        writeSettings(_settings.value)
    }

    private fun readSettings(): TranslatorSettings =
        TranslatorSettings(
            backendBaseUrl = backendUrlDefaults.backendBaseUrl,
            appToken = readAppToken(),
            voiceSpeed = readEnum("voiceSpeed", VoiceSpeed.NORMAL),
            voiceGender = readEnum("voiceGender", VoiceGender.FEMALE),
            translationStyle = readEnum("translationStyle", TranslationStyle.NATURAL),
            textScale = preferences.getFloat("textScale", 1.0f),
            themePreference = readEnum("themePreference", ThemePreference.SYSTEM),
            highContrast = preferences.getBoolean("highContrast", false),
            saveHistory = if (criticalMedicalDefaultsApplied) {
                preferences.getBoolean("saveHistory", true)
            } else {
                true
            },
            preferCellularData = readPreferCellularData(),
            offlineMedicalFallbackEnabled = if (criticalMedicalDefaultsApplied) {
                preferences.getBoolean("offlineMedicalFallbackEnabled", true)
            } else {
                true
            },
            streamingAudioEnabled = preferences.getBoolean("streamingAudioEnabled", false),
        )
            .withTravelSafeBackendUrl()

    private fun readAppToken(): String {
        val stored = preferences.getString("appToken", null)?.trim().orEmpty()
        val previousDefault = preferences.getString("lastDefaultAppToken", null)?.trim().orEmpty()
        val defaultToken = defaultAppToken.trim()
        val shouldUseDefault = when {
            stored.isBlank() -> true
            previousDefault.isNotBlank() && stored == previousDefault && stored != defaultToken -> true
            backendUrlDefaults.backendBaseUrl.isPublicUrl() && stored == localDevelopmentAppToken -> true
            else -> false
        }

        return if (shouldUseDefault) defaultToken else stored
    }

    private inline fun <reified T : Enum<T>> readEnum(key: String, defaultValue: T): T =
        runCatching {
            enumValueOf<T>(preferences.getString(key, defaultValue.name) ?: defaultValue.name)
        }.getOrDefault(defaultValue)

    private fun readPreferCellularData(): Boolean =
        if (backendUrlDefaults.migratedLocalToPublic) {
            BuildConfig.DEFAULT_PREFER_CELLULAR_DATA
        } else {
            preferences.getBoolean("preferCellularData", BuildConfig.DEFAULT_PREFER_CELLULAR_DATA)
        }

    private fun readBackendUrlDefaults(): BackendUrlDefaults {
        val stored = preferences.getString("backendBaseUrl", null)?.trim()?.trimEnd('/')
        val previousDefault = preferences.getString("lastDefaultBackendBaseUrl", null)?.trim()?.trimEnd('/')
        val defaultUrl = defaultBackendBaseUrl.trim().trimEnd('/')
        val shouldUseDefault = when {
            stored.isNullOrBlank() -> true
            !previousDefault.isNullOrBlank() && stored == previousDefault && stored != defaultUrl -> true
            defaultUrl.isPublicUrl() && stored.isLocalOrPrivateUrl() -> true
            defaultUrl.isPublicUrl() && !defaultUrl.isCloudflareQuickTunnelUrl() && stored.isCloudflareQuickTunnelUrl() -> true
            defaultUrl.isLanUrl() && stored.isLoopbackUrl() -> true
            else -> false
        }
        return BackendUrlDefaults(
            backendBaseUrl = if (shouldUseDefault) defaultUrl else stored ?: defaultUrl,
            migratedLocalToPublic = !stored.isNullOrBlank() &&
                defaultUrl.isPublicUrl() &&
                stored.isLocalOrPrivateUrl(),
        )
    }

    private fun String.isLanUrl(): Boolean =
        !isLoopbackUrl()

    private fun String.isPublicUrl(): Boolean =
        startsWith("https://", ignoreCase = true) && !isLocalOrPrivateUrl()

    private fun String.isCloudflareQuickTunnelUrl(): Boolean =
        extractHost().lowercase().endsWith(".trycloudflare.com")

    private fun String.isLocalOrPrivateUrl(): Boolean {
        val host = extractHost().lowercase()
        if (host.isBlank()) {
            return false
        }

        if (host == "localhost" || host.endsWith(".local")) {
            return true
        }

        val octets = host.split(".").map { it.toIntOrNull() }
        if (octets.size != 4 || octets.any { it == null || it !in 0..255 }) {
            return host == "::1" ||
                host.startsWith("fe80:") ||
                host.startsWith("fc") ||
                host.startsWith("fd")
        }

        val first = octets[0]!!
        val second = octets[1]!!
        return first == 0 ||
            first == 10 ||
            first == 127 ||
            first >= 224 ||
            (first == 100 && second in 64..127) ||
            (first == 169 && second == 254) ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168)
    }

    private fun String.isLoopbackUrl(): Boolean =
        startsWith("http://127.", ignoreCase = true) ||
            startsWith("http://localhost", ignoreCase = true) ||
            startsWith("http://10.0.2.2", ignoreCase = true)

    private fun TranslatorSettings.withTravelSafeBackendUrl(): TranslatorSettings {
        val defaultUrl = defaultBackendBaseUrl.trim().trimEnd('/')
        val trimmedUrl = backendBaseUrl.trim().trimEnd('/')
        val safeUrl = when {
            trimmedUrl.isBlank() -> defaultUrl
            defaultUrl.isPublicUrl() && trimmedUrl.isLocalOrPrivateUrl() -> defaultUrl
            defaultUrl.isPublicUrl() &&
                !defaultUrl.isCloudflareQuickTunnelUrl() &&
                trimmedUrl.isCloudflareQuickTunnelUrl() -> defaultUrl
            else -> trimmedUrl
        }

        return copy(
            backendBaseUrl = safeUrl,
            preferCellularData = if (safeUrl != trimmedUrl && defaultUrl.isPublicUrl()) {
                BuildConfig.DEFAULT_PREFER_CELLULAR_DATA
            } else {
                preferCellularData
            },
        )
    }

    private fun String.extractHost(): String {
        val uriHost = runCatching { URI(trim()).host }.getOrNull()
        if (!uriHost.isNullOrBlank()) {
            return uriHost.trim('[', ']')
        }

        return trim()
            .substringAfter("://", trim())
            .substringBefore("/")
            .substringBefore(":")
            .trim('[', ']')
    }

    private fun writeSettings(settings: TranslatorSettings) {
        preferences.edit()
            .putString("backendBaseUrl", settings.backendBaseUrl)
            .putString("lastDefaultBackendBaseUrl", defaultBackendBaseUrl.trim().trimEnd('/'))
            .putString("appToken", settings.appToken)
            .putString("lastDefaultAppToken", defaultAppToken.trim())
            .putString("voiceSpeed", settings.voiceSpeed.name)
            .putString("voiceGender", settings.voiceGender.name)
            .putString("translationStyle", settings.translationStyle.name)
            .putFloat("textScale", settings.textScale)
            .putString("themePreference", settings.themePreference.name)
            .putBoolean("highContrast", settings.highContrast)
            .putBoolean("saveHistory", settings.saveHistory)
            .putBoolean("preferCellularData", settings.preferCellularData)
            .putBoolean("offlineMedicalFallbackEnabled", settings.offlineMedicalFallbackEnabled)
            .putBoolean("criticalMedicalDefaultsApplied", true)
            .putBoolean("streamingAudioEnabled", settings.streamingAudioEnabled)
            .apply()
    }

    private data class BackendUrlDefaults(
        val backendBaseUrl: String,
        val migratedLocalToPublic: Boolean,
    )

    private companion object {
        private const val localDevelopmentAppToken = "dev-local-token"
    }
}
