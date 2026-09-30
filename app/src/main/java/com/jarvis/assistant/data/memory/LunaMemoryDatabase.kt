package com.jarvis.assistant.data.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [MemoryRecord::class], version = 1, exportSchema = false)
abstract class LunaMemoryDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao

    companion object {   
        @Volatile private var instance: LunaMemoryDatabase? = null

        fun get(context: Context): LunaMemoryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                LunaMemoryDatabase::class.java,
                "luna_memory.db"
            ).build().also { instance = it }
        }
    }
}
