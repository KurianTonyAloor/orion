package com.orion.assistant.ui.model

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.orion.assistant.llm.DownloadState
import com.orion.assistant.llm.ModelDownloadManager
import kotlinx.coroutines.launch

@Composable
fun ModelDownloadDialog(
    downloadManager: ModelDownloadManager,
    onDismiss: () -> Unit,
    onDownloadComplete: () -> Unit
) {
    val downloadState by downloadManager.downloadState.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(downloadState) {
        if (downloadState is DownloadState.Completed) {
            onDownloadComplete()
        }
    }

    Dialog(onDismissRequest = {
        if (downloadState !is DownloadState.Downloading) {
            onDismiss()
        }
    }) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF131B2A),
            tonalElevation = 8.dp,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Icon
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(Color(0xFF00E5FF).copy(alpha = 0.15f), shape = RoundedCornerShape(28.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (downloadState) {
                            is DownloadState.Completed -> Icons.Default.DownloadDone
                            is DownloadState.Error -> Icons.Default.ErrorOutline
                            else -> Icons.Default.CloudDownload
                        },
                        contentDescription = null,
                        tint = when (downloadState) {
                            is DownloadState.Error -> Color(0xFFFF5252)
                            is DownloadState.Completed -> Color(0xFF00E676)
                            else -> Color(0xFF00E5FF)
                        },
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Download Orion Core Brain",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Qwen2.5-1.5B-Instruct (INT4 GGUF, ~986 MB) runs locally on your Snapdragon 695 for private, offline agent execution.",
                    color = Color(0xFF94A3B8),
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )

                Spacer(modifier = Modifier.height(20.dp))

                when (val state = downloadState) {
                    is DownloadState.Idle -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = onDismiss) {
                                Text("Later", color = Color(0xFF94A3B8))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    scope.launch {
                                        downloadManager.downloadModel(
                                            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
                                            filename = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
                                            expectedSizeBytes = 986_000_000L
                                        )
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
                            ) {
                                Text("Download (~986 MB)", color = Color(0xFF0A0E17), fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    is DownloadState.Downloading -> {
                        val mbDownloaded = state.downloadedBytes / (1024 * 1024)
                        val mbTotal = state.totalBytes / (1024 * 1024)
                        val percentInt = (state.progressPercent * 100).toInt()

                        Column(modifier = Modifier.fillMaxWidth()) {
                            LinearProgressIndicator(
                                progress = { state.progressPercent },
                                modifier = Modifier.fillMaxWidth().height(8.dp),
                                color = Color(0xFF00E5FF),
                                trackColor = Color(0xFF1E293B)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "$mbDownloaded MB / $mbTotal MB",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = "$percentInt%",
                                    color = Color(0xFF00E5FF),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            TextButton(
                                onClick = { downloadManager.cancelDownload() },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("Cancel", color = Color(0xFFFF5252))
                            }
                        }
                    }

                    is DownloadState.Completed -> {
                        Text(
                            text = "Model installed! Orion is ready for offline reasoning.",
                            color = Color(0xFF00E676),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onDismiss,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))
                        ) {
                            Text("Start Using Orion", color = Color(0xFF0A0E17), fontWeight = FontWeight.Bold)
                        }
                    }

                    is DownloadState.Error -> {
                        Text(
                            text = "Error: ${state.message}",
                            color = Color(0xFFFF5252),
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = onDismiss) {
                                Text("Close", color = Color(0xFF94A3B8))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    scope.launch {
                                        downloadManager.downloadModel(
                                            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
                                            filename = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
                                            expectedSizeBytes = 986_000_000L
                                        )
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
                            ) {
                                Text("Retry", color = Color(0xFF0A0E17))
                            }
                        }
                    }
                }
            }
        }
    }
}
