package com.duddylabs.translator.realtime

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.speech.tts.TextToSpeech
import android.util.Base64
import androidx.core.content.ContextCompat
import com.duddylabs.translator.data.SpeakerLanguage
import com.duddylabs.translator.data.TranslatorMode
import com.duddylabs.translator.data.opposite
import com.duddylabs.translator.network.BackendApi
import com.duddylabs.translator.network.RealtimeSessionRequest
import com.duddylabs.translator.settings.SecureSettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Realtime interpreter that streams microphone audio to the OpenAI Realtime API over a
 * WebSocket and plays the translated audio back with [AudioTrack]. It deliberately avoids
 * the native WebRTC library (which crashed on the Pixel 9 network thread) and instead uses
 * Android's own [AudioRecord]/[AudioTrack], which are stable.
 *
 * Audio is PCM16 mono at 24 kHz in both directions, matching the Realtime API default.
 */
class OpenAIRealtimeWebSocketClient(
    private val context: Context,
    private val backendApi: BackendApi,
    private val settingsStore: SecureSettingsStore,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // a WebSocket stays open indefinitely
        .pingInterval(20, TimeUnit.SECONDS)
        .build(),
) : RealtimeTranslatorClient {

    private val _events = MutableSharedFlow<InterpreterEvent>(extraBufferCapacity = 64)
    override val events: Flow<InterpreterEvent> = _events.asSharedFlow()

    private var currentConfig: ConversationConfig = ConversationConfig(
        mode = TranslatorMode.PUSH_TO_TALK,
        sourceLanguage = SpeakerLanguage.ENGLISH,
        targetLanguage = SpeakerLanguage.PORTUGUESE_BRAZIL,
        translationStyle = com.duddylabs.translator.data.TranslationStyle.NATURAL,
    )

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private val capturing = AtomicBoolean(false)
    private var captureThread: Thread? = null
    private val playbackExecutor = Executors.newSingleThreadExecutor()

    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false

    // Assembled per assistant response.
    private val translatedTranscript = StringBuilder()
    private var pendingOriginal: String = ""
    private var spokeThisResponse = false
    private var lastTranslation: InterpreterEvent.Translation? = null

    override suspend fun start(config: ConversationConfig) {
        currentConfig = config
        _events.emit(InterpreterEvent.Connecting)
        configureAudioRouting()
        ensureTextToSpeech()

        val settings = settingsStore.settings.value
        val credentials = backendApi.createRealtimeSession(
            RealtimeSessionRequest(
                mode = config.mode,
                sourceLanguage = config.sourceLanguage,
                targetLanguage = config.targetLanguage,
                translationStyle = config.translationStyle,
                voiceGender = settings.voiceGender,
            ),
        )

        openWebSocket(credentials.clientSecret, credentials.model)
        sendSessionUpdate(config.mode)
        initAudioTrack()
        _events.emit(InterpreterEvent.Connected)
    }

    override suspend fun beginPushToTalk(speakerLanguage: SpeakerLanguage) {
        currentConfig = currentConfig.copy(
            sourceLanguage = speakerLanguage,
            targetLanguage = speakerLanguage.opposite(),
        )
        _events.emit(InterpreterEvent.Listening)
        send("""{"type":"input_audio_buffer.clear"}""")
        startCapture()
    }

    override suspend fun endPushToTalk() {
        stopCapture()
        // Manual turn: commit what we captured and ask for the translation.
        send("""{"type":"input_audio_buffer.commit"}""")
        send("""{"type":"response.create"}""")
    }

    override suspend fun startContinuous() {
        _events.emit(InterpreterEvent.Listening)
        // Continuous mode relies on server-side VAD configured in sendSessionUpdate, so the
        // model decides when each speaker turn ends; we just keep the microphone open.
        startCapture()
    }

    override suspend fun stopContinuous() {
        stopCapture()
        _events.emit(InterpreterEvent.Stopped)
    }

    override suspend fun speakTranslation(text: String, language: SpeakerLanguage) {
        speakLocally(text, language)
    }

    override suspend fun repeatLastTranslation() {
        lastTranslation?.let {
            _events.emit(it)
            speakLocally(it.polishedTranslation, it.targetLanguage)
        }
    }

    override suspend fun close() {
        stopCapture()
        webSocket?.close(1000, "client closed")
        webSocket = null
        releaseAudioTrack()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        ttsReady = false
        _events.emit(InterpreterEvent.Stopped)
    }

    // region WebSocket

    private suspend fun openWebSocket(clientSecret: String, model: String) {
        val opened = CompletableDeferred<Unit>()
        val request = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?model=$model")
            .header("Authorization", "Bearer $clientSecret")
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerEvent(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val detail = response?.let { "HTTP ${it.code}" } ?: t.message.orEmpty()
                if (!opened.isCompleted) {
                    opened.completeExceptionally(
                        IllegalStateException("Realtime WebSocket connection failed: $detail"),
                    )
                }
                _events.tryEmit(
                    InterpreterEvent.Error(
                        "Realtime voice connection dropped. Check the backend, OPENAI_API_KEY, " +
                            "and the Pixel 9 USB tunnel.\n\nDetails: $detail",
                    ),
                )
            }
        })

        opened.await()
    }

    private fun sendSessionUpdate(mode: TranslatorMode) {
        val input = JSONObject()
            .put("format", JSONObject().put("type", "audio/pcm").put("rate", SAMPLE_RATE))
            .put("transcription", JSONObject().put("model", "gpt-4o-mini-transcribe"))
        // Continuous mode uses server VAD to detect turn boundaries; the manual modes commit
        // explicitly on button release, so turn detection is disabled for them.
        if (mode == TranslatorMode.CONTINUOUS) {
            input.put("turn_detection", JSONObject().put("type", "server_vad"))
        } else {
            input.put("turn_detection", JSONObject.NULL)
        }

        val session = JSONObject()
            .put("type", "realtime")
            .put(
                "audio",
                JSONObject()
                    .put("input", input)
                    .put("output", JSONObject().put("format", JSONObject().put("type", "audio/pcm"))),
            )

        send(JSONObject().put("type", "session.update").put("session", session).toString())
    }

    private fun send(json: String) {
        webSocket?.send(json)
    }

    private fun handleServerEvent(text: String) {
        val payload = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (payload.optString("type")) {
            "input_audio_buffer.speech_started" ->
                _events.tryEmit(InterpreterEvent.Listening)

            "conversation.item.input_audio_transcription.completed" -> {
                val transcript = payload.optString("transcript").trim()
                if (transcript.isNotEmpty()) {
                    pendingOriginal = transcript
                    _events.tryEmit(
                        InterpreterEvent.OriginalTranscript(currentConfig.sourceLanguage, transcript),
                    )
                }
            }

            "response.created" -> {
                translatedTranscript.setLength(0)
                spokeThisResponse = false
            }

            "response.output_audio.delta" -> {
                if (!spokeThisResponse) {
                    spokeThisResponse = true
                    _events.tryEmit(InterpreterEvent.SpeakingTranslation)
                }
                val b64 = payload.optString("delta")
                if (b64.isNotEmpty()) {
                    enqueuePlayback(Base64.decode(b64, Base64.DEFAULT))
                }
            }

            "response.output_audio_transcript.delta" ->
                translatedTranscript.append(payload.optString("delta"))

            "response.output_audio_transcript.done" -> {
                val full = payload.optString("transcript").ifBlank { translatedTranscript.toString() }
                emitTranslation(full.trim())
            }

            "response.done" ->
                // Fallback in case the transcript.done event was not delivered.
                emitTranslation(translatedTranscript.toString().trim())

            "error" -> {
                val message = payload.optJSONObject("error")?.optString("message")
                    ?: "The realtime service reported an error."
                _events.tryEmit(InterpreterEvent.Error(message))
            }

            else -> Unit // ignore other realtime event types
        }
    }

    private fun emitTranslation(translated: String) {
        if (translated.isBlank()) {
            return
        }
        val translation = InterpreterEvent.Translation(
            sourceLanguage = currentConfig.sourceLanguage,
            targetLanguage = currentConfig.targetLanguage,
            originalText = pendingOriginal,
            literalTranslation = translated,
            polishedTranslation = translated,
        )
        lastTranslation = translation
        _events.tryEmit(translation)
        translatedTranscript.setLength(0)
        pendingOriginal = ""
    }

    // endregion

    // region Audio capture

    @SuppressLint("MissingPermission")
    private fun startCapture() {
        if (capturing.get()) {
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            _events.tryEmit(InterpreterEvent.Error("Microphone permission is not allowed."))
            return
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(minBuffer, CHUNK_BYTES * 4)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            _events.tryEmit(InterpreterEvent.Error("Could not open the Pixel 9 microphone for realtime voice."))
            return
        }

        audioRecord = record
        capturing.set(true)
        record.startRecording()
        captureThread = Thread {
            val buffer = ByteArray(CHUNK_BYTES)
            while (capturing.get()) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val encoded = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
                    send("""{"type":"input_audio_buffer.append","audio":"$encoded"}""")
                }
            }
        }.also { it.start() }
    }

    private fun stopCapture() {
        if (!capturing.getAndSet(false)) {
            return
        }
        captureThread?.join(500)
        captureThread = null
        audioRecord?.let {
            runCatching { it.stop() }
            it.release()
        }
        audioRecord = null
    }

    // endregion

    // region Audio playback

    private fun initAudioTrack() {
        if (audioTrack != null) {
            return
        }
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, CHUNK_BYTES * 8))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        audioTrack = track
    }

    private fun enqueuePlayback(pcm: ByteArray) {
        val track = audioTrack ?: return
        playbackExecutor.execute {
            var offset = 0
            while (offset < pcm.size) {
                val written = track.write(pcm, offset, pcm.size - offset)
                if (written <= 0) {
                    break
                }
                offset += written
            }
        }
    }

    private fun releaseAudioTrack() {
        audioTrack?.let {
            runCatching { it.stop() }
            it.release()
        }
        audioTrack = null
    }

    // endregion

    // region Local TTS for the typed backup / repeat paths

    private fun configureAudioRouting() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    }

    private fun ensureTextToSpeech() {
        if (textToSpeech != null) {
            return
        }
        textToSpeech = TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }
    }

    private fun speakLocally(text: String, language: SpeakerLanguage) {
        if (text.isBlank() || !ttsReady) {
            return
        }
        val locale = when (language) {
            SpeakerLanguage.ENGLISH -> Locale.US
            SpeakerLanguage.PORTUGUESE_BRAZIL -> Locale("pt", "BR")
        }
        textToSpeech?.language = locale
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "duddy-realtime")
    }

    // endregion

    private companion object {
        const val SAMPLE_RATE = 24_000
        const val CHUNK_BYTES = 3_200 // ~66 ms of PCM16 mono at 24 kHz
    }
}
