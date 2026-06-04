package com.duddylabs.translator.realtime

import android.content.Context
import android.media.AudioManager
import com.duddylabs.translator.data.SpeakerLanguage
import com.duddylabs.translator.network.BackendApi
import com.duddylabs.translator.network.RealtimeSessionRequest
import com.duddylabs.translator.settings.SecureSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OpenAIRealtimeWebRtcClient(
    private val context: Context,
    private val backendApi: BackendApi,
    private val settingsStore: SecureSettingsStore,
    private val httpClient: OkHttpClient = OkHttpClient(),
) : RealtimeTranslatorClient {
    private val _events = MutableSharedFlow<InterpreterEvent>(extraBufferCapacity = 64)
    override val events: Flow<InterpreterEvent> = _events.asSharedFlow()

    private var currentConfig: ConversationConfig? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var lastTranslation: InterpreterEvent.Translation? = null
    private var lastOriginalText: String = ""

    override suspend fun start(config: ConversationConfig) {
        currentConfig = config
        _events.emit(InterpreterEvent.Connecting)
        configureAudio()
        initializeWebRtc()

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

        connectWebRtc(credentials.clientSecret, credentials.model)
        _events.emit(InterpreterEvent.Connected)
    }

    override suspend fun beginPushToTalk(speakerLanguage: SpeakerLanguage) {
        _events.emit(InterpreterEvent.Listening)
        sendRealtimeEvent(
            """
            {
              "type": "conversation.item.create",
              "item": {
                "type": "message",
                "role": "system",
                "content": [
                  {
                    "type": "input_text",
                    "text": "Push-to-talk speaker language: ${speakerLanguage.locale}"
                  }
                ]
              }
            }
            """.trimIndent(),
        )
    }

    override suspend fun endPushToTalk() {
        sendRealtimeEvent("""{"type":"response.create"}""")
    }

    override suspend fun startContinuous() {
        _events.emit(InterpreterEvent.Listening)
        sendRealtimeEvent("""{"type":"session.update","session":{"turn_detection":{"type":"server_vad"}}}""")
    }

    override suspend fun stopContinuous() {
        _events.emit(InterpreterEvent.Stopped)
    }

    override suspend fun speakTranslation(text: String, language: SpeakerLanguage) {
        Unit
    }

    override suspend fun repeatLastTranslation() {
        lastTranslation?.let { _events.emit(it) }
    }

    override suspend fun close() {
        dataChannel?.close()
        localAudioTrack?.dispose()
        audioSource?.dispose()
        peerConnection?.close()
        peerConnectionFactory?.dispose()
        dataChannel = null
        localAudioTrack = null
        audioSource = null
        peerConnection = null
        peerConnectionFactory = null
        _events.emit(InterpreterEvent.Stopped)
    }

    private fun configureAudio() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = false
    }

    private fun initializeWebRtc() {
        if (peerConnectionFactory != null) {
            return
        }

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions(),
        )

        peerConnectionFactory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        audioSource = peerConnectionFactory?.createAudioSource(MediaConstraints())
        localAudioTrack = peerConnectionFactory?.createAudioTrack("local_audio", audioSource)
    }

    private suspend fun connectWebRtc(clientSecret: String, model: String) {
        val factory = peerConnectionFactory ?: error("WebRTC factory is not initialized.")
        val audioTrack = localAudioTrack ?: error("Local audio track is not initialized.")
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val connection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
            override fun onIceCandidate(candidate: IceCandidate?) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
            override fun onAddStream(stream: MediaStream?) = Unit
            override fun onRemoveStream(stream: MediaStream?) = Unit
            override fun onDataChannel(channel: DataChannel?) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
        }) ?: error("Could not create WebRTC peer connection.")

        peerConnection = connection
        dataChannel = connection.createDataChannel("oai-events", DataChannel.Init()).also { channel ->
            channel.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) = Unit
                override fun onStateChange() = Unit
                override fun onMessage(buffer: DataChannel.Buffer) {
                    handleRealtimeMessage(buffer.data)
                }
            })
        }

        connection.addTrack(audioTrack, listOf("local_audio_stream"))
        val offer = connection.awaitOffer()
        connection.awaitSetLocalDescription(offer)
        val answerSdp = postSdpOffer(clientSecret, model, offer.description)
        connection.awaitSetRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
    }

    private suspend fun postSdpOffer(clientSecret: String, model: String, offerSdp: String): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("https://api.openai.com/v1/realtime?model=$model")
                .header("Authorization", "Bearer $clientSecret")
                .header("Content-Type", "application/sdp")
                .post(offerSdp.toRequestBody("application/sdp".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("OpenAI WebRTC SDP exchange failed: HTTP ${response.code}")
                }
                response.body?.string().orEmpty()
            }
        }

    private fun handleRealtimeMessage(data: ByteBuffer) {
        val bytes = ByteArray(data.remaining())
        data.get(bytes)
        val payload = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull() ?: return
        val type = payload.optString("type")
        val config = currentConfig ?: return

        if (type.contains("transcription") && payload.has("transcript")) {
            val transcript = payload.optString("transcript")
            lastOriginalText = transcript
            _events.tryEmit(
                InterpreterEvent.OriginalTranscript(
                    language = config.sourceLanguage,
                    text = transcript,
                ),
            )
            return
        }

        val translatedText = payload.optString("text").ifBlank { payload.optString("transcript") }
        if (translatedText.isBlank() || !type.startsWith("response.")) {
            return
        }

        val translation = InterpreterEvent.Translation(
            sourceLanguage = config.sourceLanguage,
            targetLanguage = config.targetLanguage,
            originalText = lastOriginalText,
            literalTranslation = translatedText,
            polishedTranslation = translatedText,
        )
        lastTranslation = translation
        _events.tryEmit(translation)
    }

    private fun sendRealtimeEvent(json: String) {
        val buffer = DataChannel.Buffer(
            ByteBuffer.wrap(json.toByteArray(Charsets.UTF_8)),
            false,
        )
        dataChannel?.send(buffer)
    }
}

private suspend fun PeerConnection.awaitOffer(): SessionDescription =
    suspendCancellableCoroutine { continuation ->
        createOffer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) {
                continuation.resume(description)
            }

            override fun onSetSuccess() = Unit

            override fun onCreateFailure(error: String) {
                continuation.resumeWithException(IllegalStateException(error))
            }

            override fun onSetFailure(error: String) {
                continuation.resumeWithException(IllegalStateException(error))
            }
        }, MediaConstraints())
    }

private suspend fun PeerConnection.awaitSetLocalDescription(description: SessionDescription) {
    awaitSetDescription { observer -> setLocalDescription(observer, description) }
}

private suspend fun PeerConnection.awaitSetRemoteDescription(description: SessionDescription) {
    awaitSetDescription { observer -> setRemoteDescription(observer, description) }
}

private suspend fun awaitSetDescription(block: (SdpObserver) -> Unit) {
    suspendCancellableCoroutine { continuation ->
        block(
            object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) = Unit

                override fun onSetSuccess() {
                    continuation.resume(Unit)
                }

                override fun onCreateFailure(error: String) {
                    continuation.resumeWithException(IllegalStateException(error))
                }

                override fun onSetFailure(error: String) {
                    continuation.resumeWithException(IllegalStateException(error))
                }
            },
        )
    }
}
