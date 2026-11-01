package com.orion.assistant.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

object WhatsAppDispatcher {
    private const val TAG = "WhatsAppDispatcher"
    const val WHATSAPP_PACKAGE = "com.whatsapp"
    const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"

    /**
     * Dispatches a PDF or image file to WhatsApp via native Android FileProvider and Intent.ACTION_SEND.
     * This bypasses UI touch fragility and hands off the file directly to WhatsApp's native share flow.
     */
    fun sendFileToWhatsApp(
        context: Context,
        file: File,
        mimeType: String = "application/pdf",
        caption: String? = null
    ): Boolean {
        return try {
            if (!file.exists()) {
                Log.e(TAG, "File to send does not exist: ${file.absolutePath}")
                return false
            }

            val fileUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, fileUri)
                if (!caption.isNullOrBlank()) {
                    putExtra(Intent.EXTRA_TEXT, caption)
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                // Prefer WhatsApp if installed
                val pm = context.packageManager
                val isWhatsAppInstalled = try {
                    pm.getPackageInfo(WHATSAPP_PACKAGE, 0)
                    true
                } catch (_: Exception) {
                    false
                }

                if (isWhatsAppInstalled) {
                    `package` = WHATSAPP_PACKAGE
                }
            }

            context.startActivity(shareIntent)
            Log.d(TAG, "Dispatched ${file.name} to WhatsApp via FileProvider")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch file to WhatsApp", e)
            false
        }
    }
}
