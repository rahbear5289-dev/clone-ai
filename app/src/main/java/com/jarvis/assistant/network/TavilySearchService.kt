package com.jarvis.assistant.network

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.util.LunaLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class TavilySourceItem(
    @SerializedName("title") val title: String,
    @SerializedName("url") val url: String,
    @SerializedName("content") val content: String,
    @SerializedName("score") val score: Double = 0.0
)

data class TavilySearchResponse(
    @SerializedName("query") val query: String,
    @SerializedName("answer") val answer: String?,
    @SerializedName("results") val results: List<TavilySourceItem>?
)

data class TavilySearchResult(
    val query: String,
    val summary: String,
    val sources: List<TavilySourceItem>,
    val success: Boolean,
    val errorMessage: String? = null
)

class TavilySearchService(private val context: Context) {

    companion object {
        private const val TAG = "TavilySearchService"
        private const val API_ENDPOINT = "https://api.tavily.com/search"

        @Volatile private var instance: TavilySearchService? = null
        fun getInstance(context: Context): TavilySearchService {
            return instance ?: synchronized(this) {
                instance ?: TavilySearchService(context.applicationContext).also { instance = it }
            }
        }
    }

    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun getApiKey(): String {
        return (context.applicationContext as? JarvisApp)?.preferences?.tavilyApiKey?.trim() ?: ""
    }

    /**
     * Executes web research query via Tavily API.
     */
    suspend fun search(
        query: String,
        searchDepth: String = "basic",
        maxResults: Int = 5
    ): TavilySearchResult = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            return@withContext TavilySearchResult(
                query = query,
                summary = "",
                sources = emptyList(),
                success = false,
                errorMessage = "Tavily API key not configured in Settings."
            )
        }

        try {
            val payload = mapOf(
                "api_key" to apiKey,
                "query" to query,
                "search_depth" to searchDepth,
                "include_answer" to true,
                "max_results" to maxResults
            )

            val jsonBody = gson.toJson(payload)
            val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(API_ENDPOINT)
                .post(requestBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val code = response.code
                    LunaLogger.e(TAG, "Tavily request failed with HTTP code $code")
                    return@withContext TavilySearchResult(
                        query = query,
                        summary = "",
                        sources = emptyList(),
                        success = false,
                        errorMessage = "Tavily server returned error code $code"
                    )
                }

                val parsed = gson.fromJson(bodyStr, TavilySearchResponse::class.java)
                val rawSources = parsed.results ?: emptyList()

                // Filter duplicates and low-relevance results
                val seenUrls = HashSet<String>()
                val filteredSources = mutableListOf<TavilySourceItem>()
                for (item in rawSources) {
                    val cleanUrl = item.url.trim().lowercase()
                    if (cleanUrl.isNotEmpty() && !seenUrls.contains(cleanUrl) && item.content.isNotBlank()) {
                        seenUrls.add(cleanUrl)
                        filteredSources.add(item)
                    }
                }

                return@withContext TavilySearchResult(
                    query = query,
                    summary = parsed.answer ?: "",
                    sources = filteredSources,
                    success = true
                )
            }
        } catch (e: Exception) {
            LunaLogger.e(TAG, "Network error during Tavily search: ${e.message}", e)
            return@withContext TavilySearchResult(
                query = query,
                summary = "",
                sources = emptyList(),
                success = false,
                errorMessage = "Network or timeout error while searching: ${e.message}"
            )
        }
    }

    /**
     * Tests connectivity to Tavily API using provided key.
     */
    suspend fun testConnection(apiKey: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("API Key cannot be blank"))
        }

        try {
            val payload = mapOf(
                "api_key" to cleanKey,
                "query" to "test",
                "max_results" to 1
            )
            val jsonBody = gson.toJson(payload)
            val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(API_ENDPOINT)
                .post(requestBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
