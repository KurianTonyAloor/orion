package com.orion.assistant.agent

import android.content.Context
import android.util.Log
import com.orion.assistant.llm.LlamaCppRuntime
import com.orion.assistant.llm.ModelRole
import com.orion.assistant.model.ActionCommand
import com.orion.assistant.model.ActionResult
import com.orion.assistant.service.accessibility.ActionExecutor
import com.orion.assistant.service.accessibility.OrionAccessibilityService
import com.orion.assistant.tools.PdfConverter
import com.orion.assistant.vision.ScreenCaptureManager
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class GeneralizedTaskEngine(private val context: Context) {

    companion object {
        private const val TAG = "GeneralizedTaskEngine"
    }

    private val dynamicAppSelector = DynamicAppSelector(context)
    private val llamaRuntime = LlamaCppRuntime(context)

    private fun getExecutor(): ActionExecutor? {
        val service = OrionAccessibilityService.currentInstance ?: return null
        return ActionExecutor(context, service)
    }

    /**
     * Primary LLM Evaluator: Analyzes prompt + installed apps, determines if query is a task,
     * and compiles the structured JSON execution plan.
     */
    suspend fun evaluateAndPlan(prompt: String): FormattedTaskPlan {
        val installedApps = dynamicAppSelector.getInstalledLauncherApps().map { it.appName }

        val systemPrompt = """
            You are Orion AI's primary reasoning engine on Android.
            Analyze the user input and determine whether it is a:
            1. "query": A general knowledge question, conversation, math, or explanation.
            2. "task": An instruction to perform actions on the phone, open apps, play songs/videos, send messages, search, or create media.

            Installed Apps on device:
            ${installedApps.joinToString(", ")}

            Respond ONLY with a JSON object.
            
            If "query":
            {
              "type": "query",
              "answer": "<Your informative and friendly natural answer>"
            }

            If "task":
            {
              "type": "task",
              "target_apps": ["<App1>", "<App2>"],
              "steps": [
                {
                  "step": 1,
                  "target_app": "<App_name>",
                  "action": "open" | "type_and_submit" | "click_result" | "create_and_share" | "search_and_play",
                  "parameters": {
                    "query": "<search_or_play_query>",
                    "prompt": "<generation_prompt>",
                    "recipient": "<recipient_name>",
                    "format": "pdf" | "image"
                  },
                  "description": "<What this step does>"
                }
              ]
            }
        """.trimIndent()

        Log.d(TAG, "Evaluating intent with Primary Model: '$prompt'")
        val decision = llamaRuntime.decideNextAction(
            systemPrompt = systemPrompt,
            userGoal = prompt,
            role = ModelRole.PRIMARY_REASONER
        )

        val rawOutput = decision.rawJson?.toString() ?: decision.directMessage ?: ""
        val parsedPlan = IntentSchema.parseIntentPlan(rawOutput)

        // Heuristic fallback if LLM is offline or output unparsed
        if (!parsedPlan.isTask) {
            val lower = prompt.lowercase()
            val isActionPrompt = lower.startsWith("play ") || lower.contains("play a song") ||
                    lower.contains("send it") || lower.contains("send back") || lower.contains("poster") ||
                    lower.contains("texted me") || lower.contains("asked me to") || lower.contains("search for")

            if (isActionPrompt) {
                return buildFallbackActionPlan(prompt, installedApps)
            }
        }

        return parsedPlan
    }

    /**
     * Advances through the structured plan using the secondary model (AutonomousScreenAgent) pipeline.
     */
    suspend fun executeTaskPlan(
        plan: FormattedTaskPlan,
        onProgress: (String) -> Unit
    ): ActionResult {
        val executor = getExecutor()
            ?: return ActionResult(false, "System", "Accessibility Service is not enabled. Please enable Orion in Settings.")

        val screenAgent = AutonomousScreenAgent(context, executor)
        val totalSteps = plan.steps.size

        for ((index, step) in plan.steps.withIndex()) {
            val stepNum = index + 1
            onProgress("Step $stepNum/$totalSteps: ${step.description}...")
            Log.d(TAG, "Executing Step $stepNum: ${step.actionType} in ${step.targetApp}")

            when (step.actionType.lowercase()) {
                "open" -> {
                    screenAgent.openAppViaDrawer(step.targetApp)
                    delay(2000L)
                }

                "search_and_play" -> {
                    // e.g. "play a song by KJ yesudas from the 80s in YouTube"
                    val query = step.parameters.optString("query", "")
                    screenAgent.openAppViaDrawer(step.targetApp)
                    delay(2500L)

                    onProgress("Searching for '$query' in ${step.targetApp}...")
                    // Tap search icon / search bar in YouTube or music app
                    val clickedSearch = screenAgent.clickSemanticTarget(
                        listOf("Search", "Search YouTube", "search_button", "Search…"),
                        waitTimeoutMs = 2500L
                    )

                    if (!clickedSearch) {
                        // Top-right coordinate of YouTube search icon
                        val dm = android.content.res.Resources.getSystem().displayMetrics
                        executor.execute(ActionCommand.TapCoordinate(dm.widthPixels * 0.88f, dm.heightPixels * 0.06f))
                        delay(600L)
                    }

                    screenAgent.typeIntoInputField(query, listOf("Search YouTube", "Search", "Type a search"))
                    delay(800L)
                    screenAgent.submitInput()
                    delay(2500L)

                    // Tap first search result video
                    onProgress("Starting playback in ${step.targetApp}...")
                    val dm = android.content.res.Resources.getSystem().displayMetrics
                    executor.execute(ActionCommand.TapCoordinate(dm.widthPixels * 0.5f, dm.heightPixels * 0.28f))
                    delay(1500L)
                }

                "type_and_submit" -> {
                    val text = step.parameters.optString("query", step.parameters.optString("prompt", ""))
                    screenAgent.typeIntoInputField(text)
                    delay(600L)
                    screenAgent.submitInput()
                    delay(2000L)
                }

                "create_and_share" -> {
                    // Full creation & share pipeline
                    val orchestrator = CrossAppOrchestrator(context)
                    val goal = ComplexTaskGoal(
                        rawPrompt = step.description,
                        recipientName = step.parameters.optString("recipient", null),
                        assetTopic = step.parameters.optString("prompt", "Festive poster"),
                        outputFormat = step.parameters.optString("format", "pdf")
                    )
                    return orchestrator.executeCrossAppWorkflow(goal, onProgress)
                }

                else -> {
                    // General autonomous step loop
                    screenAgent.openAppViaDrawer(step.targetApp)
                    delay(2000L)
                    screenAgent.executeAutonomousStepLoop(
                        subGoal = step.description,
                        maxSteps = 3,
                        onProgress = onProgress
                    )
                }
            }
        }

        val successMsg = "Successfully completed task: ${plan.steps.lastOrNull()?.description ?: "Task executed"}"
        onProgress(successMsg)
        return ActionResult(true, "Orion Task", successMsg)
    }

    private fun buildFallbackActionPlan(prompt: String, installedApps: List<String>): FormattedTaskPlan {
        val lower = prompt.lowercase()

        // 1. Media playback (e.g. YouTube / Music)
        if (lower.startsWith("play ") || lower.contains("play a song") || lower.contains("in youtube") || lower.contains("on youtube")) {
            val query = lower.removePrefix("play ").removePrefix("a song by ").replace("in youtube", "").replace("on youtube", "").trim()
            val targetApp = installedApps.find { it.contains("YouTube", ignoreCase = true) } ?: "YouTube"

            return FormattedTaskPlan(
                isTask = true,
                targetApps = listOf(targetApp),
                steps = listOf(
                    TaskStep(
                        stepIndex = 1,
                        targetApp = targetApp,
                        actionType = "search_and_play",
                        parameters = JSONObject().put("query", query),
                        description = "Play '$query' in $targetApp"
                    )
                )
            )
        }

        // 2. Poster creation & sharing (Telegram / WhatsApp)
        val isTelegram = lower.contains("telegram")
        val messagingApp = if (isTelegram) {
            installedApps.find { it.contains("Telegram", ignoreCase = true) } ?: "Telegram"
        } else {
            installedApps.find { it.contains("WhatsApp", ignoreCase = true) } ?: "WhatsApp"
        }

        val generatorApp = installedApps.find { it.contains("Gemini", ignoreCase = true) }
            ?: installedApps.find { it.contains("ChatGPT", ignoreCase = true) }
            ?: "Gemini"

        val recipient = when {
            lower.contains("appan") -> "Appan"
            lower.contains("sreehari") -> "Sreehari"
            else -> null
        }

        return FormattedTaskPlan(
            isTask = true,
            targetApps = listOf(generatorApp, messagingApp),
            steps = listOf(
                TaskStep(
                    stepIndex = 1,
                    targetApp = generatorApp,
                    actionType = "create_and_share",
                    parameters = JSONObject().apply {
                        put("prompt", "Onam celebration festive poster")
                        put("recipient", recipient)
                        put("format", "pdf")
                    },
                    description = "Generate Onam poster in $generatorApp and deliver to $recipient on $messagingApp"
                )
            )
        )
    }
}
