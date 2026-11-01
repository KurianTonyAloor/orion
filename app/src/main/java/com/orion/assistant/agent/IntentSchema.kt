package com.orion.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

enum class IntentType {
    GENERAL_QUERY, // Chit-chat, knowledge questions, explanations, math
    DEVICE_TASK    // Action on device, multi-app coordination, app navigation, media playback
}

data class TaskStep(
    val stepIndex: Int,
    val targetApp: String,
    val actionType: String, // "open", "type_and_submit", "click_result", "create_and_share", "navigate"
    val parameters: JSONObject = JSONObject(),
    val description: String
)

data class FormattedTaskPlan(
    val isTask: Boolean,
    val answer: String? = null,
    val targetApps: List<String> = emptyList(),
    val steps: List<TaskStep> = emptyList()
)

object IntentSchema {

    fun parseIntentPlan(rawOutput: String): FormattedTaskPlan {
        val trimmed = rawOutput.trim()
        if (trimmed.contains("{") && trimmed.contains("}")) {
            val jsonString = trimmed.substring(trimmed.indexOf("{"), trimmed.lastIndexOf("}") + 1)
            try {
                val json = JSONObject(jsonString)
                val type = json.optString("type", "query").lowercase()

                if (type == "task" || json.has("steps") || json.has("target_apps")) {
                    val appsArray = json.optJSONArray("target_apps") ?: JSONArray()
                    val appsList = mutableListOf<String>()
                    for (i in 0 until appsArray.length()) {
                        appsList.add(appsArray.getString(i))
                    }

                    val stepsArray = json.optJSONArray("steps") ?: JSONArray()
                    val stepsList = mutableListOf<TaskStep>()
                    for (i in 0 until stepsArray.length()) {
                        val stepObj = stepsArray.getJSONObject(i)
                        stepsList.add(
                            TaskStep(
                                stepIndex = stepObj.optInt("step", i + 1),
                                targetApp = stepObj.optString("target_app", appsList.firstOrNull() ?: ""),
                                actionType = stepObj.optString("action", "navigate"),
                                parameters = stepObj.optJSONObject("parameters") ?: JSONObject(),
                                description = stepObj.optString("description", "Execute task step")
                            )
                        )
                    }

                    return FormattedTaskPlan(
                        isTask = true,
                        targetApps = appsList,
                        steps = stepsList
                    )
                } else {
                    val answer = json.optString("answer", json.optString("message", trimmed))
                    return FormattedTaskPlan(isTask = false, answer = answer)
                }
            } catch (_: Exception) {}
        }

        return FormattedTaskPlan(isTask = false, answer = trimmed)
    }
}
