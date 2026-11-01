package com.orion.assistant.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UIElement(
    val id: String?,
    val text: String?,
    val contentDescription: String?,
    val className: String,
    val bounds: Rect,
    val isClickable: Boolean,
    val isEditable: Boolean
)

class OrionAccessibilityService : AccessibilityService() {

    companion object {
        private val _isServiceConnected = MutableStateFlow(false)
        val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

        var currentInstance: OrionAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            val currentInfo = serviceInfo ?: android.accessibilityservice.AccessibilityServiceInfo()
            currentInfo.eventTypes = -1
            currentInfo.feedbackType = android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC
            currentInfo.notificationTimeout = 100
            this.serviceInfo = currentInfo

            currentInstance = this
            _isServiceConnected.value = true
            android.util.Log.d("OrionA11y", "Accessibility service connected successfully")
        } catch (e: Exception) {
            android.util.Log.e("OrionA11y", "Error in onServiceConnected", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Can be used to track active foreground window package changes
    }

    override fun onInterrupt() {
        // Handle accessibility interruption
    }

    override fun onDestroy() {
        super.onDestroy()
        currentInstance = null
        _isServiceConnected.value = false
    }

    /**
     * Recursively traverses active window hierarchy and returns structured UI elements for the LLM.
     */
    fun dumpScreenHierarchy(): List<UIElement> {
        val root = rootInActiveWindow ?: return emptyList()
        val elements = mutableListOf<UIElement>()
        traverseNode(root, elements)
        return elements
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, list: MutableList<UIElement>) {
        if (node == null) return

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val viewId = node.viewIdResourceName

        // Include interactive or informative nodes
        if (!text.isNullOrBlank() || !desc.isNullOrBlank() || node.isClickable || node.isEditable) {
            list.add(
                UIElement(
                    id = viewId,
                    text = text,
                    contentDescription = desc,
                    className = node.className?.toString() ?: "",
                    bounds = bounds,
                    isClickable = node.isClickable,
                    isEditable = node.isEditable
                )
            )
        }

        for (i in 0 until node.childCount) {
            traverseNode(node.getChild(i), list)
        }
    }

    /**
     * Clicks an element by matching text or content description.
     */
    fun clickByText(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val matchedNodes = root.findAccessibilityNodeInfosByText(query)
        for (node in matchedNodes) {
            var target: AccessibilityNodeInfo? = node
            while (target != null && !target.isClickable) {
                target = target.parent
            }
            if (target?.isClickable == true) {
                val success = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (success) return true
            }
        }
        return false
    }

    /**
     * Inputs text into an active editable node.
     */
    fun inputText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    /**
     * Dispatches arbitrary coordinate touch gestures (tap).
     */
    fun tapCoordinate(x: Float, y: Float, onComplete: ((Boolean) -> Unit)? = null) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onComplete?.invoke(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                onComplete?.invoke(false)
            }
        }, null)
    }

    /**
     * Dispatches swipe/scroll gestures.
     */
    fun swipeCoordinate(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 300,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onComplete?.invoke(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                onComplete?.invoke(false)
            }
        }, null)
    }

    // Standard Android System Navigation
    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
}
