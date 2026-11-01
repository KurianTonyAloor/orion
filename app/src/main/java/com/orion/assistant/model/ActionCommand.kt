package com.orion.assistant.model

enum class ScrollDirection {
    UP, DOWN, LEFT, RIGHT
}

enum class SystemAction {
    HOME, BACK, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, LOCK
}

sealed class ActionCommand {
    data class Click(val targetIndex: Int? = null, val targetText: String? = null, val viewId: String? = null) : ActionCommand()
    data class TapCoordinate(val x: Float, val y: Float) : ActionCommand()
    data class TypeText(val text: String, val targetIndex: Int? = null) : ActionCommand()
    data class Scroll(val direction: ScrollDirection) : ActionCommand()
    data class OpenApp(val appName: String) : ActionCommand()
    data class SystemNav(val action: SystemAction) : ActionCommand()
    data class Delay(val millis: Long) : ActionCommand()
    object InspectScreen : ActionCommand()
    object CaptureScreenSnapshot : ActionCommand()
    object CaptureCameraPhoto : ActionCommand()
}

data class ActionResult(
    val success: Boolean,
    val actionName: String,
    val details: String,
    val screenChanged: Boolean = false,
    val imagePath: String? = null
)
