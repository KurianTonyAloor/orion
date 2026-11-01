package com.orion.assistant.agent

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.orion.assistant.model.ActionCommand
import com.orion.assistant.model.ActionResult
import com.orion.assistant.model.ScrollDirection
import com.orion.assistant.service.accessibility.ActionExecutor
import com.orion.assistant.service.accessibility.OrionAccessibilityService
import com.orion.assistant.service.accessibility.ScreenSemanticParser
import com.orion.assistant.service.accessibility.SemanticElement
import kotlinx.coroutines.delay

/**
 * AutonomousScreenAgent: Dynamic closed-loop perceptual UI navigation agent.
 * Senses the live screen hierarchy (Perceive), determines target element or coordinate (Think),
 * and executes clicks, gestures, swipes, or text entry (Act).
 */
class AutonomousScreenAgent(
    private val context: Context,
    private val executor: ActionExecutor
) {
    companion object {
        private const val TAG = "AutonomousScreenAgent"
    }

    private val service: OrionAccessibilityService?
        get() = OrionAccessibilityService.currentInstance

    /**
     * Continuously senses the screen until a target element with the specified text or content description is visible.
     */
    suspend fun waitForElement(
        matchingText: String,
        timeoutMs: Long = 6000L,
        intervalMs: Long = 400L
    ): SemanticElement? {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val root = service?.rootInActiveWindow
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
     * Clicks an element by semantic intent, waiting for it and using direct coordinates if accessibility click is unhandled.
     */
    suspend fun clickSemanticTarget(
        candidateLabels: List<String>,
        waitTimeoutMs: Long = 4000L
    ): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < waitTimeoutMs) {
            val root = service?.rootInActiveWindow
            if (root != null) {
                val (_, elements) = ScreenSemanticParser.parseActiveScreen(root)
                for (label in candidateLabels) {
                    val match = elements.find { el ->
                        el.text.contains(label, ignoreCase = true)
                    }
                    if (match != null) {
                        Log.d(TAG, "Found target element '${match.text}' (clickable=${match.isClickable}) at index ${match.index} (${match.centerX}, ${match.centerY})")
                        // If marked clickable, try accessibility click first
                        val res = executor.execute(ActionCommand.Click(targetIndex = match.index))
                        if (res.success) return true

                        // Direct physical tap fallback
                        val tapRes = executor.execute(ActionCommand.TapCoordinate(match.centerX, match.centerY))
                        if (tapRes.success) return true
                    }
                }
            }
            delay(350L)
        }

        // Secondary fallback: Try click by text
        for (label in candidateLabels) {
            val res = executor.execute(ActionCommand.Click(targetText = label))
            if (res.success) return true
        }
        return false
    }

    /**
     * Finds and clicks an element based on class or role (e.g. an ImageView, Download button, or share icon).
     */
    suspend fun clickFirstMatching(
        predicate: (SemanticElement) -> Boolean,
        waitTimeoutMs: Long = 3000L
    ): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < waitTimeoutMs) {
            val root = service?.rootInActiveWindow
            if (root != null) {
                val (_, elements) = ScreenSemanticParser.parseActiveScreen(root)
                val match = elements.firstOrNull(predicate)
                if (match != null) {
                    Log.d(TAG, "Matching element found: ${match.type} '${match.text}' at (${match.centerX}, ${match.centerY})")
                    val res = executor.execute(ActionCommand.TapCoordinate(match.centerX, match.centerY))
                    if (res.success) return true
                }
            }
            delay(300L)
        }
        return false
    }

    /**
     * Types into an input field. First taps the candidate area or first editable field, then enters text.
     */
    suspend fun typeIntoInputField(
        text: String,
        clickTargetCandidates: List<String> = listOf("Type", "Ask", "Search", "Message", "Prompt")
    ): Boolean {
        val root = service?.rootInActiveWindow
        var targetNode: AccessibilityNodeInfo? = null

        // 1. Locate editable node on screen
        if (root != null) {
            val (_, elements) = ScreenSemanticParser.parseActiveScreen(root)
            val editable = elements.find { it.isEditable }
            if (editable != null) {
                executor.execute(ActionCommand.TapCoordinate(editable.centerX, editable.centerY))
                delay(300L)
                targetNode = editable.nodeRef
            }
        }

        if (targetNode == null) {
            // Tap any candidate label to bring up focus & keyboard
            clickSemanticTarget(clickTargetCandidates, waitTimeoutMs = 1200L)
            delay(400L)
        }

        // Type directly into active focus
        val typeResult = executor.execute(ActionCommand.TypeText(text))
        return typeResult.success
    }

    /**
     * Performs a physical scroll/swipe on the active screen.
     */
    suspend fun scroll(direction: ScrollDirection): Boolean {
        val res = executor.execute(ActionCommand.Scroll(direction))
        delay(600L)
        return res.success
    }

    /**
     * Long-presses an element (e.g. to save image or open context menu).
     */
    suspend fun longPressCoordinate(x: Float, y: Float, durationMs: Long = 1000L): Boolean {
        val s = service ?: return false
        val path = android.graphics.Path().apply { moveTo(x, y) }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()

        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            s.dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    continuation.resume(true) {}
                }
                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    continuation.resume(false) {}
                }
            }, null)
        }
    }
}
