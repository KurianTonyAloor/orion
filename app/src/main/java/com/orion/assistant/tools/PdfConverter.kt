package com.orion.assistant.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object PdfConverter {
    private const val TAG = "PdfConverter"

    // Standard A4 dimensions in points (72 points per inch)
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842

    /**
     * Converts an image file or Bitmap into a high-quality PDF document using native Android PdfDocument API.
     * Saved to app cache or external downloads for cross-app sharing.
     */
    suspend fun convertImageToPdf(
        context: Context,
        imageFile: File,
        outputFileName: String = "Orion_Poster_${System.currentTimeMillis()}.pdf"
    ): File? = withContext(Dispatchers.IO) {
        try {
            if (!imageFile.exists()) {
                Log.e(TAG, "Source image does not exist: ${imageFile.absolutePath}")
                return@withContext null
            }

            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                ?: return@withContext null

            return@withContext convertBitmapToPdf(context, bitmap, outputFileName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert image to PDF", e)
            null
        }
    }

    /**
     * Converts a Bitmap into an A4 PDF page scaled proportionally with centering.
     */
    suspend fun convertBitmapToPdf(
        context: Context,
        bitmap: Bitmap,
        outputFileName: String = "Orion_Poster_${System.currentTimeMillis()}.pdf"
    ): File? = withContext(Dispatchers.IO) {
        val document = PdfDocument()
        try {
            val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
            val page = document.startPage(pageInfo)
            val canvas: Canvas = page.canvas

            // Fill page background with crisp clean white
            val bgPaint = Paint().apply { color = Color.WHITE }
            canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), PAGE_HEIGHT.toFloat(), bgPaint)

            // Calculate proportional scale to fit within page with margins
            val margin = 20f
            val maxW = PAGE_WIDTH - (margin * 2)
            val maxH = PAGE_HEIGHT - (margin * 2)

            val scale = (maxW / bitmap.width).coerceAtMost(maxH / bitmap.height)
            val scaledW = bitmap.width * scale
            val scaledH = bitmap.height * scale

            // Center image on the page
            val left = margin + (maxW - scaledW) / 2f
            val top = margin + (maxH - scaledH) / 2f

            val scaledBitmap = Bitmap.createScaledBitmap(
                bitmap,
                scaledW.toInt().coerceAtLeast(1),
                scaledH.toInt().coerceAtLeast(1),
                true
            )

            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(scaledBitmap, left, top, paint)

            document.finishPage(page)

            // Save to shared documents/cache directory
            val outputDir = File(context.cacheDir, "generated_pdfs").apply { mkdirs() }
            val outputFile = File(outputDir, outputFileName)

            FileOutputStream(outputFile).use { out ->
                document.writeTo(out)
            }

            Log.d(TAG, "Generated PDF saved successfully to ${outputFile.absolutePath} (${outputFile.length()} bytes)")
            return@withContext outputFile
        } catch (e: Exception) {
            Log.e(TAG, "Error writing PDF document", e)
            null
        } finally {
            document.close()
        }
    }

    /**
     * Scans device Pictures and Download directories for the newest image (e.g. from Gemini or ChatGPT).
     */
    fun findLatestDownloadedImage(context: Context): File? {
        val candidateDirs = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            File(Environment.getExternalStorageDirectory(), "Pictures"),
            File(Environment.getExternalStorageDirectory(), "Download"),
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            context.cacheDir
        ).filterNotNull()

        var newestFile: File? = null
        var newestTime: Long = 0L

        for (dir in candidateDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            val files = dir.listFiles { file ->
                val name = file.name.lowercase()
                name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp")
            } ?: continue

            for (f in files) {
                if (f.lastModified() > newestTime) {
                    newestTime = f.lastModified()
                    newestFile = f
                }
            }
        }

        return newestFile
    }
}
