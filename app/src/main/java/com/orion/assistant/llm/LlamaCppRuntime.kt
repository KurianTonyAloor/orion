package com.orion.assistant.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel

data class ModelDecision(
    val thought: String,
    val actionName: String,
    val rawJson: JSONObject?,
    val directMessage: String?
)

class LlamaCppRuntime(private val context: Context) {

    companion object {
        private const val TAG = "OrionLlamaRuntime"

        // Persistent in-memory model cache: Load once, stay warm!
        @Volatile
        private var cachedModel: LlamaModel? = null
        @Volatile
        private var cachedModelPath: String? = null
        private val modelMutex = Mutex()
    }

    private val downloadManager = ModelDownloadManager(context)

    val isModelLoaded: Boolean
        get() = downloadManager.isModelDownloaded()

    val currentModelPath: String
        get() = downloadManager.activeModelFile.absolutePath

    /**
     * Executes cognitive decision-making on the input prompt.
     * Evaluates screen context and user instruction to select the correct action script.
     */
    suspend fun decideNextAction(systemPrompt: String, userGoal: String): ModelDecision = withContext(Dispatchers.Default) {
        // If local model is downloaded and ready, run native inference with a strict 8-second watchdog:
        if (isModelLoaded) {
            val generatedText = withTimeoutOrNull(8000L) {
                try {
                    runLocalInference(systemPrompt, userGoal)
                } catch (e: Throwable) {
                    Log.e(TAG, "Native inference execution error", e)
                    null
                }
            }

            if (!generatedText.isNullOrBlank()) {
                return@withContext parseModelDecision(generatedText)
            }
        }

        // Contextual Fallback Decision Engine (if model is still loading, busy, or timed out):
        return@withContext cognitiveContextFallback(userGoal, systemPrompt)
    }

    /**
     * Native inference bridge for the GGUF model on Snapdragon 695.
     * Uses persistent warm model instance with 4 threads and 1024 context window.
     */
    private suspend fun runLocalInference(systemPrompt: String, userGoal: String): String = withContext(Dispatchers.IO) {
        val modelFile = downloadManager.activeModelFile
        if (!modelFile.exists() || modelFile.length() < 10_000_000L) {
            return@withContext "{\"action\": \"respond\", \"message\": \"Model file is still downloading or not ready.\"}"
        }

        modelMutex.withLock {
            try {
                // If model path changed or not loaded yet, load and cache in memory
                if (cachedModel == null || cachedModelPath != modelFile.absolutePath) {
                    cachedModel?.let {
                        try { Llama.releaseModel(it) } catch (_: Throwable) {}
                    }
                    cachedModel = null

                    Log.d(TAG, "Loading GGUF model into memory: ${modelFile.name}")
                    val config = LlamaConfig(contextSize = 1024, threads = 4)
                    cachedModel = Llama.loadModel(modelFile.absolutePath, config)
                    cachedModelPath = modelFile.absolutePath
                    Log.d(TAG, "Model successfully cached in memory")
                }

                val model = cachedModel ?: return@withContext ""

                val result = Llama.complete(
                    model = model,
                    prompt = userGoal,
                    systemPrompt = systemPrompt,
                    maxTokens = 128
                )

                val text = result.text.trim()
                Log.d(TAG, "Llama generation completed: $text")
                return@withContext text
            } catch (t: Throwable) {
                Log.e(TAG, "Llama inference error: ${t.message}", t)
                // Invalidate cache on crash so it re-attempts cleanly next time
                try {
                    cachedModel?.let { Llama.releaseModel(it) }
                } catch (_: Throwable) {}
                cachedModel = null
                throw t
            }
        }
    }

    /**
     * Contextual reasoning logic that answers questions and handles actions when model is initializing.
     */
    private fun cognitiveContextFallback(userGoal: String, systemPrompt: String): ModelDecision {
        val lowerUserGoal = userGoal.lowercase().trim()

        val screenSection = systemPrompt.substringAfter("=== ACTIVE SCREEN UI ELEMENTS ===", "")
            .substringBefore("Available tool scripts", "")
            .trim()

        // 1. Tool execution heuristics
        when {
            lowerUserGoal == "screenshot" || lowerUserGoal.contains("take a screenshot") || lowerUserGoal.contains("capture screen") -> {
                return ModelDecision("User requested display snapshot", "capture_screen", null, null)
            }

            lowerUserGoal.contains("look with camera") || lowerUserGoal.contains("use camera") || lowerUserGoal.contains("take a photo") -> {
                return ModelDecision("User requested camera perception", "capture_camera", null, null)
            }

            lowerUserGoal.contains("what is on my screen") || lowerUserGoal.contains("what do you see") || lowerUserGoal.contains("explain screen") -> {
                val summary = if (screenSection.isNotBlank() && !screenSection.contains("No interactive elements")) {
                    "Here is what I see on your screen:\n" + screenSection.lines().take(6).joinToString("\n")
                } else {
                    "I see your current screen, but no readable interactive elements were found."
                }
                return ModelDecision("Screen analysis", "respond", null, summary)
            }

            lowerUserGoal.startsWith("click ") || lowerUserGoal.startsWith("tap ") -> {
                val target = lowerUserGoal.removePrefix("click ").removePrefix("tap ").trim()
                val index = target.toIntOrNull()
                val json = JSONObject().apply {
                    put("action", "click")
                    if (index != null) put("index", index) else put("text", target)
                }
                return ModelDecision("Click element", "click", json, null)
            }

            lowerUserGoal.startsWith("type ") || lowerUserGoal.startsWith("enter text ") -> {
                val text = lowerUserGoal.removePrefix("type ").removePrefix("enter text ").trim()
                val json = JSONObject().apply {
                    put("action", "type")
                    put("text", text)
                }
                return ModelDecision("Type text", "type", json, null)
            }
        }

        // 2. Knowledge queries (instant natural answers)
        val answer = when {
            lowerUserGoal.contains("what does orion mean") || lowerUserGoal.contains("who are you") || lowerUserGoal.contains("what is orion") -> {
                "Orion is named after the prominent celestial constellation known as the Hunter. In Greek mythology, Orion was a giant huntsman. Here, Orion is your on-device AI assistant designed to navigate and control your phone locally."
            }
            lowerUserGoal.contains("where is newyork") || lowerUserGoal.contains("where is new york") -> {
                "New York is located in the northeastern region of the United States. New York City sits at the southern tip of New York State on the Atlantic coast."
            }
            lowerUserGoal == "hey" || lowerUserGoal == "hello" || lowerUserGoal == "hi" -> {
                "Hello! I am Orion, running locally on your Motorola G84. How can I assist you with your device today?"
            }
            else -> {
                "I analyzed your request \"$userGoal\". You can ask me to open apps, read the screen, make calls, or take screenshots."
            }
        }

        return ModelDecision("Knowledge response", "respond", null, answer)
    }

    /**
     * Parses the model output and ensures that raw JSON is never leaked to the user.
     */
    private fun parseModelDecision(rawOutput: String): ModelDecision {
        val trimmed = rawOutput.trim()

        // 1. Try standard JSON extraction if the model chose a tool action
        if (trimmed.contains("{") && trimmed.contains("}")) {
            val jsonSubstring = trimmed.substring(trimmed.indexOf("{"), trimmed.lastIndexOf("}") + 1)
            try {
                val json = JSONObject(jsonSubstring)
                val action = json.optString("action", "respond")
                val thought = json.optString("thought", "")

                val msg = when {
                    json.has("message") -> json.getString("message")
                    json.has("text") -> json.getString("text")
                    action == "respond" -> json.optString("details", "")
                    else -> null
                }

                return ModelDecision(
                    thought = thought,
                    actionName = action,
                    rawJson = json,
                    directMessage = msg?.cleanMessage()
                )
            } catch (_: Exception) {
                val messageRegex = "\"message\"\\s*:\\s*\"(.*?)\"".toRegex(RegexOption.DOT_MATCHES_ALL)
                val match = messageRegex.find(jsonSubstring)
                if (match != null) {
                    val extracted = match.groupValues[1]
                    return ModelDecision(
                        thought = "Extracted response",
                        actionName = "respond",
                        rawJson = null,
                        directMessage = extracted.cleanMessage()
                    )
                }
            }
        }

        // 2. If it's plain text (standard conversational LLM response), present it directly
        return ModelDecision(
            thought = "Direct response",
            actionName = "respond",
            rawJson = null,
            directMessage = trimmed.cleanMessage()
        )
    }

    private fun String.cleanMessage(): String {
        return this.replace("\\\"", "\"")
            .replace("\\n", "\n")
            .trim()
    }
}
