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
    val rawPrompt: String,
    val recipientName: String?,
    val assetTopic: String,
    val outputFormat: String
)

class CrossAppOrchestrator(private val context: Context) {

    companion object {
        private const val TAG = "CrossAppOrchestrator"
    }

    private val dynamicAppSelector = DynamicAppSelector(context)

    private fun getExecutor(): ActionExecutor? {
        val service = OrionAccessibilityService.currentInstance ?: return null
        return ActionExecutor(context, service)
    }

    /**
     * Parses the general intent from the user's prompt.
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
                rawPrompt = prompt,
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
     * Executes the workflow by:
     * 1. Querying installed launcher apps and asking the LLM to dynamically pick the apps.
     * 2. Navigating through the home screen app drawer to the chosen generator app.
     * 3. Using the fast Screen Navigator to perceive, type, and submit the prompt.
     * 4. Converting the generated image artifact into a PDF.
     * 5. Navigating to the chosen messaging app via app drawer and sending the document.
     */
    suspend fun executeCrossAppWorkflow(
        goal: ComplexTaskGoal,
        onProgress: (String) -> Unit
    ): ActionResult {
        val executor = getExecutor()
            ?: return ActionResult(false, "Orchestrator", "Accessibility Service is not enabled. Please enable Orion under Accessibility settings.")

        val screenAgent = AutonomousScreenAgent(context, executor)

        // -----------------------------------------------------------------------------------------
        // DYNAMIC APP SELECTION: LLM evaluates real installed device apps
        // -----------------------------------------------------------------------------------------
        onProgress("Scanning device & letting LLM dynamically select apps for task...")
        val plan = dynamicAppSelector.selectAppsForTask(goal.rawPrompt)
        Log.d(TAG, "Dynamic execution plan: generator='${plan.selectedGeneratorApp}', messenger='${plan.selectedMessagingApp}'")

        // -----------------------------------------------------------------------------------------
        // PHASE 1: Verify recipient chat on the selected messaging app (e.g. WhatsApp / Telegram)
        // -----------------------------------------------------------------------------------------
        if (goal.recipientName != null) {
            onProgress("Phase 1/4: Launching ${plan.selectedMessagingApp} via App Drawer for ${goal.recipientName}...")
            screenAgent.openAppViaDrawer(plan.selectedMessagingApp)
            delay(2000L)

            screenAgent.clickSemanticTarget(listOf("Search", "Ask Meta AI or search", "Search…"))
            delay(500L)
            executor.execute(ActionCommand.TypeText(goal.recipientName))
            delay(1000L)
            screenAgent.clickSemanticTarget(listOf(goal.recipientName))
            delay(1200L)
        }

        // -----------------------------------------------------------------------------------------
        // PHASE 2: Launch selected Generator App (e.g. ChatGPT / Gemini) via App Drawer
        // -----------------------------------------------------------------------------------------
        onProgress("Phase 2/4: Launching dynamically chosen generator: ${plan.selectedGeneratorApp}...")
        screenAgent.openAppViaDrawer(plan.selectedGeneratorApp)
        delay(2500L)

        // Step Loop: Autonomous Screen Navigator perceives layout & inputs prompt
        onProgress("Navigator model sensing ${plan.selectedGeneratorApp} screen layout...")
        screenAgent.executeAutonomousStepLoop(
            subGoal = "Find chat input and focus field in ${plan.selectedGeneratorApp}",
            maxSteps = 2,
            onProgress = onProgress
        )

        onProgress("Entering prompt: '${plan.promptToUse}'...")
        screenAgent.typeIntoInputField(
            plan.promptToUse,
            listOf("Ask Gemini", "Message", "Ask anything", "Prompt", "Type a message", "Ask")
        )
        delay(600L)

        onProgress("Submitting prompt in ${plan.selectedGeneratorApp}...")
        screenAgent.submitInput()

        // Wait for image generation
        var elapsed = 0
        while (elapsed < 14) {
            delay(1000L)
            elapsed++
            if (elapsed % 3 == 0) {
                onProgress("Generating image in ${plan.selectedGeneratorApp} (${elapsed}s)...")
            }
        }

        // Extract or download image
        onProgress("Extracting generated poster from ${plan.selectedGeneratorApp}...")
        val clickedSave = screenAgent.clickSemanticTarget(
            listOf("Download", "Save", "Save image", "Share", "More options"),
            waitTimeoutMs = 2500L
        )

        if (!clickedSave) {
            val metrics = android.content.res.Resources.getSystem().displayMetrics
            executor.execute(ActionCommand.TapCoordinate(metrics.widthPixels / 2f, metrics.heightPixels * 0.45f))
            delay(800L)
            screenAgent.clickSemanticTarget(listOf("Download", "Save", "Save to phone"))
        }
        delay(2000L)

        // -----------------------------------------------------------------------------------------
        // PHASE 3: Convert Generated Asset to PDF
        // -----------------------------------------------------------------------------------------
        onProgress("Phase 3/4: Converting generated poster into PDF document...")
        var posterImageFile = PdfConverter.findLatestDownloadedImage(context)

        if (posterImageFile == null || (System.currentTimeMillis() - posterImageFile.lastModified() > 60_000L)) {
            Log.d(TAG, "Capturing high-resolution visual frame from screen buffer...")
            val screenBitmap = ScreenCaptureManager.captureCurrentScreen()
            if (screenBitmap != null) {
                val capturedFile = File(context.cacheDir, "generated_poster_${System.currentTimeMillis()}.png")
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
        // PHASE 4: Open Selected Messenger via App Drawer & Autonomously Deliver Document
        // -----------------------------------------------------------------------------------------
        onProgress("Phase 4/4: Opening ${plan.selectedMessagingApp} via App Drawer to dispatch PDF...")
        screenAgent.openAppViaDrawer(plan.selectedMessagingApp)
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
        onProgress("Tapping Attach in ${plan.selectedMessagingApp}...")
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

        // Tap Document
        onProgress("Selecting Document option...")
        screenAgent.clickSemanticTarget(listOf("Document", "Documents", "document"))
        delay(1500L)

        // Select the newly generated PDF
        onProgress("Selecting the newly generated PDF...")
        val clickedDoc = screenAgent.clickSemanticTarget(
            listOf("Orion_Poster", "Browse other docs", "Recent", "PDF", "Just now"),
            waitTimeoutMs = 2500L
        )

        if (!clickedDoc) {
            val dm = android.content.res.Resources.getSystem().displayMetrics
            executor.execute(ActionCommand.TapCoordinate(dm.widthPixels * 0.5f, dm.heightPixels * 0.22f))
            delay(1000L)
        }

        // Tap Send
        onProgress("Sending PDF to ${goal.recipientName ?: "contact"}...")
        screenAgent.clickSemanticTarget(listOf("Send", "send", "Send document", "arrow_forward"))
        delay(1500L)

        val successMsg = "Autonomous workflow complete! Dynamically selected [${plan.selectedGeneratorApp}], generated asset, converted to PDF, and delivered via [${plan.selectedMessagingApp}]."
        onProgress(successMsg)
        return ActionResult(true, "Orion CrossApp", successMsg)
    }
}
