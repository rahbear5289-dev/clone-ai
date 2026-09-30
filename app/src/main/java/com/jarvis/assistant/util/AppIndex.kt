package com.jarvis.assistant.util

import android.content.Context
import android.content.Intent
import java.util.Locale
import kotlin.math.abs

/**
 * A pre-built, in-memory index of every launchable app on the device.
 *
 * The index is built once (asynchronously, off the main thread, usually during
 * Application.onCreate) and kept for the lifetime of the process, so voice
 * commands like "open whatsapp" resolve in well under 300 ms. A full
 * PackageManager scan per command would blow the latency budget on devices
 * with hundreds of installed apps.
 */
object AppIndex {

    data class AppEntry(
        val packageName: String,
        val label: String,       // original display label
        val normalized: String,  // lowercased, punctuation removed
        val tokens: List<String>
    )

    @Volatile
    private var entries: List<AppEntry> = emptyList()

    @Volatile
    private var aliases: Map<String, String> = emptyMap()

    @Volatile
    private var byPackage: Map<String, AppEntry> = emptyMap()

    fun isReady(): Boolean = entries.isNotEmpty()

    fun allApps(): List<AppEntry> = entries

    fun entryFor(packageName: String): AppEntry? = byPackage[packageName]

    /** Kicks off the index build on a background thread. Safe to call repeatedly. */
    fun warmUpAsync(context: Context, aliasMap: Map<String, String> = emptyMap()) {
        if (entries.isNotEmpty()) return
        Thread {
            build(context, aliasMap)
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    /** Builds the index. Blocking; call from a background thread only. */
    fun build(context: Context, aliasMap: Map<String, String> = emptyMap()) {
        try {
            val pm = context.packageManager
            val launchable = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
            )
            val seen = HashSet<String>()
            val list = ArrayList<AppEntry>(launchable.size)
            for (info in launchable) {
                val pkg = info.activityInfo.packageName
                if (!seen.add(pkg)) continue
                val label = try {
                    info.loadLabel(pm)?.toString() ?: continue
                } catch (_: Exception) {
                    continue
                }
                if (label.isBlank()) continue
                list.add(
                    AppEntry(
                        packageName = pkg,
                        label = label,
                        normalized = normalize(label),
                        tokens = tokenize(label)
                    )
                )
            }
            entries = list
            byPackage = list.associateBy { it.packageName }
            aliases = aliasMap.mapKeys { normalize(it.key) }
        } catch (_: Exception) {
            // Keep whatever state we had; lookups will fall back to null.
        }
    }

    /**
     * Finds the best matching installed app for a spoken app name, or null.
     * Match order: alias -> exact normalized label -> package name ->
     * prefix match -> token containment -> bounded fuzzy distance
     * (tolerates speech-to-text mistakes like "whatsap" -> "WhatsApp").
     */
    fun find(spokenName: String): AppEntry? {
        if (entries.isEmpty()) return null
        val trimmed = spokenName.trim()
            .replace(Regex("^(?:the|my|please|app|application)\\s+"), "")
            .replace(Regex("\\s+(?:app|application|please|ko)$"), "")
            .trim()
        if (trimmed.isEmpty()) return null
        val query = normalize(trimmed)
        if (query.isEmpty()) return null

        aliases[query]?.let { pkg -> return byPackage[pkg] }

        entries.firstOrNull { it.normalized == query }?.let { return it }
        entries.firstOrNull { it.packageName.equals(trimmed, ignoreCase = true) }?.let { return it }

        entries.filter {
            it.normalized.startsWith(query) || it.normalized.replace(" ", "").startsWith(query.replace(" ", ""))
        }.minByOrNull { it.normalized.length }?.let { return it }

        val queryTokens = tokenize(trimmed).filter { it.length > 1 }
        if (queryTokens.isNotEmpty()) {
            entries.filter { e ->
                queryTokens.all { q -> e.tokens.any { it.startsWith(q) || q.startsWith(it) } }
            }.minByOrNull { it.normalized.length }?.let { return it }
        }

        // Fuzzy fallback only for reasonably long queries, tighter tolerance
        // for short ones so stray words don't match random apps.
        val maxDistance = if (query.length <= 4) 1 else 2
        var best: AppEntry? = null
        var bestDistance = maxDistance + 1
        for (e in entries) {
            if (abs(e.normalized.length - query.length) > maxDistance) continue
            val d = levenshteinBounded(query, e.normalized, maxDistance)
            if (d < bestDistance || (d == bestDistance && best != null && e.normalized.length < best.normalized.length)) {
                best = e
                bestDistance = d
            }
        }
        return best
    }

    fun normalize(s: String): String =
        s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9\\u0600-\\u06FF]"), "")

    fun tokenize(s: String): List<String> =
        s.lowercase(Locale.ROOT).split(Regex("[^a-z0-9\\u0600-\\u06FF]+")).filter { it.isNotBlank() }

    private fun levenshteinBounded(a: String, b: String, max: Int): Int {
        if (abs(a.length - b.length) > max) return max + 1
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > max) return max + 1
            System.arraycopy(cur, 0, prev, 0, prev.size)
        }
        return prev[b.length]
    }
}
