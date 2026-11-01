package com.orion.assistant.agent

import android.content.Context
import android.os.BatteryManager
import com.orion.assistant.service.accessibility.ActionExecutor
import com.orion.assistant.service.accessibility.OrionAccessibilityService
import com.orion.assistant.vision.ScreenCaptureManager
import com.orion.assistant.vision.VisionPreprocessor

object ContextualAgentPrompt {

    /**
     * Gathers all available device and sensory context safely without throwing crashes.
     */
    suspend fun gatherFullContext(
        context: Context,
        executor: ActionExecutor?,
        includeVisualSnapshot: Boolean = false
    ): String {
        // Safe screen context gathering
        val screenContext = try {
            executor?.refreshScreenContext() ?: "Screen content unavailable (Accessibility service inactive)."
        } catch (e: Exception) {
            "Screen content unavailable: ${e.message}"
        }

        // Safe battery level check
        val batteryLevel = try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        } catch (_: Exception) {
            -1
        }

        // Safe visual capture
        val visualInfo = if (includeVisualSnapshot) {
            try {
                val bitmap = ScreenCaptureManager.captureCurrentScreen()
                if (bitmap != null) {
                    val path = VisionPreprocessor.saveSnapshot(context, bitmap, "agent_eye")
                    val analysis = VisionPreprocessor.analyzeVisualProperties(bitmap)
                    "[Visual Frame: Captured at $path. $analysis]"
                } else {
                    "[Visual Frame: Screen capture not available]"
                }
            } catch (e: Exception) {
                "[Visual Frame: Error ${e.message}]"
            }
        } else {
            ""
        }

        return buildString {
            appendLine("=== SYSTEM DEVICE CONTEXT ===")
            appendLine("Device: Motorola G84 5G (Snapdragon 695)")
            if (batteryLevel >= 0) {
                appendLine("Battery: $batteryLevel%")
            }
            if (visualInfo.isNotBlank()) {
                appendLine(visualInfo)
            }
            appendLine("\n=== ACTIVE SCREEN UI ELEMENTS ===")
            appendLine(screenContext)
        }
    }

    /**
     * Builds the complete ChatML system prompt for the local model.
     */
    fun buildSystemPrompt(contextSnapshot: String): String {
        return """
        You are Orion, an autonomous on-device AI assistant running locally on a Motorola G84 5G.
        You have direct control over the phone via system actions and accessibility tools.

        $contextSnapshot

        Your capabilities:
        You can analyze the screen and choose the exact action script to execute.
        Available tool scripts (output ONLY a JSON action block):
        1. {"thought": "<reasoning>", "action": "click", "index": <element_number>}
        2. {"thought": "<reasoning>", "action": "click", "text": "<button_or_link_text>"}
        3. {"thought": "<reasoning>", "action": "type", "text": "<text_to_type>", "index": <optional_input_number>}
        4. {"thought": "<reasoning>", "action": "scroll", "direction": "up"|"down"|"left"|"right"}
        5. {"thought": "<reasoning>", "action": "open_app", "name": "<app_name>"}
        6. {"thought": "<reasoning>", "action": "system", "nav": "home"|"back"|"recents"|"notifications"}
        7. {"thought": "<reasoning>", "action": "capture_screen"}
        8. {"thought": "<reasoning>", "action": "capture_camera"}
        9. {"thought": "<reasoning>", "action": "call_user", "reason": "<why_calling>"}
        10. {"thought": "<reasoning>", "action": "respond", "message": "<your_spoken_or_written_reply>"}

        RULES:
        - If the user asks a knowledge question or wants advice, use action "respond".
        - If the user wants an action done on screen (e.g. tap a button, find info, search), inspect the ACTIVE SCREEN UI ELEMENTS above and output the appropriate tool action.
        - Output valid JSON only.
        """.trimIndent()
    }
}
