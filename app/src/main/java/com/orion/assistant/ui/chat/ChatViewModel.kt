package com.orion.assistant.ui.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orion.assistant.agent.OrionAgent
import com.orion.assistant.service.accessibility.OrionAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: MessageSender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val imagePath: String? = null
)

enum class MessageSender {
    USER,
    ORION,
    SYSTEM
}

class ChatViewModel : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(
        listOf(
            ChatMessage(
                sender = MessageSender.ORION,
                text = "Hello! I am Orion. Enable my Accessibility Service to allow me to inspect screens, navigate apps, and notify you via calls."
            )
        )
    )
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    fun sendMessage(prompt: String, context: Context) {
        if (prompt.isBlank()) return

        val userMsg = ChatMessage(sender = MessageSender.USER, text = prompt)
        _messages.value = _messages.value + userMsg

        viewModelScope.launch {
            val progressMessageId = java.util.UUID.randomUUID().toString()

            try {
                val agent = OrionAgent(context)

                val result = agent.processCommand(prompt) { progressText ->
                    _messages.value = _messages.value.filter { it.id != progressMessageId } + ChatMessage(
                        id = progressMessageId,
                        sender = MessageSender.ORION,
                        text = "⚙️ $progressText"
                    )
                }

                val cleanDetails = sanitizeDisplayMessage(result.details)
                val responseText = if (result.success) {
                    if (result.actionName == "Orion") {
                        cleanDetails
                    } else {
                        "${result.actionName}: $cleanDetails"
                    }
                } else {
                    "Failed to execute [${result.actionName}]: $cleanDetails"
                }

                _messages.value = _messages.value.filter { it.id != progressMessageId } + ChatMessage(
                    sender = if (result.success) MessageSender.ORION else MessageSender.SYSTEM,
                    text = responseText,
                    imagePath = result.imagePath
                )
            } catch (t: Throwable) {
                _messages.value = _messages.value.filter { it.id != progressMessageId } + ChatMessage(
                    sender = MessageSender.SYSTEM,
                    text = "Error processing request: ${t.localizedMessage ?: t.message ?: "Unknown error"}"
                )
            }
        }
    }

    private fun sanitizeDisplayMessage(text: String): String {
        val trimmed = text.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            val regex = "\"message\"\\s*:\\s*\"(.*?)\"".toRegex(RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(trimmed)
            if (match != null) {
                return match.groupValues[1].replace("\\\"", "\"").replace("\\n", "\n")
            }
        }
        return trimmed
    }
}
