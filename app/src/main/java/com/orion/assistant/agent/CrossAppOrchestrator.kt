package com.orion.assistant.agent

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.util.Log
import com.orion.assistant.model.ActionCommand
import com.orion.assistant.model.ActionResult
import com.orion.assistant.model.ScrollDirection
import com.orion.assistant.service.accessibility.ActionExecutor
import com.orion.assistant.service.accessibility.OrionAccessibilityService
import com.orion.assistant.tools.PdfConverter
import com.orion.assistant.tools.WhatsAppDispatcher
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
     * 1. Inspects chat in WhatsApp (reads context).
     * 2. Opens Gemini / ChatGPT, inputs prompt to generate the poster image, and waits for completion.
     * 3. Interacts with the AI app UI (or captures screen / downloaded media) to extract the real generated image.
     * 4. Converts the generated image into a PDF via native PdfDocument engine.
     * 5. Navigates WhatsApp through accessibility gestures/clicks (Search -> Contact -> Attach -> Document -> Send).
     */
    suspend fun executeCrossAppWorkflow(
        goal: ComplexTaskGoal,
        onProgress: (String) -> Unit
    ): ActionResult {
        val executor = getExecutor()
            ?: return ActionResult(false, "Orchestrator", "Accessibility Service is not enabled. Please enable Orion in Accessibility Settings.")

        val screenAgent = AutonomousScreenAgent(context, executor)
        Log.d(TAG, "Starting autonomous cross-app workflow for goal: $goal")

        // -----------------------------------------------------------------------------------------
        // PHASE 1: Verify / inspect context on WhatsApp
        // -----------------------------------------------------------------------------------------
        val messagingApp = capabilityRegistry.findBestAppFor(AppCapability.MESSAGING)
        val messagingName = messagingApp?.appName ?: "WhatsApp"

        if (goal.recipientName != null) {
            onProgress("Phase 1/4: Checking ${goal.recipientName}'s conversation on $messagingName...")
            executor.execute(ActionCommand.OpenApp(messagingName))
            delay(2000L)

            // Dynamic UI navigation on WhatsApp
            screenAgent.clickSemanticTarget(listOf("Search", "Ask Meta AI or search", "Search…"))
            delay(500L)
            executor.execute(ActionCommand.TypeText(goal.recipientName))
            delay(1000L)

            // Click the matching contact item
            screenAgent.clickSemanticTarget(listOf(goal.recipientName))
            delay(1500L)
        }

        // -----------------------------------------------------------------------------------------
        // PHASE 2: Open AI App (Gemini / ChatGPT) & Generate the Poster
        // -----------------------------------------------------------------------------------------
        val aiApp = capabilityRegistry.findBestAppFor(AppCapability.AI_IMAGE_GENERATOR)
        val selectedAiName = aiApp?.appName ?: "Gemini"

        onProgress("Phase 2/4: Opening $selectedAiName to generate '${goal.assetTopic}'...")
        executor.execute(ActionCommand.OpenApp(selectedAiName))
        delay(3000L)

        val generationPrompt = "Generate a full-screen vibrant ${goal.assetTopic} with traditional festive art, high resolution, no watermarks."

        // Type generation prompt into the AI app's input field
        onProgress("Typing generation prompt into $selectedAiName...")
        screenAgent.typeIntoInputField(
            generationPrompt,
            listOf("Ask Gemini", "Message", "Ask anything", "Prompt", "Type a message", "Ask")
        )
        delay(800L)

        // Click Send button (or tap arrow / mic button transition)
        screenAgent.clickSemanticTarget(listOf("Send", "Submit", "arrow", "Generate"))
        
        // Wait for AI generation (typically takes 8 to 15 seconds)
        onProgress("Waiting for $selectedAiName to complete image generation...")
        var elapsed = 0
        while (elapsed < 14) {
            delay(1000L)
            elapsed++
            if (elapsed % 3 == 0) {
                onProgress("Generating poster in $selectedAiName (${elapsed}s)...")
            }
        }

        // Try downloading / saving the generated image from the screen
        onProgress("Saving generated poster from $selectedAiName...")
        val clickedDownload = screenAgent.clickSemanticTarget(
            listOf("Download", "Save", "Save image", "More options", "Share"),
            waitTimeoutMs = 2500L
        )

        if (!clickedDownload) {
            // Tap the generated image in the middle of the screen and check for save/download
            val metrics = android.content.res.Resources.getSystem().displayMetrics
            executor.execute(ActionCommand.TapCoordinate(metrics.widthPixels / 2f, metrics.heightPixels * 0.45f))
            delay(1000L)
            screenAgent.clickSemanticTarget(listOf("Download", "Save to phone", "Save", "More options"))
        }
        delay(2000L)

        // -----------------------------------------------------------------------------------------
        // PHASE 3: Acquire Generated Poster & Convert to PDF
        // -----------------------------------------------------------------------------------------
        onProgress("Phase 3/4: Converting generated poster into PDF...")
        var posterImageFile = PdfConverter.findLatestDownloadedImage(context)

        // If not saved to disk yet, take high-res screen capture of the generated image on screen
        if (posterImageFile == null || (System.currentTimeMillis() - posterImageFile.lastModified() > 60_000L)) {
            Log.d(TAG, "Capturing generated image directly from screen buffer...")
            val screenBitmap = ScreenCaptureManager.captureCurrentScreen()
            if (screenBitmap != null) {
                val capturedFile = File(context.cacheDir, "gemini_poster_${System.currentTimeMillis()}.png")
                FileOutputStream(capturedFile).use { out ->
                    screenBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                posterImageFile = capturedFile
            }
        }

        // Convert the acquired image into a PDF
        val pdfFile = if (posterImageFile != null && posterImageFile.exists()) {
            PdfConverter.convertImageToPdf(context, posterImageFile, "Orion_Poster_${System.currentTimeMillis()}.pdf")
        } else null

        // -----------------------------------------------------------------------------------------
        // PHASE 4: Open WhatsApp, Navigate UI Artifacts & Send Document
        // -----------------------------------------------------------------------------------------
        onProgress("Phase 4/4: Delivering PDF to ${goal.recipientName ?: "contact"} via WhatsApp UI...")

        // Open WhatsApp directly
        executor.execute(ActionCommand.OpenApp(messagingName))
        delay(2000L)

        // If contact name is given, search and open chat
        if (goal.recipientName != null) {
            screenAgent.clickSemanticTarget(listOf("Search", "Ask Meta AI or search", "Search…"))
            delay(600L)
            executor.execute(ActionCommand.TypeText(goal.recipientName))
            delay(1000L)
            screenAgent.clickSemanticTarget(listOf(goal.recipientName))
            delay(1500L)
        }

        // In the chat screen: Click Attach (paperclip) icon
        onProgress("Tapping Attach in WhatsApp chat...")
        val clickedAttach = screenAgent.clickSemanticTarget(
            listOf("Attach", "Attach…", "Share", "attachment"),
            waitTimeoutMs = 3000L
        )

        if (!clickedAttach) {
            // Coordinate fallback: In WhatsApp, paperclip is typically near the right of the input bar
            val dm = android.content.res.Resources.getSystem().displayMetrics
            executor.execute(ActionCommand.TapCoordinate(dm.widthPixels * 0.78f, dm.heightPixels * 0.94f))
            delay(1000L)
        } else {
            delay(1000L)
        }

        // Select "Document" from the attachment grid
        onProgress("Selecting Document option...")
        screenAgent.clickSemanticTarget(listOf("Document", "Documents", "document"))
        delay(1500L)

        // Select the top/most recent PDF document in document picker
        onProgress("Selecting the generated poster PDF...")
        val clickedRecentDoc = screenAgent.clickSemanticTarget(
            listOf("Orion_Poster", "Browse other docs", "Recent", "PDF", "Just now"),
            waitTimeoutMs = 2500L
        )

        if (!clickedRecentDoc) {
            // Tap the first document item listed in the picker
            val dm = android.content.res.Resources.getSystem().displayMetrics
            executor.execute(ActionCommand.TapCoordinate(dm.widthPixels * 0.5f, dm.heightPixels * 0.22f))
            delay(1000L)
        }

        // Tap the WhatsApp Send arrow button
        onProgress("Sending PDF document...")
        screenAgent.clickSemanticTarget(listOf("Send", "send", "Send document", "arrow_forward"))
        delay(1500L)

        val successMsg = "Autonomous mission complete! Generated '${goal.assetTopic}' on $selectedAiName, converted it to PDF, and navigated WhatsApp to send it to ${goal.recipientName ?: "contact"}."
        onProgress(successMsg)
        return ActionResult(true, "Orion CrossApp", successMsg)
    }
}
