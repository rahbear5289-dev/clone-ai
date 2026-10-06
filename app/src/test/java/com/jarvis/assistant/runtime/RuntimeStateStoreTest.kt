package com.jarvis.assistant.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [RuntimeStateStore] — FR-RED-1, FR-RED-4, FR-RED-5.
 * Validates TTL eviction, context helpers, rate limiting, and latency metrics.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeStateStoreTest {

    @Before
    fun setup() {
        RuntimeStateStore.start()
    }

    @After
    fun tearDown() {
        RuntimeStateStore.shutdown()
    }

    @Test
    fun `set and get returns stored value`() {
        RuntimeStateStore.set("test:key", "hello_world")
        val result: String? = RuntimeStateStore.get("test:key")
        assertEquals("hello_world", result)
    }

    @Test
    fun `has returns false for missing key`() {
        assertFalse(RuntimeStateStore.has("nonexistent:key"))
    }

    @Test
    fun `has returns true for present key`() {
        RuntimeStateStore.set("present:key", 42)
        assertTrue(RuntimeStateStore.has("present:key"))
    }

    @Test
    fun `delete removes key`() {
        RuntimeStateStore.set("delete:key", "value")
        assertTrue(RuntimeStateStore.delete("delete:key"))
        assertFalse(RuntimeStateStore.has("delete:key"))
    }

    @Test
    fun `expired key is evicted on get`() {
        RuntimeStateStore.set("short:ttl:key", "expires_soon", ttlMs = 50L)
        assertNotNull(RuntimeStateStore.get<String>("short:ttl:key"))

        Thread.sleep(65)

        // After expiry, get should return null
        assertNull(RuntimeStateStore.get<String>("short:ttl:key"))
    }

    @Test
    fun `has returns false for expired key`() {
        RuntimeStateStore.set("expired:has:key", "value", ttlMs = 50L)
        Thread.sleep(65)
        assertFalse(RuntimeStateStore.has("expired:has:key"))
    }

    @Test
    fun `voice state helpers set and get correctly`() {
        RuntimeStateStore.setVoiceState("ACTIVE")
        assertEquals("ACTIVE", RuntimeStateStore.getVoiceState())

        RuntimeStateStore.setVoiceState("STANDBY")
        assertEquals("STANDBY", RuntimeStateStore.getVoiceState())

        RuntimeStateStore.setVoiceState("IDLE")
        assertEquals("IDLE", RuntimeStateStore.getVoiceState())
    }

    @Test
    fun `wake word state helpers toggle correctly`() {
        RuntimeStateStore.setWakeWordState(true)
        assertTrue(RuntimeStateStore.isWakeWordActive())

        RuntimeStateStore.setWakeWordState(false)
        assertFalse(RuntimeStateStore.isWakeWordActive())
    }

    @Test
    fun `screen context stored and retrieved correctly`() {
        val frame = ByteArray(1024) { it.toByte() }
        RuntimeStateStore.setScreenContext(frame, ttlMs = 5000L)
        val retrieved = RuntimeStateStore.getScreenContext()
        assertNotNull(retrieved)
        assertArrayEquals(frame, retrieved)
    }

    @Test
    fun `camera context stored and retrieved correctly`() {
        val frame = ByteArray(512) { it.toByte() }
        RuntimeStateStore.setCameraContext(frame, ttlMs = 5000L)
        val retrieved = RuntimeStateStore.getCameraContext()
        assertNotNull(retrieved)
        assertArrayEquals(frame, retrieved)
    }

    @Test
    fun `rate limiter allows requests under limit`() {
        val clientId = "user_rate_test_${System.nanoTime()}"
        assertTrue(RuntimeStateStore.checkRateLimit(clientId, maxRequests = 3, windowMs = 5000L))
        assertTrue(RuntimeStateStore.checkRateLimit(clientId, maxRequests = 3, windowMs = 5000L))
        assertTrue(RuntimeStateStore.checkRateLimit(clientId, maxRequests = 3, windowMs = 5000L))
    }

    @Test
    fun `rate limiter blocks requests over limit`() {
        val clientId = "user_rate_overload_${System.nanoTime()}"
        repeat(5) { RuntimeStateStore.checkRateLimit(clientId, maxRequests = 5, windowMs = 5000L) }
        val blocked = RuntimeStateStore.checkRateLimit(clientId, maxRequests = 5, windowMs = 5000L)
        assertFalse("Should be blocked after exceeding rate limit", blocked)
    }

    @Test
    fun `latency metrics track reads and writes`() {
        RuntimeStateStore.set("metric:key", "value")
        RuntimeStateStore.get<String>("metric:key")

        val metrics = RuntimeStateStore.getLatencyMetrics()
        assertTrue("Should track at least 1 write", metrics.totalWrites >= 1)
        assertTrue("Should track at least 1 read", metrics.totalReads >= 1)
        assertTrue("Avg read latency should be non-negative", metrics.avgReadLatencyMicros >= 0.0)
        assertTrue("Avg write latency should be non-negative", metrics.avgWriteLatencyMicros >= 0.0)
    }

    @Test
    fun `health check reports ACTIVE status`() {
        val health = RuntimeStateStore.health()
        assertTrue("RuntimeStateStore should be healthy", health.healthy)
        assertEquals("ACTIVE", health.status)
        assertEquals("RuntimeStateStore", health.component)
    }
}
