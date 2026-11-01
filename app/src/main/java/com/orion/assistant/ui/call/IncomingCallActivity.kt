package com.orion.assistant.ui.call

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.orion.assistant.agent.OrionAgent
import com.orion.assistant.service.call.CallManager
import com.orion.assistant.service.call.CallState
import com.orion.assistant.voice.VoiceEngine
import kotlinx.coroutines.launch

class IncomingCallActivity : ComponentActivity() {

    private lateinit var voiceEngine: VoiceEngine
    private lateinit var agent: OrionAgent

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startVoiceLoop()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        val callerName = intent.getStringExtra("CALLER_NAME") ?: "Orion AI"
        val callReason = intent.getStringExtra("CALL_REASON") ?: "Incoming notification call"

        voiceEngine = VoiceEngine(this)
        agent = OrionAgent(this)

        setContent {
            val scope = rememberCoroutineScope()
            var liveTranscript by remember { mutableStateOf("") }
            var aiSpeechResponse by remember { mutableStateOf("") }
            val isListening by voiceEngine.isListening.collectAsState()
            val isSpeaking by voiceEngine.isSpeaking.collectAsState()
            val statusMessage by voiceEngine.statusMessage.collectAsState()

            CallScreen(
                callerName = callerName,
                callReason = callReason,
                statusMessage = statusMessage,
                liveTranscript = liveTranscript,
                aiSpeechResponse = aiSpeechResponse,
                isListening = isListening,
                isSpeaking = isSpeaking,
                onAnswer = {
                    CallManager.answerCall(this)
                    checkMicAndStartCall(
                        onGranted = {
                            voiceEngine.speak("Hello! Orion is listening.") {
                                startVoiceLoopInternal(scope) { transcript, reply ->
                                    liveTranscript = transcript
                                    aiSpeechResponse = reply
                                }
                            }
                        }
                    )
                },
                onManualMicTap = {
                    if (isListening) {
                        voiceEngine.stopListening()
                    } else {
                        startVoiceLoopInternal(scope) { transcript, reply ->
                            liveTranscript = transcript
                            aiSpeechResponse = reply
                        }
                    }
                },
                onDecline = {
                    voiceEngine.stopListening()
                    voiceEngine.stopSpeaking()
                    CallManager.endCall(this)
                    finish()
                }
            )
        }
    }

    private fun checkMicAndStartCall(onGranted: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            onGranted()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceLoop() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
        startVoiceLoopInternal(scope) { _, _ -> }
    }

    private fun sanitizeSpokenText(text: String): String {
        val trimmed = text.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            val regex = "\"message\"\\s*:\\s*\"(.*?)\"".toRegex(RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(trimmed)
            if (match != null) {
                return match.groupValues[1].replace("\\\"", "\"").replace("\\n", " ")
            }
        }
        return trimmed
    }

    private fun startVoiceLoopInternal(
        scope: kotlinx.coroutines.CoroutineScope,
        onStateUpdate: (String, String) -> Unit
    ) {
        voiceEngine.startListening(
            onResult = { userSpokenText ->
                onStateUpdate(userSpokenText, "Processing...")
                scope.launch {
                    val result = agent.processCommand(userSpokenText) { progress ->
                        onStateUpdate(userSpokenText, progress)
                    }
                    val cleanDetails = sanitizeSpokenText(result.details)
                    val reply = if (result.success) {
                        if (result.actionName == "Orion") cleanDetails else "${result.actionName}: $cleanDetails"
                    } else {
                        "Sorry, I could not complete that: $cleanDetails"
                    }
                    onStateUpdate(userSpokenText, reply)

                    voiceEngine.speak(reply) {
                        // Keep the conversation going
                        startVoiceLoopInternal(scope, onStateUpdate)
                    }
                }
            },
            onPartial = { partial ->
                onStateUpdate(partial, "")
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceEngine.release()
    }
}

@Composable
fun CallScreen(
    callerName: String,
    callReason: String,
    statusMessage: String,
    liveTranscript: String,
    aiSpeechResponse: String,
    isListening: Boolean,
    isSpeaking: Boolean,
    onAnswer: () -> Unit,
    onManualMicTap: () -> Unit,
    onDecline: () -> Unit
) {
    val callState by CallManager.callState.collectAsState()

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = if (isSpeaking || isListening) 1.0f else 0.96f,
        targetValue = if (isSpeaking) 1.25f else if (isListening) 1.15f else 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isSpeaking) 600 else 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val orbColor = when {
        isSpeaking -> Color(0xFF00E5FF) // Cyan when speaking
        isListening -> Color(0xFF00E676) // Green when listening
        else -> Color(0xFF7C4DFF) // Purple when idle
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0A0E17), Color(0xFF131B2A))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header Info
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 40.dp)
            ) {
                Text(
                    text = callerName,
                    color = Color.White,
                    fontSize = 28.sp,
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (callState == CallState.RINGING) callReason else statusMessage,
                    color = orbColor,
                    fontSize = 15.sp
                )
            }

            // Real-time Subtitles / Transcription
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                if (liveTranscript.isNotBlank()) {
                    Text(
                        text = "\"$liveTranscript\"",
                        color = Color.White,
                        fontSize = 16.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                if (aiSpeechResponse.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = aiSpeechResponse,
                        color = Color(0xFF00E5FF),
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 4
                    )
                }
            }

            // Interactive Glowing AI Visualizer (Tap to force mic on/off)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(190.dp)
                    .scale(pulseScale)
                    .clickable {
                        if (callState == CallState.ACTIVE) {
                            onManualMicTap()
                        }
                    }
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                orbColor.copy(alpha = 0.5f),
                                orbColor.copy(alpha = 0.15f),
                                Color.Transparent
                            )
                        ),
                        shape = CircleShape
                    )
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(110.dp)
                        .background(orbColor, shape = CircleShape)
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.Default.Mic else if (isSpeaking) Icons.Default.Call else Icons.Default.MicOff,
                        contentDescription = null,
                        tint = Color(0xFF0A0E17),
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            // Call Controls
            if (callState == CallState.RINGING) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onDecline,
                        modifier = Modifier
                            .size(72.dp)
                            .background(Color(0xFFFF1744), shape = CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CallEnd,
                            contentDescription = "Decline",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    IconButton(
                        onClick = onAnswer,
                        modifier = Modifier
                            .size(72.dp)
                            .background(Color(0xFF00E676), shape = CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = "Answer",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Manual Mic Toggle button
                    IconButton(
                        onClick = onManualMicTap,
                        modifier = Modifier
                            .size(64.dp)
                            .background(if (isListening) Color(0xFF00E676) else Color(0xFF1E293B), shape = CircleShape)
                    ) {
                        Icon(
                            imageVector = if (isListening) Icons.Default.Mic else Icons.Default.MicOff,
                            contentDescription = "Mic Toggle",
                            tint = if (isListening) Color(0xFF0A0E17) else Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    // Hang up
                    IconButton(
                        onClick = onDecline,
                        modifier = Modifier
                            .size(72.dp)
                            .background(Color(0xFFFF1744), shape = CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CallEnd,
                            contentDescription = "End Call",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }
    }
}
