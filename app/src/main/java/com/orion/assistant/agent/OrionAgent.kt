package com.orion.assistant.agent

import android.content.Context
import com.orion.assistant.llm.LlamaCppRuntime
import com.orion.assistant.model.ActionCommand
import com.orion.assistant.model.ActionResult
import com.orion.assistant.model.ScrollDirection
import com.orion.assistant.model.SystemAction
import com.orion.assistant.service.accessibility.ActionExecutor
import com.orion.assistant.service.accessibility.OrionAccessibilityService
import com.orion.assistant.service.call.CallManager
import org.json.JSONObject

class OrionAgent(
    private val context: Context
) {

    private val llamaRuntime = LlamaCppRuntime(context)

    private fun getExecutor(): ActionExecutor? {
        val service = OrionAccessibilityService.currentInstance ?: return null
        return ActionExecutor(context, service)
    }

    /**
     * Hybrid decision and execution pipeline:
     * 1. Evaluates if the request is a deterministic device command (Fast-path).
     * 2. If cognitive reasoning / context analysis is needed, passes screen & camera context
     *    to the on-device LLM model to decide which action script to run.
     */
    suspend fun processCommand(input: String, onProgress: ((String) -> Unit)? = null): ActionResult {
        val trimmed = input.trim()
        val lower = trimmed.lowercase()

        // =========================================================================
        // FAST-PATH: Deterministic Device Commands (Immediate execution, 0ms latency)
        // =========================================================================
        val executor = getExecutor()

        if (lower.startsWith("open ") && !lower.contains(" and ")) {
            val appName = trimmed.substring(5).trim()
            return executor?.execute(ActionCommand.OpenApp(appName))
                ?: ActionResult(false, "OpenApp", "Accessibility service inactive.")
        }

        if (lower == "scroll down") return executor?.execute(ActionCommand.Scroll(ScrollDirection.DOWN)) ?: serviceNotActive()
        if (lower == "scroll up") return executor?.execute(ActionCommand.Scroll(ScrollDirection.UP)) ?: serviceNotActive()
        if (lower == "scroll left") return executor?.execute(ActionCommand.Scroll(ScrollDirection.LEFT)) ?: serviceNotActive()
        if (lower == "scroll right") return executor?.execute(ActionCommand.Scroll(ScrollDirection.RIGHT)) ?: serviceNotActive()
        if (lower == "go home" || lower == "home") return executor?.execute(ActionCommand.SystemNav(SystemAction.HOME)) ?: serviceNotActive()
        if (lower == "go back" || lower == "back") return executor?.execute(ActionCommand.SystemNav(SystemAction.BACK)) ?: serviceNotActive()
        if (lower == "recents" || lower == "recent apps") return executor?.execute(ActionCommand.SystemNav(SystemAction.RECENTS)) ?: serviceNotActive()
        if (lower == "notifications") return executor?.execute(ActionCommand.SystemNav(SystemAction.NOTIFICATIONS)) ?: serviceNotActive()

        if (lower.contains("call me") || lower == "call user") {
            CallManager.triggerIncomingCall(context, callerName = "Orion AI", reason = "Voice consultation requested")
            return ActionResult(true, "CallUser", "Triggered incoming voice call")
        }

        // Multi-step goal detection (e.g. "Open YouTube and search for Lo-Fi")
        if (lower.contains(" and ") || (lower.startsWith("search ") && lower.contains(" on "))) {
            val planner = TaskPlanner(context)
            val steps = planner.planGoal(trimmed)
            if (steps.size > 1) {
                return planner.executePlan(steps) { current, total, desc ->
                    val status = "Step $current/$total: $desc"
                    onProgress?.invoke(status)
                    com.orion.assistant.service.overlay.OrionFloatingService.updateStatus(status)
                }
            }
        }

        // =========================================================================
        // COGNITIVE LLM PATH: Model Analyzes Screen, Context & Selects Action Script
        // =========================================================================
        onProgress?.invoke("Analyzing screen & context with local model...")

        // 1. Gather live screen and device context
        val isExplicitVisualRequest = lower == "screenshot" || lower.contains("take a screenshot") || lower.contains("camera")
        val contextSnapshot = ContextualAgentPrompt.gatherFullContext(
            context = context,
            executor = executor,
            includeVisualSnapshot = isExplicitVisualRequest
        )

        // 2. Build system instructions
        val systemPrompt = ContextualAgentPrompt.buildSystemPrompt(contextSnapshot)

        // 3. Request decision from the on-device model
        val decision = llamaRuntime.decideNextAction(systemPrompt, trimmed)

        // 4. Execute the model's chosen action script
        return executeModelDecision(decision, executor)
    }

    private suspend fun executeModelDecision(
        decision: com.orion.assistant.llm.ModelDecision,
        executor: ActionExecutor?
    ): ActionResult {
        return try {
            val action = decision.actionName.lowercase()
            val json = decision.rawJson

            when (action) {
                "click" -> {
                    val index = json?.optInt("index", -1) ?: -1
                    val text = json?.optString("text", null)
                    val targetIndex = if (index >= 0) index else null
                    executor?.execute(ActionCommand.Click(targetIndex = targetIndex, targetText = text))
                        ?: serviceNotActive()
                }

                "type" -> {
                    val text = json?.optString("text", "") ?: ""
                    val index = if (json?.has("index") == true) json.getInt("index") else null
                    executor?.execute(ActionCommand.TypeText(text = text, targetIndex = index))
                        ?: serviceNotActive()
                }

                "scroll" -> {
                    val dir = when (json?.optString("direction")?.lowercase()) {
                        "up" -> ScrollDirection.UP
                        "left" -> ScrollDirection.LEFT
                        "right" -> ScrollDirection.RIGHT
                        else -> ScrollDirection.DOWN
                    }
                    executor?.execute(ActionCommand.Scroll(dir)) ?: serviceNotActive()
                }

                "open_app" -> {
                    val app = json?.optString("name", "") ?: ""
                    executor?.execute(ActionCommand.OpenApp(app)) ?: serviceNotActive()
                }

                "system" -> {
                    val nav = when (json?.optString("nav")?.lowercase()) {
                        "back" -> SystemAction.BACK
                        "recents" -> SystemAction.RECENTS
                        "notifications" -> SystemAction.NOTIFICATIONS
                        "quick_settings" -> SystemAction.QUICK_SETTINGS
                        "lock" -> SystemAction.LOCK
                        else -> SystemAction.HOME
                    }
                    executor?.execute(ActionCommand.SystemNav(nav)) ?: serviceNotActive()
                }

                "capture_screen" -> {
                    executor?.execute(ActionCommand.CaptureScreenSnapshot) ?: serviceNotActive()
                }

                "capture_camera" -> {
                    executor?.execute(ActionCommand.CaptureCameraPhoto) ?: serviceNotActive()
                }

                "call_user" -> {
                    val reason = json?.optString("reason", "Autonomous decision notification") ?: "Alert"
                    CallManager.triggerIncomingCall(context, callerName = "Orion AI", reason = reason)
                    ActionResult(true, "CallUser", "Initiating voice call: $reason")
                }

                // Direct response / reasoning from model
                "respond" -> {
                    val message = decision.directMessage ?: "Processed request."
                    ActionResult(true, "Orion", message)
                }

                else -> {
                    ActionResult(true, "Orion", decision.directMessage ?: "Action completed.")
                }
            }
        } catch (e: Exception) {
            ActionResult(false, "DecisionExecution", "Error executing model decision: ${e.message}")
        }
    }

    private fun serviceNotActive(): ActionResult {
        return ActionResult(false, "System", "Accessibility Service is not enabled. Please enable Orion under Accessibility settings.")
    }
}
