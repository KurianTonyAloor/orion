package com.orion.assistant.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log
import com.orion.assistant.llm.LlamaCppRuntime
import com.orion.assistant.llm.ModelRole
import org.json.JSONObject

data class InstalledAppInfo(
    val appName: String,
    val packageName: String,
    val description: String = ""
)

data class DynamicPlan(
    val selectedGeneratorApp: String,
    val selectedMessagingApp: String,
    val recipientName: String?,
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
    fun getInstalledLauncherApps(): List<InstalledAppInfo> {
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveList: List<ResolveInfo> = pm.queryIntentActivities(intent, 0)
        val appList = mutableListOf<InstalledAppInfo>()

        for (resolve in resolveList) {
            val pkg = resolve.activityInfo.packageName
            if (pkg == context.packageName) continue

            val label = resolve.loadLabel(pm).toString()
            appList.add(InstalledAppInfo(appName = label, packageName = pkg))
        }

        return appList.distinctBy { it.appName }
    }

    /**
     * Passes the entire user task and real installed apps list to the LLM
     * so it dynamically extracts the recipient name and selects which apps to use.
     */
    suspend fun selectAppsForTask(
        userTask: String,
        candidateApps: List<InstalledAppInfo> = getInstalledLauncherApps()
    ): DynamicPlan {
        val appNames = candidateApps.map { it.appName }

        val systemPrompt = """
            You are Orion AI planner running on Android.
            Given the user task and the list of available apps on the phone:
            1. Extract the recipient contact name if mentioned (e.g., "Sreehari", "Mom", "John").
            2. Choose the best app to create/generate the requested asset.
            3. Choose the best app to deliver/send the result.
            4. Formulate an optimized generation prompt.

            Installed Apps on device:
            ${appNames.joinToString(", ")}

            Respond ONLY with a JSON object:
            {
              "recipient_name": "<exact contact name or null>",
              "generator_app": "<App name from list, e.g. Gemini, ChatGPT, Canva>",
              "messaging_app": "<App name from list, e.g. WhatsApp, Telegram, Messages>",
              "creation_prompt": "<Optimized prompt for generator>",
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
            val rawRecipient = json.optString("recipient_name", "").trim()
            val recipient = if (rawRecipient.isNotBlank() && rawRecipient != "null") rawRecipient else null

            val resolvedGen = candidateApps.find { it.appName.equals(genApp, ignoreCase = true) }?.appName
                ?: candidateApps.find { it.appName.contains("Gemini", ignoreCase = true) || it.appName.contains("ChatGPT", ignoreCase = true) }?.appName
                ?: genApp.ifBlank { "Gemini" }

            val resolvedMsg = candidateApps.find { it.appName.equals(msgApp, ignoreCase = true) }?.appName
                ?: candidateApps.find { it.appName.contains("WhatsApp", ignoreCase = true) }?.appName
                ?: msgApp.ifBlank { "WhatsApp" }

            Log.d(TAG, "LLM plan: recipient=$recipient, generator=$resolvedGen, messenger=$resolvedMsg")
            return DynamicPlan(
                selectedGeneratorApp = resolvedGen,
                selectedMessagingApp = resolvedMsg,
                recipientName = recipient,
                promptToUse = prompt.ifBlank { "Generate a festive celebration poster, high resolution" },
                format = fmt.ifBlank { "pdf" }
            )
        }

        // Heuristic fallback
        val fallbackGen = candidateApps.find { it.appName.contains("ChatGPT", ignoreCase = true) }?.appName
            ?: candidateApps.find { it.appName.contains("Gemini", ignoreCase = true) }?.appName
            ?: "Gemini"

        val fallbackMsg = candidateApps.find { it.appName.contains("WhatsApp", ignoreCase = true) }?.appName
            ?: "WhatsApp"

        return DynamicPlan(
            selectedGeneratorApp = fallbackGen,
            selectedMessagingApp = fallbackMsg,
            recipientName = null,
            promptToUse = "Generate a vibrant festive celebration poster with traditional art",
            format = "pdf"
        )
    }
}
