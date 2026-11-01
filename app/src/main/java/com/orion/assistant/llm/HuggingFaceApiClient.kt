package com.orion.assistant.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class HuggingFaceModel(
    val id: String, // e.g., "Qwen/Qwen2.5-1.5B-Instruct-GGUF"
    val author: String,
    val modelName: String,
    val downloads: Int,
    val likes: Int
)

data class GgufVariant(
    val filename: String, // e.g., "qwen2.5-1.5b-instruct-q4_k_m.gguf"
    val sizeBytes: Long,
    val downloadUrl: String
)

object HuggingFaceApiClient {

    private const val BASE_API = "https://huggingface.co/api/models"

    /**
     * Searches Hugging Face for GGUF models matching query.
     */
    suspend fun searchModels(query: String, limit: Int = 12): List<HuggingFaceModel> = withContext(Dispatchers.IO) {
        val results = mutableListOf<HuggingFaceModel>()
        var connection: HttpURLConnection? = null

        try {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            val targetUrl = "$BASE_API?search=$encodedQuery&filter=gguf&sort=downloads&direction=-1&limit=$limit"
            val url = URL(targetUrl)

            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("User-Agent", "Orion-Android-Assistant")
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(responseText)

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val id = obj.optString("id")
                    if (id.isNotBlank()) {
                        val author = if (obj.has("author")) obj.getString("author") else id.substringBefore("/")
                        val modelName = id.substringAfter("/")
                        val downloads = obj.optInt("downloads", 0)
                        val likes = obj.optInt("likes", 0)

                        results.add(
                            HuggingFaceModel(
                                id = id,
                                author = author,
                                modelName = modelName,
                                downloads = downloads,
                                likes = likes
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
            // Returns empty list on network or parse error
        } finally {
            connection?.disconnect()
        }

        return@withContext results
    }

    /**
     * Inspects a specific model repository and lists all available .gguf variants with sizes.
     */
    suspend fun fetchGgufVariants(repoId: String): List<GgufVariant> = withContext(Dispatchers.IO) {
        val variants = mutableListOf<GgufVariant>()
        var connection: HttpURLConnection? = null

        try {
            val targetUrl = "$BASE_API/$repoId"
            val url = URL(targetUrl)

            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("User-Agent", "Orion-Android-Assistant")
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonObj = JSONObject(responseText)

                if (jsonObj.has("siblings")) {
                    val siblings = jsonObj.getJSONArray("siblings")
                    for (i in 0 until siblings.length()) {
                        val fileObj = siblings.getJSONObject(i)
                        val rfilename = fileObj.optString("rfilename", "")

                        if (rfilename.endsWith(".gguf", ignoreCase = true)) {
                            val downloadUrl = "https://huggingface.co/$repoId/resolve/main/$rfilename"
                            val sizeBytes = fileObj.optLong("size", 0L)

                            variants.add(
                                GgufVariant(
                                    filename = rfilename,
                                    sizeBytes = sizeBytes,
                                    downloadUrl = downloadUrl
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Return collected variants
        } finally {
            connection?.disconnect()
        }

        // Sort so Q4_K_M or smaller quantized models appear first
        return@withContext variants.sortedBy { it.sizeBytes }
    }
}
