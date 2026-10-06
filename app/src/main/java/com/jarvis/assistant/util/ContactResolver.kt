package com.jarvis.assistant.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves spoken contact names ("mom", "Ali Khan") to phone numbers using
 * the device's contact book, with a process-lifetime cache so repeat lookups
 * stay inside the latency budget. Raw phone numbers spoken in a command pass
 * straight through without a database hit.
 */
object ContactResolver {

    data class ContactMatch(val id: String, val name: String, val number: String)

    private val nameCache = ConcurrentHashMap<String, ContactMatch>()
    @Volatile private var allContacts: List<ContactMatch>? = null

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun clearCache() {
        nameCache.clear()
        allContacts = null
    }

    /**
     * Resolves an exact phone number (from incoming call or SMS) to a ContactMatch.
     * Uses normalized digits and PhoneLookup/cached contacts.
     */
    fun findExact(context: Context, rawNumber: String): ContactMatch? {
        val clean = rawNumber.trim()
        if (clean.isBlank()) return null
        val digits = clean.filter { it.isDigit() }
        if (digits.length < 5) return null

        // 1. Check PhoneLookup URI if permission granted
        if (hasPermission(context)) {
            try {
                val lookupUri = android.net.Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    android.net.Uri.encode(clean)
                )
                context.contentResolver.query(
                    lookupUri,
                    arrayOf(
                        ContactsContract.PhoneLookup._ID,
                        ContactsContract.PhoneLookup.DISPLAY_NAME,
                        ContactsContract.PhoneLookup.NUMBER
                    ),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getString(0) ?: ""
                        val name = cursor.getString(1) ?: ""
                        val number = cursor.getString(2) ?: clean
                        if (name.isNotBlank()) {
                            return ContactMatch(id, name, number)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Fallback: match last 10 digits against loaded contacts
        val last10 = if (digits.length >= 10) digits.takeLast(10) else digits
        val contacts = loadAll(context)
        return contacts.firstOrNull { c ->
            val cDigits = c.number.filter { it.isDigit() }
            cDigits == digits || (last10.length >= 7 && cDigits.endsWith(last10))
        }
    }

    private fun loadAll(context: Context): List<ContactMatch> {
        allContacts?.let { return it }
        if (!hasPermission(context)) return emptyList()
        val list = ArrayList<ContactMatch>(128)
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.IS_PRIMARY
                ),
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.IS_PRIMARY + " DESC"
            )?.use { c: Cursor ->
                val seen = HashSet<String>()
                while (c.moveToNext()) {
                    val number = c.getString(2) ?: continue
                    val name = c.getString(1) ?: continue
                    if (seen.add(name.lowercase(Locale.ROOT) + "|" + number)) {
                        list.add(ContactMatch(c.getString(0) ?: "", name, number))
                    }
                }
            }
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
        allContacts = list
        return list
    }

    /**
     * Finds the best contact for a spoken name, or null when nothing plausible
     * exists. Spoken phone numbers are returned as a direct match.
     */
    fun find(context: Context, spokenTarget: String): ContactMatch? {
        val matches = findAll(context, spokenTarget)
        return matches.firstOrNull()
    }

    /**
     * Returns all potential matching contacts for disambiguation (e.g. 2 contacts named "Ahmed").
     */
    fun findAll(context: Context, spokenTarget: String): List<ContactMatch> {
        val target = spokenTarget.trim().replace(Regex("\\s+(\\d)"), "$1")
        if (target.isEmpty()) return emptyList()

        val digits = target.filter { it.isDigit() || it == '+' }
        if (digits.count { it.isDigit() } >= 7) {
            return listOf(ContactMatch("", target, digits))
        }

        nameCache[target.lowercase(Locale.ROOT)]?.let { return listOf(it) }

        val contacts = loadAll(context)
        if (contacts.isEmpty()) return emptyList()

        val q = normalizeName(target)
        if (q.isEmpty()) return emptyList()
        val qTokens = q.split(" ").filter { it.isNotBlank() }

        val scored = mutableListOf<Pair<ContactMatch, Int>>()
        for (c in contacts) {
            val n = normalizeName(c.name)
            val score = when {
                n == q -> 100
                n.startsWith(q) -> 80
                n.replace(" ", "").startsWith(q.replace(" ", "")) -> 75
                qTokens.isNotEmpty() && qTokens.all { t ->
                    n.split(" ").any { it.startsWith(t) || t.startsWith(it) }
                } -> 60
                else -> {
                    val d = levenshtein(q, n)
                    if (d <= 2 && q.length >= 4) 40 - d * 5 else 0
                }
            }
            if (score >= 50) {
                scored.add(c to score)
            }
        }

        val sorted = scored.sortedByDescending { it.second }.map { it.first }
        if (sorted.isNotEmpty()) {
            nameCache[target.lowercase(Locale.ROOT)] = sorted.first()
        }
        return sorted
    }

    fun normalizeName(s: String): String =
        s.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}0-9 ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun levenshtein(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            System.arraycopy(cur, 0, prev, 0, prev.size)
        }
        return prev[b.length]
    }
}
