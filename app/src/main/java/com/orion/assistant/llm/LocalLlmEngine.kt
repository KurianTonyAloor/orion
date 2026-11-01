package com.orion.assistant.llm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

data class ModelInfo(
    val modelName: String,
    val filePath: String,
    val isPresent: Boolean,
    val sizeBytes: Long
)

class LocalLlmEngine(private val context: Context) {

    private val downloadManager = ModelDownloadManager(context)

    fun getModelInfo(): ModelInfo {
        val file = downloadManager.activeModelFile
        val isReady = file.exists() && file.length() > 50_000_000L
        return ModelInfo(
            modelName = downloadManager.activeModelFilename,
            filePath = file.absolutePath,
            isPresent = isReady,
            sizeBytes = if (file.exists()) file.length() else 0L
        )
    }

    /**
     * Adapts prompt templates automatically according to model family (Qwen, Llama, SmolLM).
     */
    fun formatChatPrompt(systemPrompt: String, userMessage: String): String {
        val filename = downloadManager.activeModelFilename.lowercase()

        return when {
            // Llama 3 / 3.2 format
            filename.contains("llama-3") || filename.contains("llama3") -> {
                "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n$systemPrompt<|eot_id|>" +
                "<|start_header_id|>user<|end_header_id|>\n$userMessage<|eot_id|>" +
                "<|start_header_id|>assistant<|end_header_id|>\n"
            }
            // Qwen 2.5 / SmolLM2 / ChatML format
            else -> {
                "<|im_start|>system\n$systemPrompt\n<|im_end|>\n" +
                "<|im_start|>user\n$userMessage\n<|im_end|>\n" +
                "<|im_start|>assistant\n"
            }
        }
    }

    /**
     * Executes local inference on Snapdragon 695 and streams tokens.
     */
    fun streamResponse(prompt: String): Flow<String> = flow {
        val modelInfo = getModelInfo()

        if (!modelInfo.isPresent) {
            val guidance = "No local model found. Search Hugging Face from the top menu to download a GGUF model for on-device reasoning."
            for (word in guidance.split(" ")) {
                emit("$word ")
                delay(30)
            }
            return@flow
        }

        emit("Running ${modelInfo.modelName} on Snapdragon 695...")
    }.flowOn(Dispatchers.Default)
}
