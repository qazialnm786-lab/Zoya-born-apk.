package com.example.audio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

class WakeWordDetector(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit,
    private val onAudioAmplitude: ((Float) -> Unit)? = null
) {
    companion object {
        private const val TAG = "WakeWordDetector"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_SIZE = 640 // 40ms at 16kHz
    }

    private val isRunning = AtomicBoolean(false)
    private var recordingJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var isRecognizerActive = false
    private var lastTriggerTime = 0L

    // Acoustic pattern tracking for "Z-O-Y-A"
    private var stage = 0 // 0 = Idle, 1 = Fricative 'Z', 2 = Vowel 'O', 3 = Glide 'Y', 4 = Vowel 'A'
    private var stageStartTime = 0L

    fun start(coroutineScope: CoroutineScope) {
        if (isRunning.getAndSet(true)) return

        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Cannot start WakeWordDetector: RECORD_AUDIO permission not granted")
            isRunning.set(false)
            return
        }

        // Initialize Android SpeechRecognizer on main thread as complementary high-accuracy recognizer
        mainHandler.post {
            initSpeechRecognizer()
        }

        // Launch lightweight acoustic AudioRecord worker
        recordingJob = coroutineScope.launch(Dispatchers.IO) {
            runAudioRecordLoop()
        }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return

        recordingJob?.cancel()
        recordingJob = null

        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
                speechRecognizer = null
                isRecognizerActive = false
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up SpeechRecognizer: ${e.message}")
            }
        }
    }

    private fun runAudioRecordLoop() {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        ).coerceAtLeast(CHUNK_SIZE * 4)

        var audioRecord: AudioRecord? = null
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                minBufferSize
            )

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                return
            }

            audioRecord.startRecording()
            val buffer = ShortArray(CHUNK_SIZE)

            while (isRunning.get()) {
                val readCount = audioRecord.read(buffer, 0, buffer.size)
                if (readCount > 0) {
                    processAcousticFrame(buffer, readCount)
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException in AudioRecord: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Exception in AudioRecord loop: ${e.message}")
        } finally {
            try {
                if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop()
                }
                audioRecord?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing AudioRecord: ${e.message}")
            }
        }
    }

    /**
     * Real-time acoustic phonetics analysis:
     * "ZOYA" acoustic structure:
     * 1) 'Z': High Zero Crossing Rate (ZCR) with moderate RMS energy (fricative).
     * 2) 'O': Deep resonance vowel, low ZCR, higher RMS energy.
     * 3) 'Y': Semivowel / glide transition.
     * 4) 'A': Open vowel with sustained harmonic energy.
     */
    private fun processAcousticFrame(buffer: ShortArray, length: Int) {
        var sumSquares = 0.0
        var zeroCrossings = 0
        var prevSample = buffer[0].toInt()

        for (i in 0 until length) {
            val sample = buffer[i].toInt()
            sumSquares += sample * sample
            if ((sample >= 0 && prevSample < 0) || (sample < 0 && prevSample >= 0)) {
                zeroCrossings++
            }
            prevSample = sample
        }

        val rms = sqrt(sumSquares / length).toFloat()
        val normalizedEnergy = (rms / 32768f).coerceIn(0f, 1f)
        val zcr = zeroCrossings.toFloat() / length // normalized zero crossing rate

        onAudioAmplitude?.invoke(normalizedEnergy)

        val currentTime = System.currentTimeMillis()

        // Energy gate: voice activity
        if (normalizedEnergy > 0.04f) {
            // Check phoneme transitions
            when (stage) {
                0 -> {
                    // Looking for 'Z' onset: elevated ZCR with voiced component
                    if (zcr > 0.15f && normalizedEnergy > 0.05f) {
                        stage = 1
                        stageStartTime = currentTime
                    }
                }
                1 -> {
                    // Transition from 'Z' to 'O': ZCR drops, energy stays strong or peaks
                    if (currentTime - stageStartTime > 400) {
                        // Timed out for syllable transition
                        stage = 0
                    } else if (zcr < 0.10f && normalizedEnergy > 0.08f) {
                        stage = 2
                        stageStartTime = currentTime
                    }
                }
                2 -> {
                    // Transition through 'Y' to 'A': glide to open vowel
                    if (currentTime - stageStartTime > 600) {
                        stage = 0
                    } else if (normalizedEnergy > 0.07f && (currentTime - stageStartTime) > 120) {
                        stage = 3
                        stageStartTime = currentTime
                    }
                }
                3 -> {
                    // Final vowel 'A' completion
                    val totalDuration = currentTime - stageStartTime
                    if (totalDuration in 80..400) {
                        triggerWakeWord("Acoustic Phonetics Pattern Match")
                        stage = 0
                    } else if (totalDuration > 500) {
                        stage = 0
                    }
                }
            }

            // Also kick off speech recognition listener if not active and energy is high
            if (!isRecognizerActive && normalizedEnergy > 0.12f && currentTime - lastTriggerTime > 3000) {
                mainHandler.post {
                    startSpeechRecognizerListening()
                }
            }
        } else {
            // Silence timeout
            if (currentTime - stageStartTime > 500) {
                stage = 0
            }
        }
    }

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.d(TAG, "SpeechRecognizer not available on this device")
            return
        }

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        isRecognizerActive = false
                    }

                    override fun onError(error: Int) {
                        isRecognizerActive = false
                    }

                    override fun onResults(results: Bundle?) {
                        isRecognizerActive = false
                        handleSpeechResults(results)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        handleSpeechResults(partialResults)
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SpeechRecognizer: ${e.message}")
        }
    }

    private fun startSpeechRecognizerListening() {
        if (isRecognizerActive || speechRecognizer == null) return
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
            }
            speechRecognizer?.startListening(intent)
            isRecognizerActive = true
        } catch (e: Exception) {
            isRecognizerActive = false
        }
    }

    private fun handleSpeechResults(bundle: Bundle?) {
        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        for (text in matches) {
            val lower = text.lowercase().trim()
            if (lower.contains("zoya") || lower.contains("zoya assistant") || lower.contains("hey zoya") || lower.contains("hi zoya") || lower.contains("soya") || lower.contains("zoe")) {
                triggerWakeWord("SpeechRecognizer Keyword Match: $lower")
                break
            }
        }
    }

    private fun triggerWakeWord(source: String) {
        val now = System.currentTimeMillis()
        if (now - lastTriggerTime < 2500) return // Debounce triggers
        lastTriggerTime = now
        Log.i(TAG, "Wake word detected via $source!")

        mainHandler.post {
            onWakeWordDetected()
        }
    }
}
