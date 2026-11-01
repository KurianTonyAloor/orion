package com.orion.assistant.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Resources
import android.graphics.Rect
import android.os.Bundle
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
 * 2. Uses robust multi-strategy text entry (ACTION_SET_TEXT, Paste, Physical Focus Tap).
 * 3. Uses on-device LLM to perceive the active screen, choose the exact click/swipe,
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
        performSwipeGesture(width / 2f, height * 0.85f, width / 2f, height * 0.25f, 300)
        delay(1000L)

        // 3. Search and click matching app
        val searched = typeIntoInputField(
            appName,
            listOf("Search apps", "Search", "Search your phone", "App search")
        )

        delay(800L)

        val clicked = clickSemanticTarget(listOf(appName), waitTimeoutMs = 2500L)
        if (clicked) {
            delay(1500L)
            return true
        }

        Log.d(TAG, "Drawer item not clicked directly, using Intent launcher fallback")
        val directRes = executor.execute(ActionCommand.OpenApp(appName))
        delay(1500L)
        return directRes.success
    }

    /**
     * Robust multi-strategy text input designed for complex apps like Gemini and WhatsApp.
     * Strategy 1: Find editable node, physically tap it to summon cursor and keyboard.
     * Strategy 2: Call ACTION_SET_TEXT directly on node and root focus.
     * Strategy 3: Copy text to system clipboard and trigger ACTION_PASTE.
     * Strategy 4: Fallback to coordinate tap near bottom of screen and type.
     */
    suspend fun typeIntoInputField(
        text: String,
        clickTargetCandidates: List<String> = listOf("Ask Gemini", "Type a message", "Message", "Ask", "Search", "Prompt")
    ): Boolean {
        Log.d(TAG, "Attempting to type text: '$text'")
        val root = service?.rootInActiveWindow

        // Step 1: Scan for editable element
        var targetElement: SemanticElement? = null
        if (root != null) {
            val (_, elements) = ScreenSemanticParser.parseActiveScreen(root)
            targetElement = elements.find { it.isEditable }
                ?: elements.find { el ->
                    clickTargetCandidates.any { candidate -> el.text.contains(candidate, ignoreCase = true) }
                }
        }

        // Tap the input box to focus it
        if (targetElement != null) {
            Log.d(TAG, "Tapping input element at (${targetElement.centerX}, ${targetElement.centerY})")
            executor.execute(ActionCommand.TapCoordinate(targetElement.centerX, targetElement.centerY))
            delay(400L)
        } else {
            // Coordinate tap fallback: In Gemini, input bar is at ~88% of screen height
            val dm = Resources.getSystem().displayMetrics
            executor.execute(ActionCommand.TapCoordinate(dm.widthPixels * 0.45f, dm.heightPixels * 0.88f))
            delay(400L)
        }

        // Step 2: Try ACTION_SET_TEXT on target node or focused node
        val freshRoot = service?.rootInActiveWindow
        val focusedNode = freshRoot?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: targetElement?.nodeRef

        if (focusedNode != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val setTextSuccess = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (setTextSuccess) {
                Log.d(TAG, "Successfully entered text via ACTION_SET_TEXT")
                return true
            }
        }

        // Step 3: Clipboard + ACTION_PASTE Fallback
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard != null) {
                val clip = ClipData.newPlainText("OrionInput", text)
                clipboard.setPrimaryClip(clip)

                val pasteTarget = freshRoot?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: focusedNode
                if (pasteTarget != null) {
                    val pasted = pasteTarget.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    if (pasted) {
                        Log.d(TAG, "Successfully entered text via ACTION_PASTE")
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Clipboard paste error: ${e.message}")
        }

        // Step 4: Generic executor fallback
        val typeResult = executor.execute(ActionCommand.TypeText(text))
        return typeResult.success
    }

    /**
     * Submits or sends the typed message in Gemini / WhatsApp / Chat apps.
     * Looks for "Send", "Submit", "arrow", or taps the send button position (bottom right).
     */
    suspend fun submitInput(): Boolean {
        delay(500L)
        val clicked = clickSemanticTarget(
            listOf("Send", "Submit", "arrow", "arrow_forward", "Send message", "Generate"),
            waitTimeoutMs = 1500L
        )

        if (clicked) return true

        // Physical tap fallback: Bottom right of screen where the send arrow is located in Gemini & WhatsApp
        val dm = Resources.getSystem().displayMetrics
        val sendX = dm.widthPixels * 0.92f
        val sendY = dm.heightPixels * 0.88f
        Log.d(TAG, "Tapping send coordinate fallback at ($sendX, $sendY)")
        val res = executor.execute(ActionCommand.TapCoordinate(sendX, sendY))
        return res.success
    }

    /**
     * Uses the local LLM to analyze the active screen hierarchy and execute the best next step
     * toward completing the specified sub-task.
     */
    suspend fun stepIntelligently(
        subGoal: String,
        onProgress: ((String) -> Unit)? = null
    ): ActionResult {
        val screenDesc = executor.refreshScreenContext()
        val systemPrompt = """
            You are Orion navigating an Android screen.
            Active screen hierarchy:
            $screenDesc

            Goal: $subGoal

            Choose the exact next action. Respond ONLY with a JSON action:
            {"action": "click", "text": "<label>", "index": <optional_index>}
            {"action": "type", "text": "<string>"}
            {"action": "scroll", "direction": "up"|"down"|"left"|"right"}
            {"action": "respond", "message": "<done_or_status>"}
        """.trimIndent()

        onProgress?.invoke("Analyzing screen layout for '$subGoal'...")
        val decision = llamaRuntime.decideNextAction(systemPrompt, subGoal)
        Log.d(TAG, "LLM decision: action=${decision.actionName}, thought=${decision.thought}")

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
                        Log.d(TAG, "Found target element '${match.text}' at index ${match.index} (${match.centerX}, ${match.centerY})")
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
