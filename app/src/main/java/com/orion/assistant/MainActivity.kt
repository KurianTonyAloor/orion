package com.orion.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.orion.assistant.llm.ModelDownloadManager
import com.orion.assistant.service.call.CallManager
import com.orion.assistant.ui.chat.ChatScreen
import com.orion.assistant.ui.chat.ChatViewModel
import com.orion.assistant.ui.model.ModelHubSearchDialog

class MainActivity : ComponentActivity() {

    private val chatViewModel: ChatViewModel by viewModels()
    private lateinit var downloadManager: ModelDownloadManager

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        downloadManager = ModelDownloadManager(this)
        requestRequiredPermissions()

        setContent {
            var showHubDialog by remember {
                mutableStateOf(!downloadManager.isModelDownloaded())
            }

            ChatScreen(
                viewModel = chatViewModel,
                isModelReady = downloadManager.isModelDownloaded(),
                onDownloadModelClick = { showHubDialog = true },
                onCallRequest = {
                    CallManager.triggerIncomingCall(
                        context = this,
                        callerName = "Orion AI",
                        reason = "Manual voice call test"
                    )
                }
            )

            if (showHubDialog) {
                ModelHubSearchDialog(
                    downloadManager = downloadManager,
                    onDismiss = { showHubDialog = false },
                    onModelSelected = { _ ->
                        showHubDialog = false
                    }
                )
            }
        }
    }

    private fun requestRequiredPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val ungranted = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (ungranted.isNotEmpty()) {
            permissionLauncher.launch(ungranted.toTypedArray())
        }
    }
}
