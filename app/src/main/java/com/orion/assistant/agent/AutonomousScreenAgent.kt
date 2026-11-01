package com.orion.assistant.agent

import android.content.Context
import android.util.Log
import com.orion.assistant.model.ActionCommand
import com.orion.assistant.model.ActionResult
import com.orion.assistant.service.accessibility.ActionExecutor
import com.orion.assistant.service.accessibility.OrionAccessibilityService
import com.orion.assistant.service.accessibility.ScreenSemanticParser
import com.orion.assistant.service.accessibility.SemanticElement
import kotlinx.coroutines.delay

/**
 * AutonomousScreenAgent: Dynamic closed-loop perceptual UI navigation agent.
 * Instead of brittle blind clicks, this agent continuously reads the active screen
 * hierarchy (Perceive), matches visual targets/context (Think), and triggers actions (Act).
 */
class AutonomousScreenAgent(
    private val context: Context,
    private val executor: ActionExecutor
) {
    companion object {
        private const val TAG = "AutonomousScreenAgent"
        private const val MAX_RETRIES = 5
    }

    /**
     * Continuously senses the screen until a target element with the specified text or content description is visible.
     */
    suspend fun waitForElement(
        matchingText: String,
        timeoutMs: Long = 6000L,
        intervalMs: Long = 500L
    ): SemanticElement? {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val root = OrionAccessibilityService.currentInstance?.rootInActiveWindow
            if (root != null) {
                val (_, elements) = ScreenSemanticParser.parseActiveScreen(root)
                val match = elements.find { el ->
                    el.text.contains(matchingText, ignoreCase = true)
                }
                if (match != null) {
                    return match
                }
            }
            delay(intervalMs)
        }
        return null
    }

    /**
     * Clicks an element by semantic intent, waiting for it if necessary.
     */
    suspend fun clickSemanticTarget(
        candidateLabels: List<String>,
        waitTimeoutMs: Long = 4000L
    ): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < waitTimeoutMs) {
            val root = OrionAccessibilityService.currentInstance?.rootInActiveWindow
            if (root != null) {
                val (_, elements) = ScreenSemanticParser.parseActiveScreen(root)
                for (label in candidateLabels) {
                    val match = elements.find { el ->
                        el.isClickable && el.text.contains(label, ignoreCase = true)
                    }
                    if (match != null) {
                        Log.d(TAG, "Found target clickable element: ${match.text} at index ${match.index}")
                        val res = executor.execute(ActionCommand.Click(targetIndex = match.index))
                        if (res.success) return true
                    }
                }
            }
            delay(400L)
        }

        // Fallback: try direct text click via executor
        for (label in candidateLabels) {
            val res = executor.execute(ActionCommand.Click(targetText = label))
            if (res.success) return true
        }
        return false
    }

    /**
     * Types into the currently focused or first available editable field.
     */
    suspend fun typeIntoInputField(
        text: String,
        clickTargetCandidates: List<String> = listOf("Type", "Ask", "Search", "Message", "Prompt")
    ): Boolean {
        // Try focusing candidate field first
        clickSemanticTarget(clickTargetCandidates, waitTimeoutMs = 1500L)
        delay(300L)

        // Type directly into active focus
        val typeResult = executor.execute(ActionCommand.TypeText(text))
        return typeResult.success
    }
}
