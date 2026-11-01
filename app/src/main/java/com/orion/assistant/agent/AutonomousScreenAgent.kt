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
import com.orion.assistant.llm.ModelRole
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
 * Driven by the high-speed SCREEN_NAVIGATOR model for real-time perceive-think-act loops.
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

        executor.execute(ActionCommand.SystemNav(SystemAction.HOME))
        delay(600L)
        executor.execute(ActionCommand.SystemNav(SystemAction.HOME))
        delay(800L)

        val dm = Resources.getSystem().displayMetrics
        val width = dm.widthPixels.toFloat()
        val height = dm.heightPixels.toFloat()

        Log.d(TAG, "Swiping up from ($width/2, $height*0.85) to ($width/2, $height*0.25) for App Drawer")
        performSwipeGesture(width / 2f, height * 0.85f, width / 2f, height * 0.25f, 300)
        delay(1000L)

        typeIntoInputField(
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
     * Perceptive WhatsApp Chat Opener:
     * Robustly searches and opens a specific conversation thread on WhatsApp.
     */
    suspend fun openWhatsAppChat(contactName: String): Boolean {
        Log.d(TAG, "Opening WhatsApp chat for contact: '$contactName'")

        // 1. First check if the contact is already visible in the active chat list
        val immediatelyVisible = clickSemanticTarget(listOf(contactName), waitTimeoutMs = 1200L)
        if (immediatelyVisible) {
            Log.d(TAG, "Contact was immediately visible in active chat list")
            delay(1200L)
            return true
        }

        // 2. Locate and tap Search
        // In modern WhatsApp: Top search bar or search icon ("Ask Meta AI or search", "Search", or search icon)
        val clickedSearch = clickSemanticTarget(
            listOf("Search", "Ask Meta AI or search", "Search…", "search"),
            waitTimeoutMs = 2000L
        )

        if (!clickedSearch) {
            // Tap top-right area where WhatsApp search icon sits
            val dm = Resources.getSystem().displayMetrics
            executor.execute(ActionCommand.TapCoordinate(dm.widthPixels * 0.72f, dm.heightPixels * 0.08f))
            delay(600L)
        } else {
            delay(600L)
        }

        // 3. Type the exact contact name into the search field
        typeIntoInputField(contactName, listOf("Search", "Search…", "Type a name"))
        delay(1200L)

        // 4. Select the matching conversation from the search results
        // Matches exact contact name, or "Chats" search result section item
        val clickedContact = clickSemanticTarget(
            listOf(contactName),
            waitTimeoutMs = 3000L
        )

        if (clickedContact) {
            Log.d(TAG, "Successfully opened chat for $contactName from search results")
            delay(1500L)
            return true
        }

        // 5. Fallback: In WhatsApp search results, the first matching chat row sits below the search header (~22% height)
        val dm = Resources.getSystem().displayMetrics
        Log.d(TAG, "Tapping first search result coordinate fallback")
        executor.execute(ActionCommand.TapCoordinate(dm.widthPixels * 0.45f, dm.heightPixels * 0.22f))
        delay(1500L)
        return true
    }

    /**
     * Autonomous Perception Loop: Continuously parses the active screen and asks the
     * Screen Navigator model to evaluate the next physical touch, swipe, or input action.
     */
    suspend fun executeAutonomousStepLoop(
        subGoal: String,
        maxSteps: Int = 4,
        onProgress: ((String) -> Unit)? = null
    ): ActionResult {
        var stepCount = 0

        while (stepCount < maxSteps) {
            stepCount++
            val screenDesc = executor.refreshScreenContext()

            val navigatorPrompt = """
                You are a real-time Android screen navigator.
                Given the ACTIVE SCREEN UI ELEMENTS and the goal, output ONLY the next action.

                === ACTIVE SCREEN UI ELEMENTS ===
                $screenDesc

                GOAL: $subGoal

                Respond ONLY with a JSON object:
                {"thought": "<reason>", "action": "click", "index": <element_number>}
                {"thought": "<reason>", "action": "click", "text": "<exact_button_text>"}
                {"thought": "<reason>", "action": "type", "text": "<text_to_type>"}
                {"thought": "<reason>", "action": "scroll", "direction": "up"|"down"}
                {"thought": "<reason>", "action": "done", "message": "<completion_summary>"}
            """.trimIndent()

            onProgress?.invoke("Navigating ($stepCount/$maxSteps): Sensing screen layout...")
            val decision = llamaRuntime.decideNextAction(
                systemPrompt = navigatorPrompt,
                userGoal = subGoal,
                role = ModelRole.SCREEN_NAVIGATOR
            )

            Log.d(TAG, "Navigator step $stepCount decision: ${decision.actionName} - ${decision.thought}")
            val json = decision.rawJson

            when (decision.actionName.lowercase()) {
                "click" -> {
                    val index = json?.optInt("index", -1) ?: -1
                    val text = json?.optString("text", null)
                    val targetIndex = if (index >= 0) index else null
                    executor.execute(ActionCommand.Click(targetIndex = targetIndex, targetText = text))
                    delay(800L)
                }

                "type" -> {
                    val text = json?.optString("text", "") ?: ""
                    typeIntoInputField(text)
                    delay(800L)
                }

                "scroll" -> {
                    val dir = if (json?.optString("direction") == "up") ScrollDirection.UP else ScrollDirection.DOWN
                    scroll(dir)
                    delay(800L)
                }

                "done" -> {
                    return ActionResult(true, "Navigator", decision.directMessage ?: "Sub-task completed.")
                }

                else -> {
                    delay(500L)
                }
            }
        }

        return ActionResult(true, "Navigator", "Completed screen actions for '$subGoal'")
    }

    /**
     * Robust multi-strategy text input designed for complex apps like Gemini and WhatsApp.
     */
    suspend fun typeIntoInputField(
        text: String,
        clickTargetCandidates: List<String> = listOf("Ask Gemini", "Type a message", "Message", "Ask", "Search", "Prompt")
    ): Boolean {
        Log.d(TAG, "Attempting to type text: '$text'")
        val root = service?.rootInActiveWindow

        var targetElement: SemanticElement? = null
        if (root != null) {
            val (_, elements) = ScreenSemanticParser.parseActiveScreen(root)
            targetElement = elements.find { it.isEditable }
                ?: elements.find { el ->
                    clickTargetCandidates.any { candidate -> el.text.contains(candidate, ignoreCase = true) }
                }
        }

        if (targetElement != null) {
            Log.d(TAG, "Tapping input element at (${targetElement.centerX}, ${targetElement.centerY})")
            executor.execute(ActionCommand.TapCoordinate(targetElement.centerX, targetElement.centerY))
            delay(400L)
        } else {
            val dm = Resources.getSystem().displayMetrics
            executor.execute(ActionCommand.TapCoordinate(dm.widthPixels * 0.45f, dm.heightPixels * 0.88f))
            delay(400L)
        }

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

        val typeResult = executor.execute(ActionCommand.TypeText(text))
        return typeResult.success
    }

    suspend fun submitInput(): Boolean {
        delay(500L)
        val clicked = clickSemanticTarget(
            listOf("Send", "Submit", "arrow", "arrow_forward", "Send message", "Generate"),
            waitTimeoutMs = 1500L
        )

        if (clicked) return true

        val dm = Resources.getSystem().displayMetrics
        val sendX = dm.widthPixels * 0.92f
        val sendY = dm.heightPixels * 0.88f
        val res = executor.execute(ActionCommand.TapCoordinate(sendX, sendY))
        return res.success
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
