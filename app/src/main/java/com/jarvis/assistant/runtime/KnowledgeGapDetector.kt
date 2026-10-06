package com.jarvis.assistant.runtime

import java.util.Locale

/**
 * Intelligent decision layer that determines whether a user's query requires
 * real-time external web research (Deep Research via Tavily) or can be answered
 * directly by the conversational AI model without web access.
 */
object KnowledgeGapDetector {

    data class Decision(
        val requiresResearch: Boolean,
        val reason: String,
        val suggestedSearchQuery: String
    )

    private val LIVE_TEMPORAL_PATTERNS = listOf(
        Regex("\\b(today|tonight|yesterday|this morning|this evening|this week|right now|currently|current|present)\\b"),
        Regex("\\b(aaj|aaj ka|kal ka|abhi|live|taza|hal hi mein|current|latest|naya)\\b"),
        Regex("\\b(latest|recent|newest|breaking|upcoming|ongoing)\\b")
    )

    private val SPORTS_PATTERNS = listOf(
        Regex("\\b(cricket|football|soccer|ipl|fifa|world cup|match|score|scores|wicket|goal|tournament|final|semifinal)\\b"),
        Regex("\\b(who won|koun jeeta|kon jita|match result|today's match|live score|score kya hai)\\b")
    )

    private val NEWS_AND_EVENTS_PATTERNS = listOf(
        Regex("\\b(news|breaking news|khabar|samachar|headline|event|happened|what happened)\\b"),
        Regex("\\b(spacex|nasa|isro|election|budget|stock market|sensex|nifty|crypto|bitcoin price)\\b")
    )

    private val LATEST_TECH_AND_RELEASES = listOf(
        Regex("\\b(latest (?:iphone|phone|laptop|model|release|update|version|feature|device|car))\\b"),
        Regex("\\b(launch date|released yet|price today|current price|kitne ka hai)\\b")
    )

    private val WEATHER_PATTERNS = listOf(
        Regex("\\b(weather|temperature|mausam|rain today|barish hogi|forecast)\\b")
    )

    // Evergreen / static knowledge patterns that must NOT trigger deep research
    private val EVERGREEN_PATTERNS = listOf(
        Regex("^(what is|what are|define|explain|meaning of)\\s+[a-z\\s]+$"),
        Regex("^(coding kya hai|java kya hai|inheritance kya hai|polymorphism kya hai)$"),
        Regex("^(capital of|who wrote|who discovered|formula for|how does)\\s+[a-z\\s]+$"),
        Regex("^(write a|compose a|tell me a joke|tell me a story|say something romantic|love message|poem)"),
        Regex("^(calculate|solve|\\d+\\s*[+\\-*/]\\s*\\d+)")
    )

    /**
     * Determines if a query requires deep research.
     */
    fun evaluate(query: String): Decision {
        val clean = query.trim().lowercase(Locale.ROOT)
        if (clean.length < 3) {
            return Decision(false, "Query too short", clean)
        }

        // Check if query is explicitly an evergreen general knowledge / creative / math prompt
        for (pattern in EVERGREEN_PATTERNS) {
            if (pattern.containsMatchIn(clean) && !containsExplicitCurrentCue(clean)) {
                return Decision(false, "Static general knowledge / creative query", clean)
            }
        }

        // 1. Explicit request for research / search
        if (clean.contains("deep research") || clean.contains("research karo") || clean.contains("search the web")) {
            val extracted = clean.replace(Regex("^(?:do |start )?deep research (?:on |about |for )?"), "")
                .replace("research karo", "")
                .trim()
            return Decision(true, "Explicit research request", extracted.ifBlank { clean })
        }

        // 2. Sports (matches, results, scores)
        val hasSports = SPORTS_PATTERNS.any { it.containsMatchIn(clean) }
        val hasTemporal = LIVE_TEMPORAL_PATTERNS.any { it.containsMatchIn(clean) }

        if (hasSports && (hasTemporal || clean.contains("who won") || clean.contains("koun jeeta") || clean.contains("live") || clean.contains("score"))) {
            return Decision(true, "Live sports and match results", clean)
        }

        // 3. Breaking news and real-time happenings
        if (NEWS_AND_EVENTS_PATTERNS.any { it.containsMatchIn(clean) } && (hasTemporal || clean.contains("latest") || clean.contains("recent"))) {
            return Decision(true, "Breaking news and current events", clean)
        }

        // 4. Latest tech releases and current prices
        if (LATEST_TECH_AND_RELEASES.any { it.containsMatchIn(clean) }) {
            return Decision(true, "Latest product releases and current market data", clean)
        }

        // 5. Current weather / forecast
        if (WEATHER_PATTERNS.any { it.containsMatchIn(clean) } && (hasTemporal || clean.contains("now") || clean.contains("forecast"))) {
            return Decision(true, "Live weather retrieval", clean)
        }

        // 6. General queries with explicit "latest", "today", "current"
        if (hasTemporal && (clean.contains("news") || clean.contains("update") || clean.contains("event") || clean.contains("result") || clean.contains("happen"))) {
            return Decision(true, "Temporal event query", clean)
        }

        return Decision(false, "Standard conversational knowledge", clean)
    }

    private fun containsExplicitCurrentCue(text: String): Boolean {
        return text.contains("today") || text.contains("latest") || text.contains("aaj") ||
                text.contains("now") || text.contains("recent") || text.contains("current")
    }
}
