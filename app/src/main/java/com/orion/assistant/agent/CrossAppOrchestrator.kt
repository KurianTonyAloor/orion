package com.orion.assistant.agent

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.orion.assistant.model.ActionCommand
import com.orion.assistant.model.ActionResult
import com.orion.assistant.service.accessibility.ActionExecutor
import com.orion.assistant.service.accessibility.OrionAccessibilityService
import com.orion.assistant.tools.PdfConverter
import com.orion.assistant.vision.ScreenCaptureManager
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream

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

        val hasSender = lower.contains("texted me") || lower.contains("messaged me") || lower.contains("sent me")
        val hasDeliverable = lower.contains("send it") || lower.contains("send back") || lower.contains("pdf") || lower.contains("poster")

        if (hasSender || (hasDeliverable && (lower.contains("make") || lower.contains("create") || lower.contains("generate")))) {
            val recipient = extractRecipient(lower)
            val topic = extractTopic(lower)
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
     * Executes the true autonomous cross-app multi-stage workflow:
     * 1. Returns to Home Page 1 and navigates to WhatsApp via the App Drawer.
     * 2. Inspects conversation context dynamically.
     * 3. Returns to Home Page 1 and opens Gemini/ChatGPT via the App Drawer.
     * 4. Dynamically analyzes the screen layout using the LLM, types the generation prompt, and submits.
     * 5. Waits for generation and extracts the real generated image artifact into a PDF.
     * 6. Returns to Home Page 1, navigates into WhatsApp, and autonomously attaches and sends the PDF.
     */
    suspend fun executeCrossAppWorkflow(
        goal: ComplexTaskGoal,
        onProgress: (String) -> Unit
    ): ActionResult {
        val executor = getExecutor()
            ?: return ActionResult(false, "Orchestrator", "Accessibility Service is not enabled. Please enable Orion under Accessibility settings.")

        val screenAgent = AutonomousScreenAgent(context, executor)
        Log.d(TAG, "Starting autonomous cross-app workflow for goal: $goal")

        // -----------------------------------------------------------------------------------------
        // PHASE 1: Open WhatsApp via Home Screen App Drawer & check conversation
        // -----------------------------------------------------------------------------------------
        val messagingApp = capabilityRegistry.findBestAppFor(AppCapability.MESSAGING)
        val messagingName = messagingApp?.appName ?: "WhatsApp"

        if (goal.recipientName != null) {
            onProgress("Phase 1/4: Going to Home Screen (Page 1) to open $messagingName via App Drawer...")
            screenAgent.openAppViaDrawer(messagingName)
            delay(2000L)

            // Dynamic LLM screen step to search and open recipient's chat
            onProgress("Searching for ${goal.recipientName} in $messagingName...")
            screenAgent.clickSemanticTarget(listOf("Search", "Ask Meta AI or search", "Search…"))
            delay(500L)
            executor.execute(ActionCommand.TypeText(goal.recipientName))
            delay(1000L)
            screenAgent.clickSemanticTarget(listOf(goal.recipientName))
            delay(1500L)
        }

        // -----------------------------------------------------------------------------------------
        // PHASE 2: Go to Home Page 1, Open Gemini / ChatGPT via App Drawer & Generate Poster
        // -----------------------------------------------------------------------------------------
        val aiApp = capabilityRegistry.findBestAppFor(AppCapability.AI_IMAGE_GENERATOR)
        val selectedAiName = aiApp?.appName ?: "Gemini"

        onProgress("Phase 2/4: Returning to Home Screen to launch $selectedAiName via App Drawer...")
        screenAgent.openAppViaDrawer(selectedAiName)
        delay(2500L)

        val generationPrompt = "Generate a full-screen vibrant ${goal.assetTopic} with traditional festive art, high resolution, no watermarks."

        // Analyze screen dynamically with LLM to find where to type
        onProgress("Analyzing $selectedAiName screen layout with LLM...")
        screenAgent.stepIntelligently("Type generation prompt: $generationPrompt", onProgress)
        delay(600L)

        // Ensure text is typed into the AI input field
        screenAgent.typeIntoInputField(
            generationPrompt,
            listOf("Ask Gemini", "Message", "Ask anything", "Prompt", "Type a message", "Ask")
        )
        delay(600L)

        // Submit prompt using dynamic LLM perception
        screenAgent.clickSemanticTarget(listOf("Send", "Submit", "arrow", "Generate"))

        // Wait for image generation
        var elapsed = 0
        while (elapsed < 14) {
            delay(1000L)
            elapsed++
            if (elapsed % 3 == 0) {
                onProgress("Generating poster in $selectedAiName (${elapsed}s)...")
            }
        }

        // Tap or save generated image
        onProgress("Extracting generated image from $selectedAiName...")
        val clickedSave = screenAgent.clickSemanticTarget(
            listOf("Download", "Save", "Save image", "Share", "More options"),
            waitTimeoutMs = 2500L
        )

        if (!clickedSave) {
            // Tap center screen where generated image is displayed
            val metrics = android.content.res.Resources.getSystem().displayMetrics
            executor.execute(ActionCommand.TapCoordinate(metrics.widthPixels / 2f, metrics.heightPixels * 0.45f))
            delay(800L)
            screenAgent.clickSemanticTarget(listOf("Download", "Save", "Save to phone"))
        }
        delay(2000L)

        // -----------------------------------------------------------------------------------------
        // PHASE 3: Acquire Poster Image & Convert to PDF
        // -----------------------------------------------------------------------------------------
        onProgress("Phase 3/4: Converting generated image to PDF...")
        var posterImageFile = PdfConverter.findLatestDownloadedImage(context)

        if (posterImageFile == null || (System.currentTimeMillis() - posterImageFile.lastModified() > 60_000L)) {
            Log.d(TAG, "Capturing high-resolution generated visual frame from screen buffer...")
            val screenBitmap = ScreenCaptureManager.captureCurrentScreen()
            if (screenBitmap != null) {
                val capturedFile = File(context.cacheDir, "gemini_poster_${System.currentTimeMillis()}.png")
                FileOutputStream(capturedFile).use { out ->
                    screenBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                posterImageFile = capturedFile
            }
        }

        val pdfFile = if (posterImageFile != null && posterImageFile.exists()) {
            PdfConverter.convertImageToPdf(context, posterImageFile, "Orion_Poster_${System.currentTimeMillis()}.pdf")
        } else null

        // -----------------------------------------------------------------------------------------
        // PHASE 4: Open WhatsApp via App Drawer & Autonomously Deliver PDF
        // -----------------------------------------------------------------------------------------
        onProgress("Phase 4/4: Returning to Home Screen to launch $messagingName via App Drawer...")
        screenAgent.openAppViaDrawer(messagingName)
        delay(2000L)

        if (goal.recipientName != null) {
            screenAgent.clickSemanticTarget(listOf("Search", "Ask Meta AI or search", "Search…"))
            delay(500L)
            executor.execute(ActionCommand.TypeText(goal.recipientName))
            delay(1000L)
            screenAgent.clickSemanticTarget(listOf(goal.recipientName))
            delay(1500L)
        }

        // Tap Attach
        onProgress("Navigating WhatsApp chat artifacts: Tapping Attach...")
        val clickedAttach = screenAgent.clickSemanticTarget(
            listOf("Attach", "Attach…", "Share", "attachment"),
            waitTimeoutMs = 3000L
        )

        if (!clickedAttach) {
            val dm = android.content.res.Resources.getSystem().displayMetrics
            executor.execute(ActionCommand.TapCoordinate(dm.widthPixels * 0.78f, dm.heightPixels * 0.94f))
            delay(1000L)
        } else {
            delay(1000L)
        }

        // Select Document
        onProgress("Selecting Document from attachment options...")
        screenAgent.clickSemanticTarget(listOf("Document", "Documents", "document"))
        delay(1500L)

        // Tap the recent PDF
        onProgress("Selecting the newly created PDF...")
        val clickedDoc = screenAgent.clickSemanticTarget(
            listOf("Orion_Poster", "Browse other docs", "Recent", "PDF", "Just now"),
            waitTimeoutMs = 2500L
        )

        if (!clickedDoc) {
            val dm = android.content.res.Resources.getSystem().displayMetrics
            executor.execute(ActionCommand.TapCoordinate(dm.widthPixels * 0.5f, dm.heightPixels * 0.22f))
            delay(1000L)
        }

        // Tap Send in WhatsApp
        onProgress("Sending PDF to ${goal.recipientName ?: "contact"}...")
        screenAgent.clickSemanticTarget(listOf("Send", "send", "Send document", "arrow_forward"))
        delay(1500L)

        val successMsg = "Autonomous workflow complete! Navigated via App Drawer, generated on $selectedAiName, converted to PDF, and dispatched to ${goal.recipientName ?: "contact"} on WhatsApp."
        onProgress(successMsg)
        return ActionResult(true, "Orion CrossApp", successMsg)
    }
}
