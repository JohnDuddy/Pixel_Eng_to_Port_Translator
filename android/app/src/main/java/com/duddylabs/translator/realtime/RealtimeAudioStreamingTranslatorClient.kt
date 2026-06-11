package com.duddylabs.translator.realtime

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import androidx.core.content.ContextCompat
import com.duddylabs.translator.data.SpeakerLanguage
import com.duddylabs.translator.data.TranslationStyle
import com.duddylabs.translator.data.TranslatorMode
import com.duddylabs.translator.data.opposite
import com.duddylabs.translator.network.BackendApi
import com.duddylabs.translator.network.NetworkPreferenceClientFactory
import com.duddylabs.translator.network.RealtimeSessionRequest
import com.duddylabs.translator.settings.SecureSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

class RealtimeAudioStreamingTranslatorClient(
    private val context: Context,
    private val backendApi: BackendApi,
    private val settingsStore: SecureSettingsStore,
    private val networkPreferenceClientFactory: NetworkPreferenceClientFactory,
    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build(),
) : RealtimeTranslatorClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _events = MutableSharedFlow<InterpreterEvent>(extraBufferCapacity = 64)
    override val events: Flow<InterpreterEvent> = _events.asSharedFlow()

    private var currentConfig = ConversationConfig(
        mode = TranslatorMode.PUSH_TO_TALK,
        sourceLanguage = SpeakerLanguage.ENGLISH,
        targetLanguage = SpeakerLanguage.PORTUGUESE_BRAZIL,
        translationStyle = TranslationStyle.NATURAL,
    )
    private var webSocket: WebSocket? = null
    private var recordingJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var committed = false
    private var latestOriginalText = ""
    private var translatedText = ""
    private var lastTranslation: InterpreterEvent.Translation? = null
    private val ttsFallback = TtsPlayer(
        context = context,
        onDone = {},
        onError = { message -> scope.launch { _events.emit(InterpreterEvent.Error(message)) } },
    )

    override suspend fun start(config: ConversationConfig) {
        currentConfig = config
        ttsFallback.configure(config.voiceSpeed, config.voiceGender)
        ttsFallback.ensureStarted()
        _events.emit(InterpreterEvent.Connected)
    }

    override suspend fun beginPushToTalk(speakerLanguage: SpeakerLanguage) {
        val streamingConfig = currentConfig.copy(
            mode = TranslatorMode.PUSH_TO_TALK,
            sourceLanguage = speakerLanguage,
            targetLanguage = speakerLanguage.opposite(),
        )
        currentConfig = streamingConfig
        openRealtimeSocket(streamingConfig)
    }

    override suspend fun endPushToTalk() {
        stopRecordingAndCommit()
    }

    override suspend fun startContinuous() {
        _events.emit(
            InterpreterEvent.Error(
                "Experimental streaming audio currently supports push-to-talk only. Turn off Streaming Speech Engine to use hands-free mode.",
            ),
        )
    }

    override suspend fun stopContinuous() {
        stopRecordingAndCommit()
    }

    override suspend fun speakTranslation(text: String, language: SpeakerLanguage) {
        ttsFallback.speak(text, language)
    }

    override suspend fun repeatLastTranslation() {
        lastTranslation?.let { translation ->
            _events.emit(InterpreterEvent.SpeakingTranslation)
            ttsFallback.speak(translation.polishedTranslation, translation.targetLanguage)
        }
    }

    override suspend fun close() {
        recordingJob?.cancel()
        audioRecord?.runCatchingStopAndRelease()
        audioRecord = null
        audioTrack?.runCatchingStopAndRelease()
        audioTrack = null
        webSocket?.close(1000, "closing")
        webSocket = null
        committed = false
        ttsFallback.close()
        _events.emit(InterpreterEvent.Stopped)
    }

    private suspend fun openRealtimeSocket(config: ConversationConfig) {
        closeSocketOnly()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _events.emit(InterpreterEvent.Error("Microphone permission is not allowed."))
            return
        }

        committed = false
        latestOriginalText = ""
        translatedText = ""
        val credentials = try {
            backendApi.createRealtimeSession(
                RealtimeSessionRequest(
                    mode = config.mode,
                    sourceLanguage = config.sourceLanguage,
                    targetLanguage = config.targetLanguage,
                    translationStyle = config.translationStyle,
                    voiceGender = config.voiceGender,
                ),
            )
        } catch (error: Throwable) {
            if (error is CancellationException) {
                throw error
            }
            _events.emit(InterpreterEvent.Error(buildRealtimeStartupFailureMessage(error)))
            closeSocketOnly()
            return
        }
        val requestUrl = "wss://api.openai.com/v1/realtime?model=${credentials.model}"
        val request = Request.Builder()
            .url(requestUrl)
            .header("Authorization", "Bearer ${credentials.clientSecret}")
            .header("OpenAI-Beta", "realtime=v1")
            .build()
        val settings = settingsStore.settings.value
        val client = networkPreferenceClientFactory.clientFor(
            baseClient = baseClient,
            preferCellularData = settings.preferCellularData,
            targetUrl = requestUrl,
        )

        webSocket = try {
            client.newWebSocket(request, StreamingWebSocketListener(config))
        } catch (error: Throwable) {
            if (error is CancellationException) {
                throw error
            }
            _events.emit(InterpreterEvent.Error("Realtime voice could not open the WebSocket. Details: ${error.message.orEmpty()}"))
            closeSocketOnly()
            null
        }
    }

    private fun closeSocketOnly() {
        recordingJob?.cancel()
        recordingJob = null
        audioRecord?.runCatchingStopAndRelease()
        audioRecord = null
        audioTrack?.runCatchingStopAndRelease()
        audioTrack = null
        webSocket?.close(1000, "new turn")
        webSocket = null
    }

    @SuppressLint("MissingPermission")
    private fun startRecording(config: ConversationConfig) {
        if (recordingJob != null) {
            return
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(minBuffer, chunkSizeBytes * 4)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (error: Throwable) {
            _events.tryEmit(InterpreterEvent.Error("Microphone recorder could not start. Details: ${error.message.orEmpty()}"))
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record.release() }
            _events.tryEmit(InterpreterEvent.Error("Microphone recorder could not initialize. Try the typed backup phrase."))
            return
        }
        audioRecord = record
        try {
            record.startRecording()
        } catch (error: Throwable) {
            runCatching { record.release() }
            audioRecord = null
            _events.tryEmit(InterpreterEvent.Error("Microphone recorder could not start. Details: ${error.message.orEmpty()}"))
            return
        }
        _events.tryEmit(InterpreterEvent.Listening)

        recordingJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(chunkSizeBytes)
            var hasSpeech = false
            var trailingSilenceMs = 0
            while (isActive && !committed) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    continue
                }

                val speech = isSpeech(buffer, read)
                if (speech) {
                    hasSpeech = true
                    trailingSilenceMs = 0
                } else if (hasSpeech) {
                    trailingSilenceMs += chunkMs
                }

                if (speech || hasSpeech) {
                    sendAudio(buffer, read)
                }

                if (hasSpeech && trailingSilenceMs >= commitSilenceMs && config.mode == TranslatorMode.PUSH_TO_TALK) {
                    stopRecordingAndCommit()
                }
            }
        }
    }

    private fun sendAudio(buffer: ByteArray, size: Int) {
        val audio = Base64.encodeToString(buffer.copyOf(size), Base64.NO_WRAP)
        webSocket?.send(
            JSONObject()
                .put("type", "input_audio_buffer.append")
                .put("audio", audio)
                .toString(),
        )
    }

    private fun stopRecordingAndCommit() {
        if (committed) {
            return
        }
        committed = true
        recordingJob?.cancel()
        recordingJob = null
        audioRecord?.runCatchingStopAndRelease()
        audioRecord = null
        webSocket?.send(JSONObject().put("type", "input_audio_buffer.commit").toString())
        webSocket?.send(JSONObject().put("type", "response.create").toString())
        _events.tryEmit(InterpreterEvent.SpeakingTranslation)
    }

    private fun ensureAudioTrack(): AudioTrack {
        val existing = audioTrack
        if (existing != null) {
            return existing
        }

        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, chunkSizeBytes * 8))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        audioTrack = track
        return track
    }

    private fun handleServerEvent(config: ConversationConfig, text: String) {
        try {
            handleServerEventPayload(config, text)
        } catch (error: Throwable) {
            if (error is CancellationException) {
                throw error
            }
            scope.launch {
                _events.emit(InterpreterEvent.Error("Realtime voice stopped after an unexpected audio event. Details: ${error.message.orEmpty()}"))
                closeSocketOnly()
            }
        }
    }

    private fun handleServerEventPayload(config: ConversationConfig, text: String) {
        val event = JSONObject(text)
        when (val type = event.optString("type")) {
            "session.created",
            "session.updated" -> scope.launch {
                try {
                    _events.emit(InterpreterEvent.Connected)
                    startRecording(config)
                } catch (error: Throwable) {
                    if (error is CancellationException) {
                        throw error
                    }
                    _events.emit(InterpreterEvent.Error("Realtime voice could not start recording. Details: ${error.message.orEmpty()}"))
                    closeSocketOnly()
                }
            }
            "conversation.item.input_audio_transcription.delta" -> {
                val delta = event.optString("delta")
                latestOriginalText += delta
                if (latestOriginalText.isNotBlank()) {
                    scope.launch {
                        _events.emit(InterpreterEvent.PartialTranscript(config.sourceLanguage, latestOriginalText))
                    }
                }
            }
            "conversation.item.input_audio_transcription.completed" -> {
                latestOriginalText = event.optString("transcript", latestOriginalText)
                scope.launch {
                    _events.emit(InterpreterEvent.OriginalTranscript(config.sourceLanguage, latestOriginalText))
                }
            }
            "response.output_audio.delta",
            "response.audio.delta" -> {
                val audio = Base64.decode(event.optString("delta"), Base64.DEFAULT)
                ensureAudioTrack().write(audio, 0, audio.size)
            }
            "response.output_audio_transcript.delta",
            "response.audio_transcript.delta" -> {
                translatedText += event.optString("delta")
                if (translatedText.isNotBlank()) {
                    scope.launch {
                        _events.emit(InterpreterEvent.PartialTranscript(config.targetLanguage, translatedText))
                    }
                }
            }
            "response.output_audio_transcript.done",
            "response.audio_transcript.done" -> {
                translatedText = event.optString("transcript", translatedText)
            }
            "response.done" -> scope.launch {
                val translation = InterpreterEvent.Translation(
                    sourceLanguage = config.sourceLanguage,
                    targetLanguage = config.targetLanguage,
                    originalText = latestOriginalText.ifBlank { "Streaming speech" },
                    literalTranslation = translatedText,
                    polishedTranslation = translatedText,
                )
                lastTranslation = translation
                _events.emit(translation)
                closeSocketOnly()
            }
            "error" -> scope.launch {
                val message = event.optJSONObject("error")?.optString("message").orEmpty()
                _events.emit(InterpreterEvent.Error(message.ifBlank { "Realtime streaming failed." }))
                closeSocketOnly()
            }
            else -> {
                // Other realtime events are diagnostic lifecycle signals.
            }
        }
    }

    private fun buildRealtimeStartupFailureMessage(error: Throwable): String {
        val details = error.message.orEmpty().ifBlank { error::class.java.simpleName }
        val guidance = when {
            details.contains("Network request failed", ignoreCase = true) ||
                details.contains("Failed to connect", ignoreCase = true) ||
                details.contains("Connection refused", ignoreCase = true) ||
                details.contains("No route to host", ignoreCase = true) ||
                details.contains("timed out", ignoreCase = true) ->
                "The backend could not be reached. For travel, use the public HTTPS Cloud Run backend or the typed offline medical fallback."
            details.contains("HTTP 401", ignoreCase = true) ||
                details.contains("HTTP 403", ignoreCase = true) ->
                "The backend rejected the app token. Reinstall with the Cloud Run travel script so the token and URL match."
            details.contains("OpenAI API key", ignoreCase = true) ||
                details.contains("OPENAI_API_KEY", ignoreCase = true) ->
                "The backend is reachable but its OpenAI API key is not configured."
            else ->
                "Use typed backup translation while checking the Backend URL, app token, and microphone permission."
        }

        return "Realtime voice could not start. $guidance\n\nDetails: $details"
    }

    private inner class StreamingWebSocketListener(
        private val config: ConversationConfig,
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            scope.launch { _events.emit(InterpreterEvent.Connecting) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleServerEvent(config, text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            scope.launch {
                _events.emit(InterpreterEvent.Error("Realtime WebSocket failed. ${t.message.orEmpty()}"))
            }
            closeSocketOnly()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scope.launch { _events.emit(InterpreterEvent.Stopped) }
        }
    }

    companion object {
        private const val sampleRate = 24_000
        private const val chunkMs = 20
        private const val chunkSizeBytes = sampleRate * chunkMs / 1_000 * 2
        private const val commitSilenceMs = 800
        private const val speechRmsThreshold = 650.0

        private fun isSpeech(buffer: ByteArray, size: Int): Boolean {
            var sumSquares = 0.0
            var samples = 0
            var index = 0
            while (index + 1 < size) {
                val sample = ((buffer[index + 1].toInt() shl 8) or (buffer[index].toInt() and 0xff)).toShort()
                sumSquares += sample.toDouble() * sample.toDouble()
                samples += 1
                index += 2
            }

            if (samples == 0) {
                return false
            }
            return sqrt(sumSquares / samples) >= speechRmsThreshold
        }

        private fun AudioRecord.runCatchingStopAndRelease() {
            runCatching { stop() }
            runCatching { release() }
        }

        private fun AudioTrack.runCatchingStopAndRelease() {
            runCatching { stop() }
            runCatching { release() }
        }
    }
}
