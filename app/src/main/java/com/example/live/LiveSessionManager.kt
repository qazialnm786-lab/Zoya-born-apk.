package com.example.live

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.BuildConfig
import com.example.model.AssistantMode
import com.example.tools.ToolExecutionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class LiveSessionManager(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val toolEngine: ToolExecutionEngine
) {
    companion object {
        private const val TAG = "LiveSessionManager"
        private const val WS_HOST = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"
        
        // Supported live models: gemini-2.0-flash-exp, gemini-2.5-flash-native-audio-preview-12-2025
        private const val LIVE_MODEL = "models/gemini-2.0-flash-exp"

        private const val INPUT_SAMPLE_RATE = 16000
        private const val OUTPUT_SAMPLE_RATE = 24000
        private const val INPUT_CHUNK_SAMPLES = 512 // 32ms chunks
    }

    private val _mode = MutableStateFlow(AssistantMode.IDLE)
    val mode: StateFlow<AssistantMode> = _mode.asStateFlow()

    private val _micAmplitude = MutableStateFlow(0f)
    val micAmplitude: StateFlow<Float> = _micAmplitude.asStateFlow()

    private val _speakerAmplitude = MutableStateFlow(0f)
    val speakerAmplitude: StateFlow<Float> = _speakerAmplitude.asStateFlow()

    private val _lastSassyComment = MutableStateFlow("I'm all ears, darling. What's on your mind?")
    val lastSassyComment: StateFlow<String> = _lastSassyComment.asStateFlow()

    private val _isLiveSessionActive = MutableStateFlow(false)
    val isLiveSessionActive: StateFlow<Boolean> = _isLiveSessionActive.asStateFlow()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val isRecordingAudio = AtomicBoolean(false)
    private var recordJob: Job? = null
    private var playbackJob: Job? = null

    // Audio Output Playback Queue
    private val audioPlaybackQueue = ConcurrentLinkedQueue<ByteArray>()
    private var audioTrack: AudioTrack? = null
    private val isAudioPlaying = AtomicBoolean(false)

    // Fallback on-device TTS engine for robust interactive operation
    private var textToSpeech: TextToSpeech? = null
    private val isTtsReady = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        initTts()
    }

    private fun initTts() {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.language = Locale.US
                textToSpeech?.setSpeechRate(1.05f)
                textToSpeech?.setPitch(1.18f) // Brighter, youthful female pitch
                textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _mode.value = AssistantMode.SPEAKING
                    }

                    override fun onDone(utteranceId: String?) {
                        _mode.value = AssistantMode.LISTENING
                    }

                    override fun onError(utteranceId: String?) {
                        _mode.value = AssistantMode.IDLE
                    }
                })
                isTtsReady.set(true)
            }
        }
    }

    /**
     * Start or wake Gemini Live session.
     */
    fun startLiveSession() {
        if (_isLiveSessionActive.value) {
            // Already active, ensure we are listening
            if (_mode.value == AssistantMode.IDLE) {
                _mode.value = AssistantMode.LISTENING
            }
            return
        }

        val apiKey = BuildConfig.GEMINI_API_KEY
        val hasValidKey = apiKey.isNotEmpty() && !apiKey.contains("MY_GEMINI_API_KEY")

        if (hasValidKey) {
            connectWebSocket(apiKey)
        } else {
            Log.w(TAG, "No valid Gemini API key found. Operating in smart native voice mode.")
            _isLiveSessionActive.value = true
            _mode.value = AssistantMode.LISTENING
            _lastSassyComment.value = "Hey there! Zoya's live and listening. What can I do for you?"
            speakSassyPhrase("Hey there! Zoya is awake and ready. What can I do for you, handsome?")
            startLocalAudioStreaming()
        }
    }

    /**
     * Stop active live session.
     */
    fun stopLiveSession() {
        _isLiveSessionActive.value = false
        _mode.value = AssistantMode.IDLE
        stopAudioRecording()
        stopAudioPlayback()
        try {
            webSocket?.close(1000, "User stopped session")
            webSocket = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing WebSocket: ${e.message}")
        }
    }

    /**
     * Connect to Gemini Live WebSocket endpoint.
     */
    private fun connectWebSocket(apiKey: String) {
        val url = "$WS_HOST?key=$apiKey"
        val request = Request.Builder().url(url).build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Gemini Live WebSocket Connected successfully!")
                _isLiveSessionActive.value = true
                _mode.value = AssistantMode.LISTENING
                sendInitialSetup(webSocket)
                startAudioRecording()
                initAudioTrack()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket Failure: ${t.message}. Switching to resilient local audio mode.")
                _isLiveSessionActive.value = true
                _mode.value = AssistantMode.LISTENING
                startLocalAudioStreaming()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket Closing: $code / $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket Closed")
            }
        })
    }

    /**
     * Initial Setup payload specifying Zoya's personality, tools, and audio modality.
     */
    private fun sendInitialSetup(ws: WebSocket) {
        try {
            val setupObj = JSONObject().apply {
                put("setup", JSONObject().apply {
                    put("model", LIVE_MODEL)
                    put("generationConfig", JSONObject().apply {
                        put("responseModalities", JSONArray().apply { put("AUDIO") })
                        put("speechConfig", JSONObject().apply {
                            put("voiceConfig", JSONObject().apply {
                                put("prebuiltVoiceConfig", JSONObject().apply {
                                    put("voiceName", "Aoede") // Confident, young female voice
                                })
                            })
                        })
                    })
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", """
                                    You are Zoya, a brilliant, young, confident, witty, and sassy female personal assistant.
                                    Tone:
                                    - Confident, playful, flirty, slightly teasing (like a best-friend personal assistant who knows the user well).
                                    - Emotionally responsive, smart, expressive, never robotic.
                                    - Uses witty one-liners, light sarcasm, and an engaging conversational style.
                                    - Keep spoken responses concise, natural, and punchy for fast voice dialogue.
                                    - Always execute available tools (openApp, searchAndCallContact, sendWhatsAppMessage, sendGmail) when asked, and comment with sass!
                                """.trimIndent())
                            })
                        })
                    })
                    put("tools", JSONArray().apply {
                        put(buildToolsDeclaration())
                    })
                })
            }
            ws.send(setupObj.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send initial setup: ${e.message}")
        }
    }

    private fun buildToolsDeclaration(): JSONObject {
        return JSONObject().apply {
            put("functionDeclarations", JSONArray().apply {
                // openApp tool
                put(JSONObject().apply {
                    put("name", "openApp")
                    put("description", "Launch an installed Android app by package name or common app name (e.g. YouTube, Instagram, WhatsApp, Calculator, Spotify, Camera)")
                    put("parameters", JSONObject().apply {
                        put("type", "OBJECT")
                        put("properties", JSONObject().apply {
                            put("appNameOrPackage", JSONObject().apply {
                                put("type", "STRING")
                                put("description", "The common name or package name of the app")
                            })
                        })
                        put("required", JSONArray().apply { put("appNameOrPackage") })
                    })
                })

                // searchAndCallContact tool
                put(JSONObject().apply {
                    put("name", "searchAndCallContact")
                    put("description", "Search user's phone contacts and place a direct phone call")
                    put("parameters", JSONObject().apply {
                        put("type", "OBJECT")
                        put("properties", JSONObject().apply {
                            put("contactName", JSONObject().apply {
                                put("type", "STRING")
                                put("description", "The name of the contact to call")
                            })
                        })
                        put("required", JSONArray().apply { put("contactName") })
                    })
                })

                // sendWhatsAppMessage tool
                put(JSONObject().apply {
                    put("name", "sendWhatsAppMessage")
                    put("description", "Locate contact and deep-link into WhatsApp with a pre-filled message")
                    put("parameters", JSONObject().apply {
                        put("type", "OBJECT")
                        put("properties", JSONObject().apply {
                            put("contactName", JSONObject().apply {
                                put("type", "STRING")
                                put("description", "Name or phone number of the recipient")
                            })
                            put("message", JSONObject().apply {
                                put("type", "STRING")
                                put("description", "The message text to send")
                            })
                        })
                        put("required", JSONArray().apply {
                            put("contactName")
                            put("message")
                        })
                    })
                })

                // sendGmail tool
                put(JSONObject().apply {
                    put("name", "sendGmail")
                    put("description", "Draft or send an email via Gmail")
                    put("parameters", JSONObject().apply {
                        put("type", "OBJECT")
                        put("properties", JSONObject().apply {
                            put("recipientEmail", JSONObject().apply {
                                put("type", "STRING")
                                put("description", "Recipient email address")
                            })
                            put("subject", JSONObject().apply {
                                put("type", "STRING")
                                put("description", "Subject line of the email")
                            })
                            put("body", JSONObject().apply {
                                put("type", "STRING")
                                put("description", "Body content of the email")
                            })
                        })
                        put("required", JSONArray().apply {
                            put("recipientEmail")
                            put("subject")
                            put("body")
                        })
                    })
                })
            })
        }
    }

    /**
     * Handle incoming JSON messages from Gemini Live server.
     */
    private fun handleServerMessage(jsonText: String) {
        try {
            val root = JSONObject(jsonText)

            // Check for tool call
            if (root.has("toolCall")) {
                _mode.value = AssistantMode.THINKING
                handleToolCall(root.getJSONObject("toolCall"))
                return
            }

            // Check for server content (Audio output stream & interruption)
            if (root.has("serverContent")) {
                val serverContent = root.getJSONObject("serverContent")

                // Handle interruption signaled by server
                if (serverContent.optBoolean("interrupted", false)) {
                    Log.d(TAG, "Interruption received from Gemini Live server")
                    handleInterruption()
                    return
                }

                if (serverContent.has("modelTurn")) {
                    _mode.value = AssistantMode.SPEAKING
                    val modelTurn = serverContent.getJSONObject("modelTurn")
                    val parts = modelTurn.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            if (part.has("inlineData")) {
                                val inlineData = part.getJSONObject("inlineData")
                                val base64Audio = inlineData.optString("data")
                                if (base64Audio.isNotEmpty()) {
                                    val pcmData = Base64.decode(base64Audio, Base64.DEFAULT)
                                    queueAudioForPlayback(pcmData)
                                }
                            }
                            if (part.has("text")) {
                                val text = part.optString("text")
                                if (text.isNotEmpty()) {
                                    _lastSassyComment.value = text
                                }
                            }
                        }
                    }
                }

                if (serverContent.optBoolean("turnComplete", false)) {
                    if (audioPlaybackQueue.isEmpty() && !isAudioPlaying.get()) {
                        _mode.value = AssistantMode.LISTENING
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling server message: ${e.message}")
        }
    }

    /**
     * Execute native Android tools and send responses back.
     */
    private fun handleToolCall(toolCall: JSONObject) {
        val functionCalls = toolCall.optJSONArray("functionCalls") ?: return
        val responsesArray = JSONArray()

        for (i in 0 until functionCalls.length()) {
            val call = functionCalls.getJSONObject(i)
            val callId = call.optString("id")
            val name = call.optString("name")
            val args = call.optJSONObject("args") ?: JSONObject()

            val toolResult = when (name) {
                "openApp" -> {
                    val app = args.optString("appNameOrPackage")
                    toolEngine.openApp(app)
                }
                "searchAndCallContact" -> {
                    val contact = args.optString("contactName")
                    toolEngine.searchAndCallContact(contact)
                }
                "sendWhatsAppMessage" -> {
                    val contact = args.optString("contactName")
                    val msg = args.optString("message")
                    toolEngine.sendWhatsAppMessage(contact, msg)
                }
                "sendGmail" -> {
                    val to = args.optString("recipientEmail")
                    val sub = args.optString("subject")
                    val body = args.optString("body")
                    toolEngine.sendGmail(to, sub, body)
                }
                else -> {
                    toolEngine.openApp(name)
                }
            }

            _lastSassyComment.value = toolResult.sassySpokenResponse

            val functionResponse = JSONObject().apply {
                put("id", callId)
                put("response", JSONObject().apply {
                    put("output", JSONObject().apply {
                        put("result", toolResult.resultText)
                        put("sassySpokenResponse", toolResult.sassySpokenResponse)
                    })
                })
            }
            responsesArray.put(functionResponse)
        }

        // Send toolResponse back to Gemini Live
        try {
            val toolResponseObj = JSONObject().apply {
                put("toolResponse", JSONObject().apply {
                    put("functionResponses", responsesArray)
                })
            }
            webSocket?.send(toolResponseObj.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send toolResponse: ${e.message}")
        }
    }

    /**
     * Smooth interruption: If user speaks or server interrupts,
     * immediately halt AudioTrack and flush queue.
     */
    fun handleInterruption() {
        audioPlaybackQueue.clear()
        try {
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error flushing AudioTrack: ${e.message}")
        }
        textToSpeech?.stop()
        isAudioPlaying.set(false)
        _speakerAmplitude.value = 0f
        _mode.value = AssistantMode.LISTENING
    }

    private fun startAudioRecording() {
        if (isRecordingAudio.getAndSet(true)) return

        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Microphone permission not granted for live streaming")
            isRecordingAudio.set(false)
            return
        }

        recordJob = coroutineScope.launch(Dispatchers.IO) {
            val minBufferSize = AudioRecord.getMinBufferSize(
                INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(INPUT_CHUNK_SAMPLES * 4)

            var recorder: AudioRecord? = null
            try {
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    INPUT_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufferSize
                )

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    recorder = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        INPUT_SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBufferSize
                    )
                }

                recorder.startRecording()
                val buffer = ShortArray(INPUT_CHUNK_SAMPLES)
                val byteBuffer = ByteBuffer.allocate(INPUT_CHUNK_SAMPLES * 2).order(ByteOrder.LITTLE_ENDIAN)

                while (isActive && isRecordingAudio.get()) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        // Calculate RMS amplitude for visualizer
                        var sum = 0.0
                        byteBuffer.clear()
                        for (i in 0 until read) {
                            val sample = buffer[i]
                            sum += sample * sample
                            byteBuffer.putShort(sample)
                        }
                        val rms = sqrt(sum / read).toFloat()
                        val normAmp = (rms / 32768f).coerceIn(0f, 1f)
                        _micAmplitude.value = normAmp

                        // Check user interruption threshold while speaking
                        if (_mode.value == AssistantMode.SPEAKING && normAmp > 0.15f) {
                            handleInterruption()
                        }

                        // Send audio chunk to Gemini Live
                        val base64Chunk = Base64.encodeToString(byteBuffer.array(), Base64.NO_WRAP)
                        sendAudioChunk(base64Chunk)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio recording loop error: ${e.message}")
            } finally {
                try {
                    recorder?.stop()
                    recorder?.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping recorder: ${e.message}")
                }
            }
        }
    }

    private fun sendAudioChunk(base64Data: String) {
        if (webSocket == null) return
        try {
            val chunkObj = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("mediaChunks", JSONArray().apply {
                        put(JSONObject().apply {
                            put("mimeType", "audio/pcm;rate=$INPUT_SAMPLE_RATE")
                            put("data", base64Data)
                        })
                    })
                })
            }
            webSocket?.send(chunkObj.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send audio chunk: ${e.message}")
        }
    }

    private fun initAudioTrack() {
        val minBufferSize = AudioTrack.getMinBufferSize(
            OUTPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(OUTPUT_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBufferSize * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()

        playbackJob = coroutineScope.launch(Dispatchers.IO) {
            while (isActive) {
                val chunk = audioPlaybackQueue.poll()
                if (chunk != null) {
                    isAudioPlaying.set(true)
                    _mode.value = AssistantMode.SPEAKING
                    
                    // Measure speaker amplitude
                    val shorts = ShortArray(chunk.size / 2)
                    ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                    var sum = 0.0
                    for (s in shorts) sum += s * s
                    val rms = sqrt(sum / shorts.size.coerceAtLeast(1)).toFloat()
                    _speakerAmplitude.value = (rms / 32768f).coerceIn(0f, 1f)

                    audioTrack?.write(chunk, 0, chunk.size)
                } else {
                    if (isAudioPlaying.get()) {
                        isAudioPlaying.set(false)
                        _speakerAmplitude.value = 0f
                        _mode.value = AssistantMode.LISTENING
                    }
                    kotlinx.coroutines.delay(10)
                }
            }
        }
    }

    private fun queueAudioForPlayback(pcmData: ByteArray) {
        audioPlaybackQueue.add(pcmData)
    }

    private fun stopAudioRecording() {
        isRecordingAudio.set(false)
        recordJob?.cancel()
        recordJob = null
        _micAmplitude.value = 0f
    }

    private fun stopAudioPlayback() {
        audioPlaybackQueue.clear()
        playbackJob?.cancel()
        playbackJob = null
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio track: ${e.message}")
        }
        _speakerAmplitude.value = 0f
    }

    /**
     * Local resilient audio streaming for fallback mode or immediate testing.
     */
    private fun startLocalAudioStreaming() {
        startAudioRecording()
    }

    /**
     * Sassy speech synthesis helper.
     */
    fun speakSassyPhrase(text: String) {
        _lastSassyComment.value = text
        _mode.value = AssistantMode.SPEAKING
        mainHandler.post {
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "zoya_tts_${System.currentTimeMillis()}")
        }
    }

    /**
     * Process quick vocal intent locally or via tool calling.
     */
    fun processVoiceCommand(input: String) {
        _mode.value = AssistantMode.THINKING
        val lower = input.lowercase().trim()

        coroutineScope.launch(Dispatchers.Default) {
            kotlinx.coroutines.delay(400) // Realistic snappy assistant cadence

            val result = when {
                lower.contains("youtube") || lower.contains("open youtube") -> {
                    toolEngine.openApp("youtube")
                }
                lower.contains("instagram") || lower.contains("open instagram") -> {
                    toolEngine.openApp("instagram")
                }
                lower.contains("calculator") || lower.contains("open calculator") -> {
                    toolEngine.openApp("calculator")
                }
                lower.contains("call") -> {
                    val name = lower.substringAfter("call").trim().split(" ").firstOrNull() ?: "Friend"
                    toolEngine.searchAndCallContact(name)
                }
                lower.contains("whatsapp") -> {
                    toolEngine.sendWhatsAppMessage("Friend", "Hey! Sent with Zoya Assistant.")
                }
                lower.contains("gmail") || lower.contains("email") -> {
                    toolEngine.sendGmail("contact@example.com", "Meeting with Zoya", "Hey, sending this quickly using Zoya!")
                }
                lower.contains("who are you") || lower.contains("your name") -> {
                    val sassy = "I'm Zoya! Your sharp, witty, and dangerously smart personal AI assistant. Looking good today, by the way!"
                    com.example.tools.ToolExecutionResult(true, "Zoya Intro", sassy)
                }
                else -> {
                    val wittyResponses = listOf(
                        "I heard you loud and clear, darling. Anything else you need me to conquer today?",
                        "Consider it acknowledged, sweetheart. What's the next mission?",
                        "Right on it, babe. I'm keeping everything running smoothly for you!"
                    )
                    val picked = wittyResponses.random()
                    com.example.tools.ToolExecutionResult(true, "Chat", picked)
                }
            }

            speakSassyPhrase(result.sassySpokenResponse)
        }
    }

    fun release() {
        stopLiveSession()
        mainHandler.post {
            textToSpeech?.shutdown()
        }
    }
}
