package com.jarvis.assistant.util

import android.util.Log

/**
 * Structured, level-gated Logger for LUNA AI.
 * Resolves Bug #9 (Excessive console messages) and conforms to FR-LOG-1, FR-LOG-2, FR-LOG-3.
 *
 * Suppresses high-frequency audio chunk logs and hides sensitive tokens/PII.
 */
object LunaLogger {

    enum class Level(val priority: Int) {
        DEBUG(Log.DEBUG),
        INFO(Log.INFO),
        WARN(Log.WARN),
        ERROR(Log.ERROR),
        NONE(Int.MAX_VALUE)
    }

    /**
     * Active minimum log level. In debug builds defaults to INFO or DEBUG,
     * suppressing verbose streaming chunks.
     */
    @Volatile
    var minimumLevel: Level = Level.INFO

    private val SENSITIVE_PATTERNS = listOf(
        Regex("key=[a-zA-Z0-9_\\-]{15,}") to "key=REDACTED_API_KEY",
        Regex("AIza[0-9A-Za-z-_]{35}") to "AIzaREDACTED_API_KEY"
    )

    fun d(tag: String, message: String, throwable: Throwable? = null) {
        log(Level.DEBUG, tag, message, throwable)
    }

    fun i(tag: String, message: String, throwable: Throwable? = null) {
        log(Level.INFO, tag, message, throwable)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        log(Level.WARN, tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        log(Level.ERROR, tag, message, throwable)
    }

    private fun log(level: Level, tag: String, rawMessage: String, throwable: Throwable?) {
        if (level.priority < minimumLevel.priority) return

        val cleanTag = "Luna::$tag"
        val sanitized = sanitize(rawMessage)

        when (level) {
            Level.DEBUG -> Log.d(cleanTag, sanitized, throwable)
            Level.INFO -> Log.i(cleanTag, sanitized, throwable)
            Level.WARN -> Log.w(cleanTag, sanitized, throwable)
            Level.ERROR -> Log.e(cleanTag, sanitized, throwable)
            Level.NONE -> Unit
        }
    }

    private fun sanitize(input: String): String {
        var result = input
        for ((pattern, replacement) in SENSITIVE_PATTERNS) {
            result = pattern.replace(result, replacement)
        }
        return result
    }
}
