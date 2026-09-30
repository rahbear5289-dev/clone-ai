package com.jarvis.assistant.data.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface MemoryDao {
    @Insert
    suspend fun insert(record: MemoryRecord): Long

    @Query("SELECT * FROM memories WHERE content LIKE '%' || :query || '%' COLLATE NOCASE ORDER BY createdAt DESC LIMIT :limit")
    suspend fun search(query: String, limit: Int): List<MemoryRecord>

    @Query("SELECT * FROM memories ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<MemoryRecord>

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("DELETE FROM memories")
    suspend fun clear()
}
