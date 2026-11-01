package com.orion.assistant.vision

import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import com.orion.assistant.service.accessibility.OrionAccessibilityService
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

object ScreenCaptureManager {

    private val executor = Executors.newSingleThreadExecutor()

    /**
     * Captures the current active screen bitmap via OrionAccessibilityService.
     * Returns null if service is not running or capture fails.
     */
    suspend fun captureCurrentScreen(): Bitmap? {
        val service = OrionAccessibilityService.currentInstance ?: return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return try {
                suspendCancellableCoroutine { continuation ->
                    try {
                        service.takeScreenshot(
                            Display.DEFAULT_DISPLAY,
                            executor,
                            object : TakeScreenshotCallback {
                                override fun onSuccess(screenshot: ScreenshotResult) {
                                    try {
                                        val hardwareBuffer = screenshot.hardwareBuffer
                                        val colorSpace = screenshot.colorSpace
                                        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                                        val softwareBitmap = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                        hardwareBuffer.close()
                                        continuation.resume(softwareBitmap)
                                    } catch (_: Exception) {
                                        continuation.resume(null)
                                    }
                                }

                                override fun onFailure(errorCode: Int) {
                                    continuation.resume(null)
                                }
                            }
                        )
                    } catch (_: Exception) {
                        continuation.resume(null)
                    }
                }
            } catch (_: Exception) {
                null
            }
        }
        return null
    }
}
