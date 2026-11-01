package com.orion.assistant.service.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

data class SemanticElement(
    val index: Int,
    val type: String,
    val text: String,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isFocused: Boolean,
    val bounds: Rect,
    val centerX: Float,
    val centerY: Float,
    val nodeRef: AccessibilityNodeInfo
)

object ScreenSemanticParser {

    /**
     * Parses the current window hierarchy into an indexed list of meaningful elements.
     */
    fun parseActiveScreen(root: AccessibilityNodeInfo?): Pair<String, List<SemanticElement>> {
        if (root == null) return Pair("Empty or inaccessible screen.", emptyList())

        val rawElements = mutableListOf<AccessibilityNodeInfo>()
        collectInteractiveNodes(root, rawElements)

        val semanticList = mutableListOf<SemanticElement>()
        val stringBuilder = StringBuilder()

        val packageName = root.packageName?.toString() ?: "Unknown"
        stringBuilder.appendLine("[Current App/Package: $packageName]")

        var idx = 0
        for (node in rawElements) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            // Ignore offscreen or 0-area invisible items
            if (bounds.width() <= 0 || bounds.height() <= 0) continue

            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()
            val label = when {
                !text.isNullOrBlank() && !desc.isNullOrBlank() && text != desc -> "$text ($desc)"
                !text.isNullOrBlank() -> text
                !desc.isNullOrBlank() -> desc
                else -> ""
            }

            // Simplify class names
            val rawClass = node.className?.toString() ?: ""
            val type = when {
                node.isEditable || rawClass.contains("EditText", ignoreCase = true) -> "InputField"
                node.isCheckable || rawClass.contains("CheckBox", ignoreCase = true) -> "CheckBox"
                rawClass.contains("Button", ignoreCase = true) -> "Button"
                rawClass.contains("Image", ignoreCase = true) && node.isClickable -> "IconButton"
                rawClass.contains("TextView", ignoreCase = true) && node.isClickable -> "ClickableText"
                rawClass.contains("TextView", ignoreCase = true) -> "Text"
                node.isClickable -> "ClickableItem"
                else -> "Item"
            }

            // Only list items with text/description or that are clickable/editable
            if (label.isNotBlank() || node.isClickable || node.isEditable) {
                val element = SemanticElement(
                    index = idx,
                    type = type,
                    text = label,
                    isClickable = node.isClickable,
                    isEditable = node.isEditable,
                    isFocused = node.isFocused,
                    bounds = bounds,
                    centerX = bounds.exactCenterX(),
                    centerY = bounds.exactCenterY(),
                    nodeRef = node
                )

                semanticList.add(element)

                val details = buildString {
                    append("[$idx] ")
                    append(type)
                    if (label.isNotBlank()) append(": \"$label\"")
                    if (node.isFocused) append(" (Focused)")
                }
                stringBuilder.appendLine(details)
                idx++
            }
        }

        if (semanticList.isEmpty()) {
            stringBuilder.appendLine("(No interactive elements found on screen)")
        }

        return Pair(stringBuilder.toString().trim(), semanticList)
    }

    private fun collectInteractiveNodes(node: AccessibilityNodeInfo?, output: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return

        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()

        if (node.isClickable || node.isEditable || !text.isNullOrBlank() || !desc.isNullOrBlank()) {
            output.add(node)
        }

        for (i in 0 until node.childCount) {
            collectInteractiveNodes(node.getChild(i), output)
        }
    }
}
