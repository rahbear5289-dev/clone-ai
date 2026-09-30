package com.jarvis.assistant.data.memory

/** Durable, user-controlled memory. Sensitive credentials and payment data are rejected. */
class MemoryRepository(private val dao: MemoryDao) {
    suspend fun remember(content: String): Result<Long> {
        val value = content.trim().take(500)
        if (value.length < 2) return Result.failure(IllegalArgumentException("Memory is empty."))
        if (sensitivePattern.containsMatchIn(value)) {
            return Result.failure(IllegalArgumentException("I can't save passwords, payment details, or verification codes."))
        }
        val existing = dao.search(value, 1).firstOrNull()
        if (existing != null) return Result.success(existing.id)
        return Result.success(dao.insert(MemoryRecord(content = value)))
    }

    suspend fun recall(query: String): List<MemoryRecord> {
        val terms = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 2 }.distinct().take(6)
        val matches = terms.flatMap { dao.search(it, 5) }.distinctBy { it.id }
        return (matches.ifEmpty { dao.recent(5) }).take(5)
    }

    suspend fun forget(id: Long): Boolean = dao.delete(id) > 0
    suspend fun clear() = dao.clear()

    private companion object {
        val sensitivePattern = Regex(
            "(?i)(password|passcode|pin|cvv|card number|verification code|one[- ]time password|\\b\\d{12,19}\\b)"
        )
    }
}
