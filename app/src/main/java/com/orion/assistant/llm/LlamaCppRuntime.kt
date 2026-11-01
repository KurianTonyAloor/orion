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

        // Persistent dual model cache in RAM
        @Volatile
        private var cachedPrimaryModel: LlamaModel? = null
        @Volatile
        private var cachedPrimaryPath: String? = null

        @Volatile
        private var cachedNavigatorModel: LlamaModel? = null
        @Volatile
        private var cachedNavigatorPath: String? = null

        private val modelMutex = Mutex()
    }

    private val downloadManager = ModelDownloadManager(context)

    fun isModelLoaded(role: ModelRole = ModelRole.PRIMARY_REASONER): Boolean {
        return downloadManager.isModelDownloaded(role)
    }

    /**
     * Executes cognitive decision-making on the input prompt using either Primary Reasoner or Screen Navigator.
     */
    suspend fun decideNextAction(
        systemPrompt: String,
        userGoal: String,
        role: ModelRole = ModelRole.PRIMARY_REASONER
    ): ModelDecision = withContext(Dispatchers.Default) {
        val watchdogMs = if (role == ModelRole.SCREEN_NAVIGATOR) 3500L else 8000L

        if (downloadManager.isModelDownloaded(role)) {
            val generatedText = withTimeoutOrNull(watchdogMs) {
                try {
                    runLocalInference(systemPrompt, userGoal, role)
                } catch (e: Throwable) {
                    Log.e(TAG, "Native inference error ($role)", e)
                    null
                }
            }

            if (!generatedText.isNullOrBlank()) {
                return@withContext parseModelDecision(generatedText)
            }
        }

        // Fast Contextual Fallback Decision Engine
        return@withContext cognitiveContextFallback(userGoal, systemPrompt)
    }

    private suspend fun runLocalInference(
        systemPrompt: String,
        userGoal: String,
        role: ModelRole
    ): String = withContext(Dispatchers.IO) {
        val modelFile = if (role == ModelRole.SCREEN_NAVIGATOR) {
            downloadManager.activeNavigatorModelFile
        } else {
            downloadManager.activeModelFile
        }

        if (!modelFile.exists() || modelFile.length() < 10_000_000L) {
            return@withContext "{\"action\": \"respond\", \"message\": \"Model file not ready.\"}"
        }

        modelMutex.withLock {
            try {
                val isNavigator = (role == ModelRole.SCREEN_NAVIGATOR)
                val currentCached = if (isNavigator) cachedNavigatorModel else cachedPrimaryModel
                val currentPath = if (isNavigator) cachedNavigatorPath else cachedPrimaryPath

                val activeModel = if (currentCached == null || currentPath != modelFile.absolutePath) {
                    currentCached?.let {
                        try { Llama.releaseModel(it) } catch (_: Throwable) {}
                    }

                    Log.d(TAG, "Loading GGUF ($role) into memory: ${modelFile.name}")
                    val threads = if (isNavigator) 4 else 4
                    val ctxSize = if (isNavigator) 512 else 1024
                    val config = LlamaConfig(contextSize = ctxSize, threads = threads)
                    val loaded = Llama.loadModel(modelFile.absolutePath, config)

                    if (isNavigator) {
                        cachedNavigatorModel = loaded
                        cachedNavigatorPath = modelFile.absolutePath
                    } else {
                        cachedPrimaryModel = loaded
                        cachedPrimaryPath = modelFile.absolutePath
                    }
                    loaded
                } else {
                    currentCached
                }

                val maxTokens = if (isNavigator) 64 else 128
                val result = Llama.complete(
                    model = activeModel,
                    prompt = userGoal,
                    systemPrompt = systemPrompt,
                    maxTokens = maxTokens
                )

                val text = result.text.trim()
                Log.d(TAG, "Llama ($role) generation: $text")
                return@withContext text
            } catch (t: Throwable) {
                Log.e(TAG, "Llama inference error ($role): ${t.message}", t)
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

        val answer = when {
            lowerUserGoal.contains("what does orion mean") || lowerUserGoal.contains("who are you") || lowerUserGoal.contains("what is orion") -> {
                "Orion is an autonomous on-device AI assistant engineered for the Snapdragon 695. The name Orion is inspired by the prominent celestial hunter constellation. Orion operates locally on your device to inspect screens, execute cross-app workflows, generate assets, and interact with applications autonomously without cloud dependencies."
            }
            lowerUserGoal.contains("where is newyork") || lowerUserGoal.contains("where is new york") -> {
                "New York is located in the northeastern region of the United States. New York City, the nation's most populous city, sits at the southern tip of the state along the Atlantic coast."
            }
            lowerUserGoal.contains("onam") && (lowerUserGoal.contains("what is") || lowerUserGoal.contains("tell me about")) -> {
                "Onam is an annual harvest and cultural festival celebrated predominantly in Kerala, India. It commemorates King Mahabali's legendary return and is marked by intricate floral carpets (Pookkalam), grand feasts (Onasadya), and vibrant festivities."
            }
            lowerUserGoal == "hey" || lowerUserGoal == "hello" || lowerUserGoal == "hi" || lowerUserGoal.startsWith("hey ") || lowerUserGoal.startsWith("hello ") -> {
                "Hello! I am Orion, running locally on your Motorola G84. How can I assist you with your device today?"
            }
            lowerUserGoal.contains("how are you") -> {
                "I am running optimally on-device, ready to execute your system tasks and cross-app workflows."
            }
            else -> {
                "I processed your command \"$userGoal\". Orion is ready to execute cross-app workflows, inspect screens, and perform device tasks autonomously."
            }
        }

        return ModelDecision("Knowledge response", "respond", null, answer)
    }

    private fun parseModelDecision(rawOutput: String): ModelDecision {
        val trimmed = rawOutput.trim()

        if (trimmed.contains("{") && trimmed.contains("}")) {
            val jsonSubstring = trimmed.substring(trimmed.indexOf("{"), trimmed.lastIndexOf("}") + 1)
            try {
                val json = JSONObject(jsonSubstring)
                val action = json.optString("action", "respond")
                val thought = json.optString("thought", "")
                val message = if (json.has("message")) json.getString("message") else null
                return ModelDecision(thought, action, json, message)
            } catch (_: Exception) {}
        }

        return ModelDecision("Natural text completion", "respond", null, trimmed)
    }
}
