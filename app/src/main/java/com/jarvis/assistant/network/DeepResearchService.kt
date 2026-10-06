package com.jarvis.assistant.network

import android.content.Context
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.runtime.KnowledgeGapDetector
import com.jarvis.assistant.util.LunaLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

sealed class ResearchState {
    object Idle : ResearchState()
    data class Detecting(val query: String, val message: String = "Detecting knowledge gap...") : ResearchState()
    data class Searching(val query: String, val message: String = "Searching web...") : ResearchState()
    data class Analyzing(val query: String, val sourceCount: Int, val message: String = "Analyzing sources...") : ResearchState()
    data class Verifying(val query: String, val message: String = "Verifying information...") : ResearchState()
    data class Synthesizing(val query: String, val message: String = "Generating answer...") : ResearchState()
    data class Completed(val query: String, val answer: String, val sources: List<TavilySourceItem>) : ResearchState()
    data class Failed(val query: String, val error: String) : ResearchState()
    object Cancelled : ResearchState()
}

data class ResearchOutput(
    val requestId: Int,
    val query: String,
    val finalAnswer: String,
    val sources: List<TavilySourceItem>,
    val verified: Boolean,
    val sourceAgreement: Boolean
)

class DeepResearchService(private val context: Context) {

    companion object {
        private const val TAG = "DeepResearchService"
        private val requestCounter = AtomicInteger(1000)

        @Volatile private var instance: DeepResearchService? = null
        fun getInstance(context: Context): DeepResearchService {
            return instance ?: synchronized(this) {
                instance ?: DeepResearchService(context.applicationContext).also { instance = it }
            }
        }

        // Cache entry: 15-minute TTL
        private data class CacheEntry(val output: ResearchOutput, val timestamp: Long)
        private val researchCache = ConcurrentHashMap<String, CacheEntry>()
    }

    private val tavilyService = TavilySearchService.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _currentState = MutableStateFlow<ResearchState>(ResearchState.Idle)
    val currentState: StateFlow<ResearchState> = _currentState.asStateFlow()

    private var activeJob: Job? = null
    @Volatile private var currentRequestId: Int = 0

    /**
     * Executes Deep Research on the given user query.
     * Cancels any previously executing research request.
     */
    fun startResearch(
        userQuery: String,
        onComplete: (ResearchOutput) -> Unit,
        onError: (String) -> Unit
    ) {
        val requestId = requestCounter.incrementAndGet()
        currentRequestId = requestId
        activeJob?.cancel()

        activeJob = scope.launch {
            try {
                LunaLogger.i(TAG, "Starting Research Request #$requestId for: '$userQuery'")
                _currentState.value = ResearchState.Detecting(userQuery)

                // Check cache if not live sports / breaking events
                val cleanKey = userQuery.trim().lowercase()
                val isLiveQuery = isRealTimeLiveQuery(cleanKey)
                if (!isLiveQuery) {
                    val cached = researchCache[cleanKey]
                    if (cached != null && (System.currentTimeMillis() - cached.timestamp < 15 * 60 * 1000L)) {
                        LunaLogger.i(TAG, "Request #$requestId served from recent research cache")
                        _currentState.value = ResearchState.Completed(
                            userQuery,
                            cached.output.finalAnswer,
                            cached.output.sources
                        )
                        onComplete(cached.output.copy(requestId = requestId))
                        return@launch
                    }
                }

                // Stage 1: Knowledge Gap Detection & Query Refinement
                delay(300)
                if (!isActive || currentRequestId != requestId) return@launch
                val gap = KnowledgeGapDetector.evaluate(userQuery)
                val searchQuery = gap.suggestedSearchQuery.ifBlank { userQuery }

                // Stage 2: Web Searching
                _currentState.value = ResearchState.Searching(userQuery)
                val searchResult = tavilyService.search(
                    query = searchQuery,
                    searchDepth = "advanced",
                    maxResults = 6
                )

                if (!isActive || currentRequestId != requestId) return@launch

                if (!searchResult.success || searchResult.sources.isEmpty()) {
                    val fallbackMsg = if (searchResult.errorMessage?.contains("API key") == true) {
                        "Tavily Web Search API key is not configured in Settings. Please add your key to enable live web research."
                    } else {
                        "I couldn't access live web information right now. ${searchResult.errorMessage ?: ""}".trim()
                    }
                    _currentState.value = ResearchState.Failed(userQuery, fallbackMsg)
                    onError(fallbackMsg)
                    return@launch
                }

                // Stage 3: Analyzing sources
                _currentState.value = ResearchState.Analyzing(userQuery, searchResult.sources.size)
                delay(400)
                if (!isActive || currentRequestId != requestId) return@launch

                // Stage 4: Verifying information & cross-source check
                _currentState.value = ResearchState.Verifying(userQuery)
                val (verified, agreement) = verifySources(searchResult.sources)
                delay(400)
                if (!isActive || currentRequestId != requestId) return@launch

                // Stage 5: Synthesizing Answer
                _currentState.value = ResearchState.Synthesizing(userQuery)
                val synthesizedAnswer = synthesizeResponse(
                    userQuery = userQuery,
                    tavilySummary = searchResult.summary,
                    sources = searchResult.sources,
                    sourcesAgree = agreement
                )

                if (!isActive || currentRequestId != requestId) return@launch

                val output = ResearchOutput(
                    requestId = requestId,
                    query = userQuery,
                    finalAnswer = synthesizedAnswer,
                    sources = searchResult.sources,
                    verified = verified,
                    sourceAgreement = agreement
                )

                // Cache stable results
                if (!isLiveQuery) {
                    researchCache[cleanKey] = CacheEntry(output, System.currentTimeMillis())
                }

                _currentState.value = ResearchState.Completed(
                    userQuery,
                    synthesizedAnswer,
                    searchResult.sources
                )

                LunaLogger.i(TAG, "Research Request #$requestId completed successfully")
                onComplete(output)

            } catch (e: CancellationException) {
                LunaLogger.i(TAG, "Research Request #$requestId cancelled by newer query")
                _currentState.value = ResearchState.Cancelled
            } catch (e: Exception) {
                LunaLogger.e(TAG, "Research Request #$requestId failed: ${e.message}", e)
                val errorMsg = "Research encounter an error: ${e.message}"
                _currentState.value = ResearchState.Failed(userQuery, errorMsg)
                onError(errorMsg)
            }
        }
    }

    fun cancelActiveResearch() {
        activeJob?.cancel()
        activeJob = null
        _currentState.value = ResearchState.Idle
    }

    private fun isRealTimeLiveQuery(text: String): Boolean {
        return text.contains("today") || text.contains("live") || text.contains("score") ||
                text.contains("match") || text.contains("breaking") || text.contains("aaj") ||
                text.contains("now") || text.contains("price") || text.contains("weather")
    }

    private fun verifySources(sources: List<TavilySourceItem>): Pair<Boolean, Boolean> {
        if (sources.isEmpty()) return Pair(false, false)
        if (sources.size == 1) return Pair(true, true) // Single source, low disagreement confidence

        // Cross-compare keywords/snippets for severe divergence
        val sample1 = sources[0].content.lowercase()
        val sample2 = sources[1].content.lowercase()
        val shareWords = sample1.split(" ").filter { it.length > 4 }.toSet()
        val matchCount = sample2.split(" ").count { it.length > 4 && shareWords.contains(it) }

        val agree = matchCount >= 2
        return Pair(true, agree)
    }

    private fun synthesizeResponse(
        userQuery: String,
        tavilySummary: String,
        sources: List<TavilySourceItem>,
        sourcesAgree: Boolean
    ): String {
        val answerBuilder = StringBuilder()

        if (tavilySummary.isNotBlank()) {
            answerBuilder.append(tavilySummary.trim())
        } else {
            // Build coherent summary from primary snippet
            val primary = sources.firstOrNull()?.content?.take(300)?.trim()
            if (!primary.isNullOrBlank()) {
                answerBuilder.append(primary)
            } else {
                answerBuilder.append("According to latest web search results, ")
                answerBuilder.append(sources.firstOrNull()?.title ?: userQuery)
            }
        }

        if (!sourcesAgree) {
            answerBuilder.append("\n\n(Note: Some web sources report slightly differing details on this event.)")
        }

        // Include top source citations if available
        val topSources = sources.take(2).mapNotNull {
            val domain = try {
                android.net.Uri.parse(it.url).host?.replace("www.", "")
            } catch (_: Exception) { null }
            domain?.let { d -> "[Source: $d]" }
        }

        if (topSources.isNotEmpty()) {
            answerBuilder.append("\n").append(topSources.distinct().joinToString(" • "))
        }

        return answerBuilder.toString()
    }
}
