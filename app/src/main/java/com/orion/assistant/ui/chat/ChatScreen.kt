package com.orion.assistant.ui.chat

import android.content.Intent
import android.graphics.BitmapFactory
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orion.assistant.service.accessibility.OrionAccessibilityService
import com.orion.assistant.voice.VoiceEngine

import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    isModelReady: Boolean = false,
    onDownloadModelClick: () -> Unit = {},
    onCallRequest: () -> Unit
) {
    val context = LocalContext.current
    val messages by viewModel.messages.collectAsState()
    val isAccessibilityConnected by OrionAccessibilityService.isServiceConnected.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val voiceEngine = remember { VoiceEngine(context) }
    val isListening by voiceEngine.isListening.collectAsState()

    DisposableEffect(Unit) {
        onDispose {
            voiceEngine.release()
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "ORION AI",
                            color = Color(0xFF00E5FF),
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Text(
                            text = "Snapdragon 695 • On-Device",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onDownloadModelClick) {
                        Icon(
                            imageVector = if (isModelReady) Icons.Default.Psychology else Icons.Default.Memory,
                            contentDescription = "Model Status",
                            tint = if (isModelReady) Color(0xFF00E676) else Color(0xFFFFB300)
                        )
                    }
                    IconButton(onClick = onCallRequest) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = "Simulate Call",
                            tint = Color(0xFF00E5FF)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0A0E17)
                )
            )
        },
        containerColor = Color(0xFF0A0E17)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Service Status Card
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(12.dp),
                color = if (isAccessibilityConnected) Color(0xFF132A20) else Color(0xFF2A1B13)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isAccessibilityConnected) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (isAccessibilityConnected) Color(0xFF00E676) else Color(0xFFFFB300),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isAccessibilityConnected) "Device Control Active" else "Accessibility Inactive",
                            color = Color.White,
                            fontSize = 13.sp
                        )
                    }

                    if (!isAccessibilityConnected) {
                        Row {
                            TextButton(onClick = {
                                try {
                                    val intent = Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        android.net.Uri.parse("package:${context.packageName}")
                                    ).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                            }) {
                                Text("App Info", color = Color(0xFF94A3B8), fontSize = 11.sp)
                            }
                            TextButton(onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                            }) {
                                Text("Enable", color = Color(0xFF00E5FF), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // Floating Bubble Toggle Card
            val isOverlayRunning by com.orion.assistant.service.overlay.OrionFloatingService.isRunning.collectAsState()
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF131B2A)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Floating Assistant Bubble",
                        color = Color.White,
                        fontSize = 13.sp
                    )
                    Switch(
                        checked = isOverlayRunning,
                        onCheckedChange = { enable ->
                            if (enable) {
                                if (Settings.canDrawOverlays(context)) {
                                    val serviceIntent = Intent(context, com.orion.assistant.service.overlay.OrionFloatingService::class.java)
                                    context.startForegroundService(serviceIntent)
                                } else {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        android.net.Uri.parse("package:${context.packageName}")
                                    ).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                }
                            } else {
                                val serviceIntent = Intent(context, com.orion.assistant.service.overlay.OrionFloatingService::class.java)
                                context.stopService(serviceIntent)
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF00E5FF),
                            checkedTrackColor = Color(0xFF00E5FF).copy(alpha = 0.4f)
                        )
                    )
                }
            }

            // Quick Action Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = false,
                    onClick = { viewModel.sendMessage("Call me", context) },
                    label = { Text("Call Me", fontSize = 12.sp) }
                )
                FilterChip(
                    selected = false,
                    onClick = { viewModel.sendMessage("open settings", context) },
                    label = { Text("Open Settings", fontSize = 12.sp) }
                )
                FilterChip(
                    selected = false,
                    onClick = { viewModel.sendMessage("inspect screen", context) },
                    label = { Text("Inspect Screen", fontSize = 12.sp) }
                )
                FilterChip(
                    selected = false,
                    onClick = { viewModel.sendMessage("take a screenshot", context) },
                    label = { Text("Screenshot", fontSize = 12.sp) }
                )
                FilterChip(
                    selected = false,
                    onClick = { viewModel.sendMessage("go home", context) },
                    label = { Text("Go Home", fontSize = 12.sp) }
                )
            }

            // Messages List
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(messages) { message ->
                    ChatMessageItem(message = message)
                }
            }

            // Input Bar with Mic button
            Surface(
                color = Color(0xFF131B2A),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Voice Mic Button
                    IconButton(
                        onClick = {
                            if (isListening) {
                                voiceEngine.stopListening()
                            } else {
                                voiceEngine.startListening(
                                    onResult = { spokenText ->
                                        inputText = spokenText
                                        viewModel.sendMessage(spokenText, context)
                                    },
                                    onPartial = { partial ->
                                        inputText = partial
                                    }
                                )
                            }
                        },
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                if (isListening) Color(0xFF00E676) else Color(0xFF1E293B),
                                shape = RoundedCornerShape(22.dp)
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Voice Input",
                            tint = if (isListening) Color(0xFF0A0E17) else Color(0xFF00E5FF)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    TextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                if (isListening) "Listening..." else "Ask or command Orion...",
                                color = if (isListening) Color(0xFF00E676) else Color(0xFF64748B)
                            )
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF1E293B),
                            unfocusedContainerColor = Color(0xFF1E293B),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = Color(0xFF00E5FF),
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 4
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                viewModel.sendMessage(inputText, context)
                                inputText = ""
                            }
                        },
                        modifier = Modifier
                            .size(44.dp)
                            .background(Color(0xFF00E5FF), shape = RoundedCornerShape(22.dp))
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = Color(0xFF0A0E17)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChatMessageItem(message: ChatMessage) {
    val isUser = message.sender == MessageSender.USER
    val isSystem = message.sender == MessageSender.SYSTEM

    val alignment = when {
        isUser -> Alignment.End
        else -> Alignment.Start
    }

    val bubbleColor = when {
        isUser -> Color(0xFF00E5FF)
        isSystem -> Color(0xFF33202A)
        else -> Color(0xFF1E293B)
    }

    val textColor = when {
        isUser -> Color(0xFF0A0E17)
        isSystem -> Color(0xFFFFB3BA)
        else -> Color(0xFFE2E8F0)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (!message.imagePath.isNullOrBlank()) {
                    val bitmap = remember(message.imagePath) {
                        BitmapFactory.decodeFile(message.imagePath)
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Captured Image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                Text(
                    text = message.text,
                    color = textColor,
                    fontSize = 14.sp
                )
            }
        }
    }
}
