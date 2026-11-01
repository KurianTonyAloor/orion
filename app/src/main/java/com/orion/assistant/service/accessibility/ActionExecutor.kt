package com.orion.assistant.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.orion.assistant.model.ActionCommand
import com.orion.assistant.model.ActionResult
import com.orion.assistant.model.ScrollDirection
import com.orion.assistant.model.SystemAction
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class ActionExecutor(
    private val context: Context,
    private val service: OrionAccessibilityService
) {

    private var cachedElements: List<SemanticElement> = emptyList()

    /**
     * Updates screen context and returns semantic screen description.
     */
    fun refreshScreenContext(): String {
        return try {
            val root = service.rootInActiveWindow
            val (description, elements) = ScreenSemanticParser.parseActiveScreen(root)
            cachedElements = elements
            description
        } catch (e: Exception) {
            cachedElements = emptyList()
            "Screen hierarchy temporarily unavailable: ${e.message}"
        }
    }

    suspend fun execute(command: ActionCommand): ActionResult {
        return when (command) {
            is ActionCommand.Click -> handleClick(command)
            is ActionCommand.TapCoordinate -> handleTap(command.x, command.y)
            is ActionCommand.TypeText -> handleType(command)
            is ActionCommand.Scroll -> handleScroll(command.direction)
            is ActionCommand.OpenApp -> handleOpenApp(command.appName)
            is ActionCommand.SystemNav -> handleSystemNav(command.action)
            is ActionCommand.Delay -> {
                kotlinx.coroutines.delay(command.millis)
                ActionResult(true, "Delay", "Waited for ${command.millis}ms")
            }
            is ActionCommand.InspectScreen -> {
                val screenDesc = refreshScreenContext()
                ActionResult(true, "InspectScreen", screenDesc)
            }
            is ActionCommand.CaptureScreenSnapshot -> {
                val bitmap = com.orion.assistant.vision.ScreenCaptureManager.captureCurrentScreen()
                if (bitmap != null) {
                    val path = com.orion.assistant.vision.VisionPreprocessor.saveSnapshot(context, bitmap, "screen")
                    val analysis = com.orion.assistant.vision.VisionPreprocessor.analyzeVisualProperties(bitmap)
                    ActionResult(true, "CaptureScreenSnapshot", "Captured screen successfully. $analysis", imagePath = path)
                } else {
                    ActionResult(false, "CaptureScreenSnapshot", "Failed to capture screen image.")
                }
            }
            is ActionCommand.CaptureCameraPhoto -> {
                ActionResult(true, "CaptureCameraPhoto", "Camera capture ready (trigger from UI/Call context).")
            }
        }
    }

    private suspend fun handleClick(cmd: ActionCommand.Click): ActionResult {
        refreshScreenContext()

        // 1. Target by Index
        if (cmd.targetIndex != null) {
            val element = cachedElements.getOrNull(cmd.targetIndex)
                ?: return ActionResult(false, "Click", "Element index [${cmd.targetIndex}] not found on screen.")

            // Try standard accessibility click
            var clicked = element.nodeRef.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!clicked) {
                // If wrapper isn't clickable, climb up hierarchy
                var parent = element.nodeRef.parent
                while (parent != null && !clicked) {
                    clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    parent = parent.parent
                }
            }

            // Fallback to gesture coordinate tap if accessibility action was rejected
            if (!clicked) {
                val tapped = performTapGesture(element.centerX, element.centerY)
                return ActionResult(tapped, "Click", "Tapped coordinate (${element.centerX}, ${element.centerY}) for element [${cmd.targetIndex}] '${element.text}'")
            }

            return ActionResult(true, "Click", "Clicked element [${cmd.targetIndex}] '${element.text}'")
        }

        // 2. Target by Text
        if (!cmd.targetText.isNullOrBlank()) {
            val success = service.clickByText(cmd.targetText)
            return ActionResult(success, "Click", "Click by text '${cmd.targetText}' result: $success")
        }

        return ActionResult(false, "Click", "No target specified for Click action.")
    }

    private suspend fun handleTap(x: Float, y: Float): ActionResult {
        val success = performTapGesture(x, y)
        return ActionResult(success, "TapCoordinate", "Tapped coordinate ($x, $y)")
    }

    private fun handleType(cmd: ActionCommand.TypeText): ActionResult {
        val root = service.rootInActiveWindow ?: return ActionResult(false, "TypeText", "No active window")

        val targetNode: AccessibilityNodeInfo? = if (cmd.targetIndex != null) {
            cachedElements.getOrNull(cmd.targetIndex)?.nodeRef
        } else {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        }

        if (targetNode == null) {
            return ActionResult(false, "TypeText", "No focused or specified input field found.")
        }

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cmd.text)
        }
        val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        return ActionResult(success, "TypeText", "Typed text into field: $success")
    }

    private suspend fun handleScroll(direction: ScrollDirection): ActionResult {
        val displayMetrics = Resources.getSystem().displayMetrics
        val width = displayMetrics.widthPixels.toFloat()
        val height = displayMetrics.heightPixels.toFloat()

        val (startX, startY, endX, endY) = when (direction) {
            ScrollDirection.DOWN -> listOf(width / 2, height * 0.75f, width / 2, height * 0.25f)
            ScrollDirection.UP -> listOf(width / 2, height * 0.25f, width / 2, height * 0.75f)
            ScrollDirection.LEFT -> listOf(width * 0.8f, height / 2, width * 0.2f, height / 2)
            ScrollDirection.RIGHT -> listOf(width * 0.2f, height / 2, width * 0.8f, height / 2)
        }

        val success = performSwipeGesture(startX, startY, endX, endY, 350)
        return ActionResult(success, "Scroll", "Scrolled $direction")
    }

    private fun handleOpenApp(appName: String): ActionResult {
        val pm = context.packageManager
        val query = appName.lowercase().trim()

        // 1. Direct package match
        var intent = pm.getLaunchIntentForPackage(query)

        // 2. Search application labels
        if (intent == null) {
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in installedApps) {
                val label = pm.getApplicationLabel(app).toString().lowercase()
                if (label.contains(query) || query.contains(label)) {
                    intent = pm.getLaunchIntentForPackage(app.packageName)
                    if (intent != null) break
                }
            }
        }

        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return ActionResult(true, "OpenApp", "Launched app: $appName")
        }

        return ActionResult(false, "OpenApp", "Could not find an installed app matching '$appName'")
    }

    private fun handleSystemNav(action: SystemAction): ActionResult {
        val success = when (action) {
            SystemAction.HOME -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            SystemAction.BACK -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            SystemAction.RECENTS -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            SystemAction.NOTIFICATIONS -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            SystemAction.QUICK_SETTINGS -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            SystemAction.LOCK -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        }
        return ActionResult(success, "SystemNav", "Executed system navigation $action: $success")
    }

    private suspend fun performTapGesture(x: Float, y: Float): Boolean =
        suspendCancellableCoroutine { continuation ->
            service.tapCoordinate(x, y) { success ->
                continuation.resume(success)
            }
        }

    private suspend fun performSwipeGesture(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long): Boolean =
        suspendCancellableCoroutine { continuation ->
            service.swipeCoordinate(startX, startY, endX, endY, duration) { success ->
                continuation.resume(success)
            }
        }
}
