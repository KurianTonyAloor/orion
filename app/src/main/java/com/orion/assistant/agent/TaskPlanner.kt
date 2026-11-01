package com.orion.assistant.agent

import android.content.Context
import com.orion.assistant.model.ActionCommand
import com.orion.assistant.model.ActionResult
import com.orion.assistant.model.ScrollDirection
import com.orion.assistant.model.SystemAction
import com.orion.assistant.service.accessibility.ActionExecutor
import com.orion.assistant.service.accessibility.OrionAccessibilityService
import kotlinx.coroutines.delay

data class TaskPlanStep(
    val description: String,
    val command: ActionCommand,
    val postDelayMs: Long = 800L
)

class TaskPlanner(private val context: Context) {

    private fun getExecutor(): ActionExecutor? {
        val service = OrionAccessibilityService.currentInstance ?: return null
        return ActionExecutor(context, service)
    }

    /**
     * Decomposes complex user goals into an ordered sequence of executable steps.
     */
    fun planGoal(goal: String): List<TaskPlanStep> {
        val lower = goal.lowercase().trim()
        val steps = mutableListOf<TaskPlanStep>()

        when {
            // Goal: "Open [App] and search for [Query]"
            lower.contains(" and search for ") || (lower.startsWith("open ") && lower.contains(" search ")) -> {
                val parts = if (lower.contains(" and search for ")) {
                    lower.split(" and search for ")
                } else {
                    lower.split(" search ")
                }

                val appRaw = parts[0].removePrefix("open").trim()
                val searchQuery = parts.getOrNull(1)?.trim() ?: ""

                steps.add(TaskPlanStep("Open $appRaw", ActionCommand.OpenApp(appRaw), 1500L))
                steps.add(TaskPlanStep("Inspect $appRaw screen", ActionCommand.InspectScreen, 500L))
                steps.add(TaskPlanStep("Click Search bar", ActionCommand.Click(targetText = "Search"), 600L))
                if (searchQuery.isNotBlank()) {
                    steps.add(TaskPlanStep("Type query '$searchQuery'", ActionCommand.TypeText(searchQuery), 600L))
                }
            }

            // Goal: "Search [Query] on [App]"
            lower.startsWith("search ") && lower.contains(" on ") -> {
                val withoutSearch = lower.removePrefix("search ").trim()
                val parts = withoutSearch.split(" on ")
                val query = parts[0].trim()
                val app = parts.getOrNull(1)?.trim() ?: "google"

                steps.add(TaskPlanStep("Open $app", ActionCommand.OpenApp(app), 1500L))
                steps.add(TaskPlanStep("Inspect $app screen", ActionCommand.InspectScreen, 500L))
                steps.add(TaskPlanStep("Click Search", ActionCommand.Click(targetText = "Search"), 600L))
                steps.add(TaskPlanStep("Type '$query'", ActionCommand.TypeText(query), 600L))
            }

            // Goal: "Open [App] and scroll down"
            lower.startsWith("open ") && lower.contains(" and scroll") -> {
                val app = lower.substringAfter("open ").substringBefore(" and scroll").trim()
                val direction = if (lower.contains("scroll up")) ScrollDirection.UP else ScrollDirection.DOWN
                steps.add(TaskPlanStep("Open $app", ActionCommand.OpenApp(app), 1500L))
                steps.add(TaskPlanStep("Scroll $direction in $app", ActionCommand.Scroll(direction), 500L))
            }

            // Single action fallbacks
            lower.startsWith("open ") -> {
                val app = goal.substring(5).trim()
                steps.add(TaskPlanStep("Open $app", ActionCommand.OpenApp(app), 1000L))
            }

            lower == "scroll down" -> steps.add(TaskPlanStep("Scroll Down", ActionCommand.Scroll(ScrollDirection.DOWN)))
            lower == "scroll up" -> steps.add(TaskPlanStep("Scroll Up", ActionCommand.Scroll(ScrollDirection.UP)))
            lower == "go home" || lower == "home" -> steps.add(TaskPlanStep("Go Home", ActionCommand.SystemNav(SystemAction.HOME)))
            lower == "go back" || lower == "back" -> steps.add(TaskPlanStep("Go Back", ActionCommand.SystemNav(SystemAction.BACK)))
            lower == "notifications" -> steps.add(TaskPlanStep("Open Notifications", ActionCommand.SystemNav(SystemAction.NOTIFICATIONS)))

            else -> {
                // Default inspection / query
                steps.add(TaskPlanStep("Inspect current screen", ActionCommand.InspectScreen))
            }
        }

        return steps
    }

    /**
     * Executes the decomposed plan step-by-step with real-time feedback.
     */
    suspend fun executePlan(
        steps: List<TaskPlanStep>,
        onStepProgress: (Int, Int, String) -> Unit
    ): ActionResult {
        val executor = getExecutor()
            ?: return ActionResult(false, "Planner", "Accessibility Service is not running.")

        val total = steps.size
        for ((index, step) in steps.withIndex()) {
            onStepProgress(index + 1, total, step.description)

            val result = executor.execute(step.command)
            if (!result.success && step.command !is ActionCommand.InspectScreen) {
                // If a step fails, try to inspect the screen and retry once if it's a Click
                if (step.command is ActionCommand.Click) {
                    executor.refreshScreenContext()
                    val retryResult = executor.execute(step.command)
                    if (!retryResult.success) {
                        return ActionResult(false, "PlanExecution", "Failed at step [${index + 1}/$total]: ${step.description}")
                    }
                } else {
                    return ActionResult(false, "PlanExecution", "Failed at step [${index + 1}/$total]: ${step.description}")
                }
            }

            if (step.postDelayMs > 0) {
                delay(step.postDelayMs)
            }
        }

        return ActionResult(true, "PlanExecution", "Completed all $total steps successfully.")
    }
}
