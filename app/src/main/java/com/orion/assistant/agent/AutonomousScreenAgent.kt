package com.orion.assistant.agent

import android.content.Context
import android.content.res.Resources
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.orion.assistant.llm.LlamaCppRuntime
import com.orion.assistant.llm.ModelDecision
import com.orion.assistant.model.ActionCommand
import com.orion.assistant.model.ActionResult
import com.orion.assistant.model.ScrollDirection
import com.orion.assistant.model.SystemAction
import com.orion.assistant.service.accessibility.ActionExecutor
import com.orion.assistant.service.accessibility.OrionAccessibilityService
import com.orion.assistant.service.accessibility.ScreenSemanticParser
import com.orion.assistant.service.accessibility.SemanticElement
import kotlinx.coroutines.delay

/**
 * AutonomousScreenAgent: Dynamic closed-loop perceptual UI navigation agent.
 * 1. Homes the device to Page 1 and opens the app drawer.
 * 2. Uses the on-device LLM to perceive the active screen, choose the exact click/swipe,
 *    and achieve navigation goals autonomously.
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

    private val llamaRuntime = LlamaCppRuntime(context)

    /**
     * Navigates to the first page of the home screen, swipes up to open the app drawer,
     * searches for the target app by name, and taps it to open.
     */
    suspend fun openAppViaDrawer(appName: String): Boolean {
        Log.d(TAG, "Navigating to home screen first page to open app drawer for: $appName")

        // 1. Double tap HOME to ensure we are on the main default home page (Page 1)
        executor.execute(ActionCommand.SystemNav(SystemAction.HOME))
        delay(600L)
        executor.execute(ActionCommand.SystemNav(SystemAction.HOME))
        delay(800L)

        // 2. Perform upward swipe gesture from bottom-center to open the App Drawer
        val dm = Resources.getSystem().displayMetrics
        val width = dm.widthPixels.toFloat()
        val height = dm.heightPixels.toFloat()

        Log.d(TAG, "Swiping up from ($width/2, $height*0.85) to ($width/2, $height*0.25) for App Drawer")
        val swiped = performSwipeGesture(width / 2f, height * 0.85f, width / 2f, height * 0.25f, 300)
        delay(1000L)

        // 3. Look for the app search bar or app drawer elements
        val searched = typeIntoInputField(
            appName,
            listOf("Search apps", "Search", "Search your phone", "App search")
        )

        delay(800L)

        // 4. Click the matching app in the search results
        val clicked = clickSemanticTarget(listOf(appName), waitTimeoutMs = 2500L)
        if (clicked) {
            delay(1500L)
            return true
        }

        // Fallback: If not found in search results immediately, try direct package/label launch
        Log.d(TAG, "Drawer item not clicked directly, using Intent launcher fallback")
        val directRes = executor.execute(ActionCommand.OpenApp(appName))
        delay(1500L)
        return directRes.success
    }

    /**
     * Uses the local LLM to analyze the active screen hierarchy and execute the best next step
     * toward completing the specified sub-task.
     */
    suspend fun stepIntelligently(
        subGoal: String,
        onProgress: ((String) -> Unit)? = null
    ): ActionResult {
        // 1. Gather screen elements
        val screenDesc = executor.refreshScreenContext()
        val systemPrompt = """
            You are Orion navigating an Android screen.
            Active screen hierarchy:
            $screenDesc

            Goal: $subGoal

            Choose the exact next action to get closer to the goal.
            Respond ONLY with a JSON action:
            {"action": "click", "text": "<label>", "index": <optional_index>}
            {"action": "type", "text": "<string>"}
            {"action": "scroll", "direction": "up"|"down"|"left"|"right"}
            {"action": "respond", "message": "<done_or_status>"}
        """.trimIndent()

        onProgress?.invoke("Analyzing screen layout for '$subGoal'...")
        val decision = llamaRuntime.decideNextAction(systemPrompt, subGoal)
        Log.d(TAG, "LLM dynamic navigation decision: action=${decision.actionName}, thought=${decision.thought}")

        // 2. Execute decision
        val json = decision.rawJson
        return when (decision.actionName.lowercase()) {
            "click" -> {
                val index = json?.optInt("index", -1) ?: -1
                val text = json?.optString("text", null)
                val targetIndex = if (index >= 0) index else null
                executor.execute(ActionCommand.Click(targetIndex = targetIndex, targetText = text))
            }
            "type" -> {
                val text = json?.optString("text", "") ?: ""
                typeIntoInputField(text)
                ActionResult(true, "Type", "Typed $text")
            }
            "scroll" -> {
                val dir = when (json?.optString("direction")?.lowercase()) {
                    "up" -> ScrollDirection.UP
                    "left" -> ScrollDirection.LEFT
                    "right" -> ScrollDirection.RIGHT
                    else -> ScrollDirection.DOWN
                }
                scroll(dir)
                ActionResult(true, "Scroll", "Scrolled $dir")
            }
            else -> {
                ActionResult(true, "Step", decision.directMessage ?: "Analyzed screen.")
            }
        }
    }

    /**
     * Continuously senses the screen until a target element with the specified text is visible.
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
                        val res = executor.execute(ActionCommand.Click(targetIndex = match.index))
                        if (res.success) return true

                        val tapRes = executor.execute(ActionCommand.TapCoordinate(match.centerX, match.centerY))
                        if (tapRes.success) return true
                    }
                }
            }
            delay(350L)
        }

        for (label in candidateLabels) {
            val res = executor.execute(ActionCommand.Click(targetText = label))
            if (res.success) return true
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
            clickSemanticTarget(clickTargetCandidates, waitTimeoutMs = 1200L)
            delay(400L)
        }

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

    private suspend fun performSwipeGesture(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 300
    ): Boolean {
        val s = service ?: return false
        val path = android.graphics.Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
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
