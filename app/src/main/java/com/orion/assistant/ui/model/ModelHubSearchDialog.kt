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
    initialRole: ModelRole = ModelRole.PRIMARY_REASONER,
    onDismiss: () -> Unit,
    onModelSelected: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val downloadState by downloadManager.downloadState.collectAsState()

    var selectedRole by remember { mutableStateOf(initialRole) }
    var searchQuery by remember {
        mutableStateOf(if (initialRole == ModelRole.SCREEN_NAVIGATOR) "qwen 2.5 0.5b" else "qwen 2.5 1.5b")
    }
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

    LaunchedEffect(selectedRole) {
        val query = if (selectedRole == ModelRole.SCREEN_NAVIGATOR) "qwen 2.5 0.5b" else "qwen 2.5 1.5b"
        searchQuery = query
        doSearch(query)
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
                .fillMaxHeight(0.88f),
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

                Spacer(modifier = Modifier.height(10.dp))

                // Role Selector Tabs (Primary Reasoner vs Screen Navigator)
                TabRow(
                    selectedTabIndex = if (selectedRole == ModelRole.PRIMARY_REASONER) 0 else 1,
                    containerColor = Color(0xFF1E293B),
                    contentColor = Color(0xFF00E5FF)
                ) {
                    Tab(
                        selected = selectedRole == ModelRole.PRIMARY_REASONER,
                        onClick = { selectedRole = ModelRole.PRIMARY_REASONER },
                        text = {
                            Text(
                                "🧠 Primary (1.5B - 3B)",
                                fontSize = 12.sp,
                                fontWeight = if (selectedRole == ModelRole.PRIMARY_REASONER) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                    Tab(
                        selected = selectedRole == ModelRole.SCREEN_NAVIGATOR,
                        onClick = { selectedRole = ModelRole.SCREEN_NAVIGATOR },
                        text = {
                            Text(
                                "⚡ Navigator (0.5B Fast)",
                                fontSize = 12.sp,
                                fontWeight = if (selectedRole == ModelRole.SCREEN_NAVIGATOR) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Search Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            if (selectedRole == ModelRole.SCREEN_NAVIGATOR) "Search fast 0.5B navigator models..." else "Search 1.5B/3B reasoning models...",
                            color = Color(0xFF64748B),
                            fontSize = 13.sp
                        )
                    },
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

                // Presets
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val presets = if (selectedRole == ModelRole.SCREEN_NAVIGATOR) {
                        listOf("Qwen 2.5 0.5B", "SmolLM2 360M", "Qwen 2 0.5B")
                    } else {
                        listOf("Qwen 2.5 1.5B", "SmolLM2 1.7B", "Llama 3.2 1B")
                    }

                    presets.forEach { preset ->
                        FilterChip(
                            selected = searchQuery.equals(preset, ignoreCase = true),
                            onClick = {
                                searchQuery = preset
                                doSearch(preset)
                            },
                            label = { Text(preset, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF00E5FF).copy(alpha = 0.2f),
                                selectedLabelColor = Color(0xFF00E5FF),
                                containerColor = Color(0xFF1E293B),
                                labelColor = Color(0xFF94A3B8)
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Live Download Progress Bar
                AnimatedVisibility(visible = downloadState is DownloadState.Downloading) {
                    val current = downloadState as? DownloadState.Downloading
                    if (current != null) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF1E293B)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Downloading [${selectedRole.name}]: ${current.modelName}",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "${(current.progressPercent * 100).toInt()}%",
                                        color = Color(0xFF00E5FF),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { current.progressPercent },
                                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                    color = Color(0xFF00E5FF),
                                    trackColor = Color(0xFF334155)
                                )
                            }
                        }
                    }
                }

                // Search Results
                if (isSearching) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFF00E5FF))
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(searchResults) { model ->
                            val isSelected = selectedRepoId == model.id

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isSelected) {
                                            selectedRepoId = null
                                            repoVariants = emptyList()
                                        } else {
                                            selectedRepoId = model.id
                                            isLoadingVariants = true
                                            scope.launch {
                                                repoVariants = HuggingFaceApiClient.fetchGgufVariants(model.id)
                                                isLoadingVariants = false
                                            }
                                        }
                                    },
                                shape = RoundedCornerShape(14.dp),
                                color = if (isSelected) Color(0xFF1E293B) else Color(0xFF162032)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = model.id,
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "Downloads: ${model.downloads} • Likes: ${model.likes}",
                                                color = Color(0xFF64748B),
                                                fontSize = 11.sp
                                            )
                                        }
                                        Icon(
                                            imageVector = if (isSelected) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            contentDescription = null,
                                            tint = Color(0xFF00E5FF)
                                        )
                                    }

                                    // Quantized Variants List
                                    AnimatedVisibility(visible = isSelected) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 10.dp)
                                        ) {
                                            if (isLoadingVariants) {
                                                CircularProgressIndicator(
                                                    color = Color(0xFF00E5FF),
                                                    modifier = Modifier.size(24.dp).align(Alignment.CenterHorizontally)
                                                )
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
                                                                        expectedSizeBytes = variant.sizeBytes,
                                                                        role = selectedRole
                                                                    )
                                                                }
                                                            },
                                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                            modifier = Modifier.height(32.dp)
                                                        ) {
                                                            Text("Assign as $selectedRole", color = Color(0xFF0A0E17), fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
