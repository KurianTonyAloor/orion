package com.orion.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class VoiceEngine(private val context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "OrionVoiceEngine"
        // Android SpeechRecognizer Error 11: ERROR_CANNOT_CHECK_SUPPORT / engine binding dropped
        const val ERROR_CANNOT_CHECK_SUPPORT = 11
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTtsInitialized = false
    private var pendingSpeakText: String? = null
    private var pendingSpeakCallback: (() -> Unit)? = null

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _statusMessage = MutableStateFlow("Audio ready")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private var onSpeechResultListener: ((String) -> Unit)? = null
    private var onSpeechPartialListener: ((String) -> Unit)? = null
    private var activeSpeechCallback: (() -> Unit)? = null

    private var shouldKeepListening = false

    init {
        mainHandler.post {
            textToSpeech = TextToSpeech(context.applicationContext, this)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsInitialized = true
            textToSpeech?.language = Locale.US
            textToSpeech?.setSpeechRate(1.05f)
            textToSpeech?.setPitch(1.0f)

            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _isSpeaking.value = true
                    _statusMessage.value = "Orion is speaking..."
                }

                override fun onDone(utteranceId: String?) {
                    _isSpeaking.value = false
                    mainHandler.post {
                        activeSpeechCallback?.invoke()
                        activeSpeechCallback = null
                    }
                }

                override fun onError(utteranceId: String?) {
                    _isSpeaking.value = false
                    mainHandler.post {
                        activeSpeechCallback?.invoke()
                        activeSpeechCallback = null
                    }
                }
            })

            pendingSpeakText?.let { text ->
                val callback = pendingSpeakCallback
                pendingSpeakText = null
                pendingSpeakCallback = null
                speak(text, callback)
            }
        } else {
            Log.e(TAG, "TextToSpeech init failed with code: $status")
            _statusMessage.value = "TTS init failed"
            pendingSpeakCallback?.invoke()
            pendingSpeakCallback = null
        }
    }

    fun startListening(
        onResult: (String) -> Unit,
        onPartial: ((String) -> Unit)? = null
    ) {
        shouldKeepListening = true
        onSpeechResultListener = onResult
        onSpeechPartialListener = onPartial
        restartRecognizerSession()
    }

    private fun restartRecognizerSession() {
        mainHandler.post {
            if (_isSpeaking.value) {
                Log.d(TAG, "Postponing listening while speaking")
                return@post
            }

            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                _statusMessage.value = "Speech recognition unavailable"
                return@post
            }

            cleanupRecognizer()

            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext).apply {
                    setRecognitionListener(createRecognitionListener())
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toString())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }

                speechRecognizer?.startListening(intent)
                _isListening.value = true
                _statusMessage.value = "Listening to you..."
            } catch (e: Exception) {
                Log.e(TAG, "Error starting speech recognition", e)
                _isListening.value = false
                _statusMessage.value = "Reconnecting mic..."
                retryAfterDelay(800)
            }
        }
    }

    private fun cleanupRecognizer() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
        _isListening.value = false
    }

    fun stopListening() {
        shouldKeepListening = false
        mainHandler.post {
            cleanupRecognizer()
        }
    }

    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        mainHandler.post {
            if (text.isBlank()) {
                onComplete?.invoke()
                return@post
            }

            // Immediately halt mic to prevent echo and Error 11 audio collision
            cleanupRecognizer()

            if (!isTtsInitialized || textToSpeech == null) {
                pendingSpeakText = text
                pendingSpeakCallback = onComplete
                mainHandler.postDelayed({
                    if (pendingSpeakCallback != null) {
                        pendingSpeakCallback?.invoke()
                        pendingSpeakCallback = null
                    }
                }, 1200)
                return@post
            }

            activeSpeechCallback = onComplete
            val utteranceId = "orion_${System.currentTimeMillis()}"
            val res = textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (res != TextToSpeech.SUCCESS) {
                _isSpeaking.value = false
                activeSpeechCallback?.invoke()
                activeSpeechCallback = null
            }
        }
    }

    fun stopSpeaking() {
        mainHandler.post {
            textToSpeech?.stop()
            _isSpeaking.value = false
            activeSpeechCallback = null
        }
    }

    private fun retryAfterDelay(delayMs: Long) {
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (shouldKeepListening && !_isSpeaking.value) {
                restartRecognizerSession()
            }
        }, delayMs)
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _isListening.value = true
                _statusMessage.value = "Listening to you..."
            }

            override fun onBeginningOfSpeech() {
                _statusMessage.value = "Hearing speech..."
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                _isListening.value = false
                _statusMessage.value = "Processing speech..."
            }

            override fun onError(error: Int) {
                _isListening.value = false
                val reason = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic permission missing"
                    SpeechRecognizer.ERROR_NETWORK -> "Network issue"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Waiting for speech..."
                    ERROR_CANNOT_CHECK_SUPPORT -> "Session reset (Error 11)"
                    else -> "Error code: $error"
                }

                Log.w(TAG, "SpeechRecognizer onError: $reason ($error)")
                _statusMessage.value = if (error == ERROR_CANNOT_CHECK_SUPPORT) "Reconnecting..." else reason

                // Automatically heal the session
                if (shouldKeepListening && !_isSpeaking.value) {
                    val backoff = if (error == ERROR_CANNOT_CHECK_SUPPORT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 600L else 400L
                    retryAfterDelay(backoff)
                }
            }

            override fun onResults(results: Bundle?) {
                _isListening.value = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val recognized = matches?.firstOrNull()?.trim()

                if (!recognized.isNullOrBlank()) {
                    _statusMessage.value = "Heard: $recognized"
                    onSpeechResultListener?.invoke(recognized)
                } else if (shouldKeepListening && !_isSpeaking.value) {
                    retryAfterDelay(300)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partial = matches?.firstOrNull()?.trim()
                if (!partial.isNullOrBlank()) {
                    onSpeechPartialListener?.invoke(partial)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    fun release() {
        shouldKeepListening = false
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post {
            cleanupRecognizer()
            textToSpeech?.stop()
            textToSpeech?.shutdown()
            textToSpeech = null
            isTtsInitialized = false
        }
    }
}
