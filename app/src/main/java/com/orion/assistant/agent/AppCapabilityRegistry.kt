package com.orion.assistant.agent

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

enum class AppCapability {
    AI_IMAGE_GENERATOR,
    MESSAGING,
    IMAGE_TO_PDF_CONVERTER,
    EMAIL,
    BROWSER,
    NOTES,
    FILE_MANAGER
}

data class DiscoveredApp(
    val appName: String,
    val packageName: String,
    val capability: AppCapability,
    val priority: Int = 1
)

class AppCapabilityRegistry(private val context: Context) {

    private val pm: PackageManager = context.packageManager

    /**
     * Inspects all installed apps and groups them by functional capabilities.
     */
    fun scanDeviceCapabilities(): Map<AppCapability, List<DiscoveredApp>> {
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val capabilityMap = mutableMapOf<AppCapability, MutableList<DiscoveredApp>>()

        for (app in installedApps) {
            // Ignore pure system packages unless user-facing
            val label = pm.getApplicationLabel(app).toString().lowercase()
            val pkg = app.packageName.lowercase()

            val detectedCapability = classifyApp(label, pkg)
            if (detectedCapability != null) {
                val list = capabilityMap.getOrPut(detectedCapability) { mutableListOf() }
                list.add(
                    DiscoveredApp(
                        appName = pm.getApplicationLabel(app).toString(),
                        packageName = app.packageName,
                        capability = detectedCapability,
                        priority = getAppPriority(label, pkg)
                    )
                )
            }
        }

        // Sort each category by priority (highest priority first)
        return capabilityMap.mapValues { (_, list) ->
            list.sortedByDescending { it.priority }
        }
    }

    /**
     * Finds the best installed app for a required capability.
     */
    fun findBestAppFor(capability: AppCapability): DiscoveredApp? {
        val map = scanDeviceCapabilities()
        return map[capability]?.firstOrNull()
    }

    private fun classifyApp(label: String, pkg: String): AppCapability? {
        return when {
            // AI Image & Content Generators
            label.contains("chatgpt") || pkg.contains("openai") -> AppCapability.AI_IMAGE_GENERATOR
            label.contains("gemini") || pkg.contains("google.android.apps.bard") -> AppCapability.AI_IMAGE_GENERATOR
            label.contains("copilot") || pkg.contains("copilot") -> AppCapability.AI_IMAGE_GENERATOR
            label.contains("canva") -> AppCapability.AI_IMAGE_GENERATOR

            // Messaging
            label.contains("whatsapp") || pkg.contains("whatsapp") -> AppCapability.MESSAGING
            label.contains("telegram") || pkg.contains("telegram") -> AppCapability.MESSAGING
            label.contains("signal") -> AppCapability.MESSAGING
            label.contains("messages") || pkg.contains("messaging") -> AppCapability.MESSAGING

            // Image to PDF / Document Converters
            label.contains("pdf") && (label.contains("convert") || label.contains("image") || label.contains("maker") || label.contains("scan")) -> AppCapability.IMAGE_TO_PDF_CONVERTER
            label.contains("camscanner") || label.contains("adobe scan") -> AppCapability.IMAGE_TO_PDF_CONVERTER
            pkg.contains("pdf") || pkg.contains("converter") -> AppCapability.IMAGE_TO_PDF_CONVERTER

            // Email
            label.contains("gmail") || label.contains("outlook") -> AppCapability.EMAIL

            // Notes
            label.contains("keep") || label.contains("notes") || label.contains("notion") -> AppCapability.NOTES

            else -> null
        }
    }

    private fun getAppPriority(label: String, pkg: String): Int {
        return when {
            // Prefer Gemini or ChatGPT first for AI generation
            label.contains("gemini") -> 10
            label.contains("chatgpt") -> 9
            label.contains("copilot") -> 8
            label.contains("whatsapp") -> 10
            label.contains("telegram") -> 8
            else -> 5
        }
    }
}
