package com.orion.assistant.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File
import java.io.FileOutputStream

object VisionPreprocessor {

    const val TARGET_SIZE = 384

    /**
     * Resizes and letterboxes a bitmap to square target dimensions (e.g. 384x384)
     * suitable for lightweight on-device vision models like Moondream or MobileVLM.
     */
    fun preprocessBitmap(source: Bitmap, targetWidth: Int = TARGET_SIZE, targetHeight: Int = TARGET_SIZE): Bitmap {
        val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)

        val srcWidth = source.width
        val srcHeight = source.height

        val scale = Math.min(
            targetWidth.toFloat() / srcWidth.toFloat(),
            targetHeight.toFloat() / srcHeight.toFloat()
        )

        val scaledWidth = (srcWidth * scale).toInt()
        val scaledHeight = (srcHeight * scale).toInt()

        val left = (targetWidth - scaledWidth) / 2f
        val top = (targetHeight - scaledHeight) / 2f

        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val scaledSource = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
        canvas.drawBitmap(scaledSource, left, top, paint)

        return output
    }

    /**
     * Saves a snapshot to the app cache directory and returns the absolute file path.
     */
    fun saveSnapshot(context: Context, bitmap: Bitmap, prefix: String = "screen_snap"): String? {
        return try {
            val file = File(context.cacheDir, "${prefix}_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            file.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extracts simple visual summary heuristics (dimensions, brightness level, orientation).
     */
    fun analyzeVisualProperties(bitmap: Bitmap): String {
        val width = bitmap.width
        val height = bitmap.height
        val aspectRatio = String.format("%.2f", width.toFloat() / height.toFloat())
        return "Image dimensions: ${width}x${height} (Aspect ratio: $aspectRatio)"
    }
}
