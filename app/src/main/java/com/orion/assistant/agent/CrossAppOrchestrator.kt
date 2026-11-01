package com.orion.assistant.agent

import android.content.Context
import android.util.Log
import com.orion.assistant.model.ActionCommand
import com.orion.assistant.model.ActionResult
import com.orion.assistant.service.accessibility.ActionExecutor
import com.orion.assistant.service.accessibility.OrionAccessibilityService
import kotlinx.coroutines.delay

data class ComplexTaskGoal(
    val recipientName: String?,
    val assetTopic: String,
    val outputFormat: String, // "pdf", "image", etc.
    val sourceContextApp: AppCapability = AppCapability.MESSAGING
)

class CrossAppOrchestrator(private val context: Context) {

    companion object {
        private const val TAG = "CrossAppOrchestrator"
    }

    private val capabilityRegistry = AppCapabilityRegistry(context)

    private fun getExecutor(): ActionExecutor? {
        val service = OrionAccessibilityService.currentInstance ?: return null
        return ActionExecutor(context, service)
    }

    /**
     * Detects if the prompt is an autonomous cross-app workflow request.
     */
    fun parseComplexGoal(prompt: String): ComplexTaskGoal? {
        val lower = prompt.lowercase()

        // Check for cross-app indicators: (texted/messaged me about X, make it and send as Y)
        val hasSender = lower.contains("texted me") || lower.contains("messaged me") || lower.contains("sent me")
        val hasDeliverable = lower.contains("send it") || lower.contains("send back") || lower.contains("pdf") || lower.contains("poster")

        if (hasSender || (hasDeliverable && (lower.contains("make") || lower.contains("create") || lower.contains("generate")))) {
            // Extract recipient
            val recipient = extractRecipient(lower)

            // Extract topic
            val topic = extractTopic(lower)

            // Extract format
            val format = if (lower.contains("pdf")) "pdf" else "image"

            return ComplexTaskGoal(
                recipientName = recipient,
                assetTopic = topic,
                outputFormat = format
            )
        }

        return null
    }

    private fun extractRecipient(lower: String): String? {
        val keywords = listOf("texted me", "messaged me", "send it to", "send back to", "send to")
        for (kw in keywords) {
            if (lower.contains(kw)) {
                val after = lower.substringAfter(kw).trim()
                val candidate = after.split(" ").firstOrNull()?.replace(",", "")?.replace(".", "")?.trim()
                if (!candidate.isNullOrBlank() && candidate != "about" && candidate != "that" && candidate != "the") {
                    return candidate.replaceFirstChar { it.uppercase() }
                }
            }
        }
        // Check pattern: "Sreehari texted me"
        if (lower.contains(" texted me")) {
            val before = lower.substringBefore(" texted me").trim()
            val candidate = before.split(" ").lastOrNull()?.trim()
            if (!candidate.isNullOrBlank()) {
                return candidate.replaceFirstChar { it.uppercase() }
            }
        }
        return null
    }

    private fun extractTopic(lower: String): String {
        return when {
            lower.contains("onam poster") -> "Onam festive celebration poster"
            lower.contains("poster") -> {
                val segment = lower.substringAfter("about").substringBefore("make").trim()
                if (segment.isNotBlank()) segment else "Celebration poster"
            }
            else -> "Digital art poster"
        }
    }

    /**
     * Executes the cross-app multi-stage workflow autonomously.
     */
    suspend fun executeCrossAppWorkflow(
        goal: ComplexTaskGoal,
        onProgress: (String) -> Unit
    ): ActionResult {
        val executor = getExecutor()
            ?: return ActionResult(false, "Orchestrator", "Accessibility Service is not enabled.")

        Log.d(TAG, "Starting workflow for goal: $goal")

        // -----------------------------------------------------------------------------------------
        // PHASE 1: Retrieve context from messaging app (e.g. WhatsApp)
        // -----------------------------------------------------------------------------------------
        val messagingApp = capabilityRegistry.findBestAppFor(AppCapability.MESSAGING)
        if (messagingApp != null && goal.recipientName != null) {
            onProgress("Phase 1/4: Checking ${goal.recipientName}'s message on ${messagingApp.appName}...")
            executor.execute(ActionCommand.OpenApp(messagingApp.appName))
            delay(2000L)

            // Search for recipient
            executor.execute(ActionCommand.Click(targetText = "Search"))
            delay(600L)
            executor.execute(ActionCommand.TypeText(goal.recipientName))
            delay(1000L)
            // Tap the matching conversation
            executor.execute(ActionCommand.Click(targetText = goal.recipientName))
            delay(1500L)
            executor.refreshScreenContext()
        }

        // -----------------------------------------------------------------------------------------
        // PHASE 2: Discover and execute AI Generation (e.g. Gemini or ChatGPT)
        // -----------------------------------------------------------------------------------------
        val aiApp = capabilityRegistry.findBestAppFor(AppCapability.AI_IMAGE_GENERATOR)
        val selectedAiName = aiApp?.appName ?: "Gemini"

        onProgress("Phase 2/4: Generating ${goal.assetTopic} using $selectedAiName...")
        executor.execute(ActionCommand.OpenApp(selectedAiName))
        delay(2500L)

        val generationPrompt = "Create a high quality vibrant ${goal.assetTopic} with traditional festive art, high resolution, ready for print."
        
        // Find input box and type prompt
        executor.execute(ActionCommand.Click(targetText = "Ask"))
        delay(500L)
        executor.execute(ActionCommand.TypeText(generationPrompt))
        delay(600L)
        executor.execute(ActionCommand.Click(targetText = "Send"))
        delay(4000L) // Wait for generation to start

        // -----------------------------------------------------------------------------------------
        // PHASE 3: Convert to PDF if required
        // -----------------------------------------------------------------------------------------
        if (goal.outputFormat == "pdf") {
            val converterApp = capabilityRegistry.findBestAppFor(AppCapability.IMAGE_TO_PDF_CONVERTER)
            val converterName = converterApp?.appName ?: "Image to PDF"

            onProgress("Phase 3/4: Converting generated poster into PDF format via $converterName...")
            executor.execute(ActionCommand.OpenApp(converterName))
            delay(2000L)

            // Standard workflow in image to pdf converters: select latest image -> convert
            executor.execute(ActionCommand.Click(targetText = "Select Images"))
            delay(1000L)
            // Tap first recent image
            executor.execute(ActionCommand.Click(targetIndex = 1))
            delay(800L)
            executor.execute(ActionCommand.Click(targetText = "Convert to PDF"))
            delay(2000L)
        }

        // -----------------------------------------------------------------------------------------
        // PHASE 4: Delivery back to Recipient via WhatsApp
        // -----------------------------------------------------------------------------------------
        if (messagingApp != null && goal.recipientName != null) {
            onProgress("Phase 4/4: Delivering final PDF to ${goal.recipientName} on ${messagingApp.appName}...")
            executor.execute(ActionCommand.OpenApp(messagingApp.appName))
            delay(1500L)

            // Attach document
            executor.execute(ActionCommand.Click(targetText = "Attach"))
            delay(800L)
            executor.execute(ActionCommand.Click(targetText = "Document"))
            delay(1000L)
            // Tap recent PDF
            executor.execute(ActionCommand.Click(targetIndex = 1))
            delay(800L)
            executor.execute(ActionCommand.Click(targetText = "Send"))
            delay(1000L)
        }

        val successMsg = "Successfully created ${goal.assetTopic} using $selectedAiName, converted to PDF, and sent to ${goal.recipientName ?: "contact"}!"
        onProgress(successMsg)
        return ActionResult(true, "Orion Workflow", successMsg)
    }
}
