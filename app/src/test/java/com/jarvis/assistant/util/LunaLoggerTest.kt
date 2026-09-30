package com.jarvis.assistant.util

import org.junit.Assert.*
import org.junit.Test

class LunaLoggerTest {

    @Test
    fun testLevelHierarchy() {
        assertTrue(LunaLogger.Level.DEBUG.priority < LunaLogger.Level.INFO.priority)
        assertTrue(LunaLogger.Level.INFO.priority < LunaLogger.Level.WARN.priority)
        assertTrue(LunaLogger.Level.WARN.priority < LunaLogger.Level.ERROR.priority)
    }

    @Test
    fun testDefaultMinimumLevel() {
        assertEquals(LunaLogger.Level.INFO, LunaLogger.minimumLevel)
    }
}
