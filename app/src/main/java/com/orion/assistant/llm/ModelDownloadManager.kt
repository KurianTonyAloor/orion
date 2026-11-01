package com.orion.assistant.llm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(
        val modelName: String,
        val progressPercent: Float,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : DownloadState()
    data class Completed(val modelName: String) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

class ModelDownloadManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "orion_model_prefs"
        private const val KEY_ACTIVE_MODEL = "active_model_filename"
        private const val KEY_NAVIGATOR_MODEL = "active_navigator_model_filename"

        const val DEFAULT_PRIMARY_MODEL = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
        const val DEFAULT_NAVIGATOR_MODEL = "qwen2.5-0.5b-instruct-q4_k_m.gguf"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private var isCancelled = false

    val modelsDir: File
        get() = File(context.filesDir, "models").apply { if (!exists()) mkdirs() }

    var activeModelFilename: String
        get() {
            val saved = prefs.getString(KEY_ACTIVE_MODEL, null)
            if (!saved.isNullOrBlank() && File(modelsDir, saved).exists()) {
                return saved
            }
            val existing = listDownloadedModels().firstOrNull()
            return existing?.name ?: DEFAULT_PRIMARY_MODEL
        }
        set(value) {
            prefs.edit().putString(KEY_ACTIVE_MODEL, value).apply()
        }

    var activeNavigatorModelFilename: String
        get() {
            val saved = prefs.getString(KEY_NAVIGATOR_MODEL, null)
            if (!saved.isNullOrBlank() && File(modelsDir, saved).exists()) {
                return saved
            }
            // Check if there is an existing 0.5b or lightweight model
            val existing05B = listDownloadedModels().find { it.name.contains("0.5b", ignoreCase = true) }
            return existing05B?.name ?: activeModelFilename
        }
        set(value) {
            prefs.edit().putString(KEY_NAVIGATOR_MODEL, value).apply()
        }

    val activeModelFile: File
        get() = File(modelsDir, activeModelFilename)

    val activeNavigatorModelFile: File
        get() = File(modelsDir, activeNavigatorModelFilename)

    fun isModelDownloaded(role: ModelRole = ModelRole.PRIMARY_REASONER): Boolean {
        return when (role) {
            ModelRole.PRIMARY_REASONER -> listDownloadedModels().isNotEmpty()
            ModelRole.SCREEN_NAVIGATOR -> activeNavigatorModelFile.exists() && activeNavigatorModelFile.length() > 10_000_000L
        }
    }

    fun listDownloadedModels(): List<File> {
        return modelsDir.listFiles { file ->
            file.extension.equals("gguf", ignoreCase = true) && file.length() > 10_000_000L
        }?.toList() ?: emptyList()
    }

    suspend fun downloadModel(
        downloadUrl: String,
        filename: String,
        expectedSizeBytes: Long = 0L,
        role: ModelRole = ModelRole.PRIMARY_REASONER
    ): Boolean = withContext(Dispatchers.IO) {
        val targetFile = File(modelsDir, filename)
        if (targetFile.exists() && targetFile.length() > 50_000_000L) {
            if (role == ModelRole.SCREEN_NAVIGATOR) {
                activeNavigatorModelFilename = filename
            } else {
                activeModelFilename = filename
            }
            _downloadState.value = DownloadState.Completed(filename)
            return@withContext true
        }

        isCancelled = false
        _downloadState.value = DownloadState.Downloading(filename, 0f, 0L, expectedSizeBytes)

        val tempFile = File(modelsDir, "$filename.tmp")
        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            val url = URL(downloadUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30000
                readTimeout = 30000
                setRequestProperty("User-Agent", "Orion-Android-Assistant")
            }

            var responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                responseCode == 307 || responseCode == 308) {
                val newUrl = connection.getHeaderField("Location")
                connection.disconnect()
                connection = (URL(newUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30000
                    readTimeout = 30000
                }
                responseCode = connection.responseCode
            }

            if (responseCode !in 200..299) {
                _downloadState.value = DownloadState.Error("Server returned HTTP code $responseCode")
                return@withContext false
            }

            val contentLength = connection.contentLengthLong.let {
                if (it > 0) it else expectedSizeBytes
            }

            inputStream = connection.inputStream
            outputStream = FileOutputStream(tempFile)

            val buffer = ByteArray(64 * 1024)
            var totalRead = 0L
            var bytesRead: Int
            var lastUpdate = System.currentTimeMillis()

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (isCancelled) {
                    tempFile.delete()
                    _downloadState.value = DownloadState.Idle
                    return@withContext false
                }

                outputStream.write(buffer, 0, bytesRead)
                totalRead += bytesRead

                val now = System.currentTimeMillis()
                if (now - lastUpdate > 300 || totalRead == contentLength) {
                    val percent = if (contentLength > 0) (totalRead.toFloat() / contentLength.toFloat()) else 0f
                    _downloadState.value = DownloadState.Downloading(
                        modelName = filename,
                        progressPercent = percent.coerceIn(0f, 1f),
                        downloadedBytes = totalRead,
                        totalBytes = contentLength
                    )
                    lastUpdate = now
                }
            }

            outputStream.flush()
            outputStream.close()
            outputStream = null

            if (tempFile.renameTo(targetFile)) {
                if (role == ModelRole.SCREEN_NAVIGATOR) {
                    activeNavigatorModelFilename = filename
                } else {
                    activeModelFilename = filename
                }
                _downloadState.value = DownloadState.Completed(filename)
                return@withContext true
            } else {
                _downloadState.value = DownloadState.Error("Failed to rename temporary file to $filename")
                return@withContext false
            }

        } catch (e: Exception) {
            tempFile.delete()
            _downloadState.value = DownloadState.Error(e.localizedMessage ?: "Network connection error")
            return@withContext false
        } finally {
            try { outputStream?.close() } catch (_: Exception) {}
            try { inputStream?.close() } catch (_: Exception) {}
            try { connection?.disconnect() } catch (_: Exception) {}
        }
    }

    fun cancelDownload() {
        isCancelled = true
        _downloadState.value = DownloadState.Idle
    }
}
