package com.orion.assistant.ui.model

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.orion.assistant.llm.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelHubSearchDialog(
    downloadManager: ModelDownloadManager,
    onDismiss: () -> Unit,
    onModelSelected: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val downloadState by downloadManager.downloadState.collectAsState()

    var searchQuery by remember { mutableStateOf("qwen 2.5 1.5b") }
    var isSearching by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<HuggingFaceModel>>(emptyList()) }
    var selectedRepoId by remember { mutableStateOf<String?>(null) }
    var repoVariants by remember { mutableStateOf<List<GgufVariant>>(emptyList()) }
    var isLoadingVariants by remember { mutableStateOf(false) }

    fun doSearch(query: String) {
        if (query.isBlank()) return
        isSearching = true
        selectedRepoId = null
        repoVariants = emptyList()
        scope.launch {
            searchResults = HuggingFaceApiClient.searchModels(query)
            isSearching = false
        }
    }

    LaunchedEffect(Unit) {
        doSearch(searchQuery)
    }

    Dialog(
        onDismissRequest = {
            if (downloadState !is DownloadState.Downloading) onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF0F172A),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            tint = Color(0xFF00E5FF),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Hugging Face Model Hub",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF94A3B8))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Search Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search GGUF models on Hugging Face...", color = Color(0xFF64748B), fontSize = 13.sp) },
                    trailingIcon = {
                        IconButton(onClick = { doSearch(searchQuery) }) {
                            Icon(Icons.Default.Search, contentDescription = "Search", tint = Color(0xFF00E5FF))
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00E5FF),
                        unfocusedBorderColor = Color(0xFF334155),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    shape = RoundedCornerShape(14.dp),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Recommended Presets Chips (Optimized for SD695)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("Qwen 2.5 1.5B", "SmolLM2 1.7B", "Llama 3.2 1B", "Phi 3.5").forEach { preset ->
                        FilterChip(
                            selected = searchQuery.equals(preset, ignoreCase = true),
                            onClick = {
                                searchQuery = preset
                                doSearch(preset)
                            },
                            label = { Text(preset, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF00E5FF).copy(alpha = 0.2f),
                                selectedLabelColor = Color(0xFF00E5FF)
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Active Download Status Card (if downloading)
                when (val state = downloadState) {
                    is DownloadState.Downloading -> {
                        Surface(
                            color = Color(0xFF1E293B),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = "Downloading: ${state.modelName}",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { state.progressPercent },
                                    modifier = Modifier.fillMaxWidth().height(6.dp),
                                    color = Color(0xFF00E5FF),
                                    trackColor = Color(0xFF334155)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    val mbDownloaded = state.downloadedBytes / (1024 * 1024)
                                    val mbTotal = state.totalBytes / (1024 * 1024)
                                    Text("$mbDownloaded MB / $mbTotal MB", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                    Text("${(state.progressPercent * 100).toInt()}%", color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                TextButton(
                                    onClick = { downloadManager.cancelDownload() },
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text("Cancel Download", color = Color(0xFFFF5252), fontSize = 11.sp)
                                }
                            }
                        }
                    }
                    is DownloadState.Completed -> {
                        Surface(
                            color = Color(0xFF132A20),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Active Model: ${state.modelName}", color = Color(0xFF00E676), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                TextButton(onClick = {
                                    onModelSelected(state.modelName)
                                    onDismiss()
                                }) {
                                    Text("Use Now", color = Color(0xFF00E5FF), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    is DownloadState.Error -> {
                        Surface(
                            color = Color(0xFF2A1515),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        ) {
                            Text("Error: ${state.message}", color = Color(0xFFFF5252), fontSize = 12.sp, modifier = Modifier.padding(12.dp))
                        }
                    }
                    else -> {}
                }

                // Results List
                if (isSearching) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFF00E5FF), modifier = Modifier.size(32.dp))
                    }
                } else if (searchResults.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("No GGUF models found for '$searchQuery'", color = Color(0xFF64748B), fontSize = 13.sp)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(searchResults) { model ->
                            val isExpanded = selectedRepoId == model.id

                            Surface(
                                color = Color(0xFF1E293B),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isExpanded) {
                                            selectedRepoId = null
                                            repoVariants = emptyList()
                                        } else {
                                            selectedRepoId = model.id
                                            isLoadingVariants = true
                                            scope.launch {
                                                repoVariants = HuggingFaceApiClient.fetchModelVariants(model.id)
                                                isLoadingVariants = false
                                            }
                                        }
                                    }
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = model.modelName,
                                                color = Color.White,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "by ${model.author}",
                                                color = Color(0xFF94A3B8),
                                                fontSize = 12.sp
                                            )
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Download, contentDescription = null, tint = Color(0xFF64748B), modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(2.dp))
                                            Text("${model.downloads}", color = Color(0xFF64748B), fontSize = 11.sp)
                                        }
                                    }

                                    // Expanded Quantization Variants
                                    AnimatedVisibility(visible = isExpanded) {
                                        Column(modifier = Modifier.padding(top = 12.dp)) {
                                            HorizontalDivider(color = Color(0xFF334155), thickness = 0.5.dp)
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text("Available Quantizations:", color = Color(0xFF00E5FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            Spacer(modifier = Modifier.height(6.dp))

                                            if (isLoadingVariants) {
                                                CircularProgressIndicator(color = Color(0xFF00E5FF), modifier = Modifier.size(24.dp).align(Alignment.CenterHorizontally))
                                            } else if (repoVariants.isEmpty()) {
                                                Text("No .gguf files found in this repository.", color = Color(0xFF64748B), fontSize = 12.sp)
                                            } else {
                                                repoVariants.forEach { variant ->
                                                    val sizeMb = variant.sizeBytes / (1024 * 1024)
                                                    val sizeStr = if (sizeMb > 1024) String.format("%.2f GB", sizeMb / 1024f) else "$sizeMb MB"

                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(vertical = 4.dp)
                                                            .clip(RoundedCornerShape(8.dp))
                                                            .background(Color(0xFF0F172A))
                                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(
                                                                text = variant.filename,
                                                                color = Color(0xFFE2E8F0),
                                                                fontSize = 12.sp,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                            Text(sizeStr, color = Color(0xFF94A3B8), fontSize = 11.sp)
                                                        }
                                                        Button(
                                                            onClick = {
                                                                scope.launch {
                                                                    downloadManager.downloadModel(
                                                                        downloadUrl = variant.downloadUrl,
                                                                        filename = variant.filename,
                                                                        expectedSizeBytes = variant.sizeBytes
                                                                    )
                                                                }
                                                            },
                                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                            modifier = Modifier.height(32.dp)
                                                        ) {
                                                            Text("Get", color = Color(0xFF0A0E17), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
