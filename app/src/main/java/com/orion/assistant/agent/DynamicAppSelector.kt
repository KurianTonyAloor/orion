package com.orion.assistant.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log
import com.orion.assistant.llm.LlamaCppRuntime
import com.orion.assistant.llm.ModelRole
import org.json.JSONObject

data class DiscoveredApp(
    val appName: String,
    val packageName: String,
    val description: String = ""
)

data class DynamicPlan(
    val selectedGeneratorApp: String,
    val selectedMessagingApp: String,
    val promptToUse: String,
    val format: String
)

class DynamicAppSelector(private val context: Context) {

    companion object {
        private const val TAG = "DynamicAppSelector"
    }

    private val pm: PackageManager = context.packageManager
    private val llamaRuntime = LlamaCppRuntime(context)

    /**
     * Queries Android's PackageManager for user-launchable apps on the device.
     */
    fun getInstalledLauncherApps(): List<DiscoveredApp> {
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveList: List<ResolveInfo> = pm.queryIntentActivities(intent, 0)
        val appList = mutableListOf<DiscoveredApp>()

        for (resolve in resolveList) {
            val pkg = resolve.activityInfo.packageName
            // Exclude orion itself from candidates
            if (pkg == context.packageName) continue

            val label = resolve.loadLabel(pm).toString()
            appList.add(DiscoveredApp(appName = label, packageName = pkg))
        }

        return appList.distinctBy { it.appName }
    }

    /**
     * Passes the entire user task and real installed apps list to the LLM
     * so it dynamically selects which app to use for creation and delivery.
     */
    suspend fun selectAppsForTask(
        userTask: String,
        candidateApps: List<DiscoveredApp> = getInstalledLauncherApps()
    ): DynamicPlan {
        val appNames = candidateApps.map { it.appName }

        val systemPrompt = """
            You are Orion AI planner running on Android.
            Given the user task and the list of available apps on the phone, choose the best app to create/generate the requested asset, and the best app to send it to the contact.

            Installed Apps on device:
            ${appNames.joinToString(", ")}

            Respond ONLY with a JSON object:
            {
              "generator_app": "<App name from list, e.g. Gemini, ChatGPT, Canva, Chrome>",
              "messaging_app": "<App name from list, e.g. WhatsApp, Telegram, Messages>",
              "creation_prompt": "<Optimized prompt for the generator app>",
              "format": "pdf" or "image"
            }
        """.trimIndent()

        Log.d(TAG, "Requesting dynamic app selection from LLM for: '$userTask'")
        val decision = llamaRuntime.decideNextAction(
            systemPrompt = systemPrompt,
            userGoal = userTask,
            role = ModelRole.PRIMARY_REASONER
        )

        val json = decision.rawJson
        if (json != null) {
            val genApp = json.optString("generator_app", "").trim()
            val msgApp = json.optString("messaging_app", "").trim()
            val prompt = json.optString("creation_prompt", "").trim()
            val fmt = json.optString("format", "pdf").trim()

            // Verify the selected generator app is installed
            val resolvedGen = candidateApps.find { it.appName.equals(genApp, ignoreCase = true) }?.appName
                ?: candidateApps.find { it.appName.contains("Gemini", ignoreCase = true) || it.appName.contains("ChatGPT", ignoreCase = true) }?.appName
                ?: genApp.ifBlank { "Gemini" }

            val resolvedMsg = candidateApps.find { it.appName.equals(msgApp, ignoreCase = true) }?.appName
                ?: candidateApps.find { it.appName.contains("WhatsApp", ignoreCase = true) }?.appName
                ?: msgApp.ifBlank { "WhatsApp" }

            Log.d(TAG, "LLM dynamically selected: generator=$resolvedGen, messenger=$resolvedMsg")
            return DynamicPlan(
                selectedGeneratorApp = resolvedGen,
                selectedMessagingApp = resolvedMsg,
                promptToUse = prompt.ifBlank { "Generate a festive celebration poster, high resolution" },
                format = fmt.ifBlank { "pdf" }
            )
        }

        // Heuristic fallback if local model is offline/unloaded
        val fallbackGen = candidateApps.find { it.appName.contains("ChatGPT", ignoreCase = true) }?.appName
            ?: candidateApps.find { it.appName.contains("Gemini", ignoreCase = true) }?.appName
            ?: "Gemini"

        val fallbackMsg = candidateApps.find { it.appName.contains("WhatsApp", ignoreCase = true) }?.appName
            ?: "WhatsApp"

        return DynamicPlan(
            selectedGeneratorApp = fallbackGen,
            selectedMessagingApp = fallbackMsg,
            promptToUse = "Generate a vibrant festive celebration poster with traditional art",
            format = "pdf"
        )
    }
}
