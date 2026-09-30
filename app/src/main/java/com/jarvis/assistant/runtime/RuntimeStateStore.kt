package com.jarvis.assistant.runtime

import com.jarvis.assistant.util.LunaLogger
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.measureNanoTime

/**
 * Entry in the runtime state cache with expiration timestamp.
 */
data class CacheEntry<T>(
    val value: T,
    val expiresAt: Long = Long.MAX_VALUE,
    val createdAt: Long = System.currentTimeMillis()
) {
    val isExpired: Boolean
        get() = expiresAt != Long.MAX_VALUE && System.currentTimeMillis() > expiresAt
}

/**
 * Performance metrics for runtime store operations (FR-RED-5).
 */
data class StoreLatencyMetrics(
    val totalReads: Long,
    val totalWrites: Long,
    val avgReadLatencyMicros: Double,
    val avgWriteLatencyMicros: Double,
    val maxLatencyMicros: Long
)

/**
 * Fast runtime state store conforming to FR-RED-1, FR-RED-4, and FR-RED-5.
 * Handles temporary context (camera/screen frames with TTL), active sessions,
 * voice and wake-word states, and rate limiting counters.
 */
object RuntimeStateStore : LunaLifecycle {
    private const val TAG = "RuntimeStateStore"

    private val storeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val memoryStore = ConcurrentHashMap<String, CacheEntry<Any>>()

    // Metrics tracking
    private val totalReads = AtomicLong(0)
    private val totalWrites = AtomicLong(0)
    private val totalReadLatencyNanos = AtomicLong(0)
    private val totalWriteLatencyNanos = AtomicLong(0)
    private val maxLatencyNanos = AtomicLong(0)

    private var cleanupJob: Job? = null

    @Volatile
    private var isRunning = true

    init {
        start()
    }

    /**
     * Store a value with an optional TTL in milliseconds.
     * Conforms to FR-RED-4 (TTLs on temporary keys).
     */
    fun <T : Any> set(key: String, value: T, ttlMs: Long = 0L) {
        if (!isRunning) return

        val elapsed = measureNanoTime {
            val expiresAt = if (ttlMs > 0) System.currentTimeMillis() + ttlMs else Long.MAX_VALUE
            memoryStore[key] = CacheEntry(value = value, expiresAt = expiresAt)
        }

        recordWriteMetric(elapsed)
    }

    /**
     * Retrieve a value if it exists and has not expired.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: String): T? {
        if (!isRunning) return null

        var result: T? = null
        val elapsed = measureNanoTime {
            val entry = memoryStore[key]
            if (entry != null) {
                if (entry.isExpired) {
                    memoryStore.remove(key)
                } else {
                    result = entry.value as? T
                }
            }
        }

        recordReadMetric(elapsed)
        return result
    }

    /**
     * Delete a key immediately.
     */
    fun delete(key: String): Boolean {
        return memoryStore.remove(key) != null
    }

    /**
     * Check if key exists and is fresh.
     */
    fun has(key: String): Boolean {
        val entry = memoryStore[key] ?: return false
        if (entry.isExpired) {
            memoryStore.remove(key)
            return false
        }
        return true
    }

    // -------------------------------------------------------------------------
    // Context & Session Convenience Helpers (FR-RED-1 & FR-RED-4)
    // -------------------------------------------------------------------------

    /**
     * Cache fresh screen context with short TTL (e.g. 15-30 seconds).
     */
    fun setScreenContext(frameData: ByteArray, ttlMs: Long = 20_000L) {
        set("context:screen:latest", frameData, ttlMs)
        set("context:screen:timestamp", System.currentTimeMillis(), ttlMs)
    }

    fun getScreenContext(): ByteArray? = get("context:screen:latest")

    /**
     * Cache fresh camera context with short TTL (e.g. 15-30 seconds).
     */
    fun setCameraContext(frameData: ByteArray, ttlMs: Long = 20_000L) {
        set("context:camera:latest", frameData, ttlMs)
        set("context:camera:timestamp", System.currentTimeMillis(), ttlMs)
    }

    fun getCameraContext(): ByteArray? = get("context:camera:latest")

    /**
     * Update active conversation session state.
     */
    fun setSessionState(sessionId: String, stateMap: Map<String, Any>, ttlMs: Long = 3600_000L) {
        set("session:$sessionId:state", stateMap, ttlMs)
    }

    fun getSessionState(sessionId: String): Map<String, Any>? = get("session:$sessionId:state")

    /**
     * Voice state machine cache.
     */
    fun setVoiceState(state: String) {
        set("state:voice", state)
    }

    fun getVoiceState(): String = get("state:voice") ?: "IDLE"

    /**
     * Wake word state cache.
     */
    fun setWakeWordState(active: Boolean) {
        set("state:wakeword", active)
    }

    fun isWakeWordActive(): Boolean = get("state:wakeword") ?: false

    /**
     * Rate limiter sliding window check. Returns true if request is allowed.
     */
    fun checkRateLimit(clientId: String, maxRequests: Int, windowMs: Long): Boolean {
        val now = System.currentTimeMillis()
        val key = "ratelimit:$clientId"
        val timestamps: MutableList<Long> = get(key) ?: mutableListOf()

        // Prune older than window
        timestamps.removeAll { it < now - windowMs }

        return if (timestamps.size < maxRequests) {
            timestamps.add(now)
            set(key, timestamps, windowMs)
            true
        } else {
            false
        }
    }

    // -------------------------------------------------------------------------
    // Metrics & Latency (FR-RED-5)
    // -------------------------------------------------------------------------

    private fun recordReadMetric(nanos: Long) {
        totalReads.incrementAndGet()
        totalReadLatencyNanos.addAndGet(nanos)
        updateMaxLatency(nanos)
    }

    private fun recordWriteMetric(nanos: Long) {
        totalWrites.incrementAndGet()
        totalWriteLatencyNanos.addAndGet(nanos)
        updateMaxLatency(nanos)
    }

    private fun updateMaxLatency(nanos: Long) {
        var current = maxLatencyNanos.get()
        while (nanos > current) {
            if (maxLatencyNanos.compareAndSet(current, nanos)) break
            current = maxLatencyNanos.get()
        }
    }

    fun getLatencyMetrics(): StoreLatencyMetrics {
        val rCount = totalReads.get().coerceAtLeast(1)
        val wCount = totalWrites.get().coerceAtLeast(1)
        return StoreLatencyMetrics(
            totalReads = totalReads.get(),
            totalWrites = totalWrites.get(),
            avgReadLatencyMicros = (totalReadLatencyNanos.get() / rCount) / 1000.0,
            avgWriteLatencyMicros = (totalWriteLatencyNanos.get() / wCount) / 1000.0,
            maxLatencyMicros = maxLatencyNanos.get() / 1000
        )
    }

    // -------------------------------------------------------------------------
    // Cleanup Worker
    // -------------------------------------------------------------------------

    private fun startEvictionWorker() {
        cleanupJob?.cancel()
        cleanupJob = storeScope.launch {
            while (isActive && isRunning) {
                delay(10_000L) // Evict expired items every 10 seconds
                try {
                    val now = System.currentTimeMillis()
                    val expiredKeys = memoryStore.entries
                        .filter { it.value.isExpired }
                        .map { it.key }
                    expiredKeys.forEach { memoryStore.remove(it) }
                    if (expiredKeys.isNotEmpty()) {
                        LunaLogger.d(TAG, "Evicted ${expiredKeys.size} expired keys from runtime store")
                    }
                } catch (e: Exception) {
                    LunaLogger.w(TAG, "Error in cache eviction: ${e.message}")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // LunaLifecycle Implementation
    // -------------------------------------------------------------------------

    override fun start() {
        isRunning = true
        startEvictionWorker()
        LunaLogger.i(TAG, "RuntimeStateStore started with TTL eviction")
    }

    override fun pause() {
        LunaLogger.d(TAG, "RuntimeStateStore paused")
    }

    override fun resume() {
        LunaLogger.d(TAG, "RuntimeStateStore resumed")
    }

    override fun stop() {
        cleanupJob?.cancel()
        LunaLogger.i(TAG, "RuntimeStateStore stopped")
    }

    override fun health(): HealthStatus {
        val metrics = getLatencyMetrics()
        return HealthStatus(
            component = "RuntimeStateStore",
            healthy = isRunning,
            status = if (isRunning) "ACTIVE" else "STOPPED",
            details = mapOf(
                "keyCount" to memoryStore.size,
                "reads" to metrics.totalReads,
                "writes" to metrics.totalWrites,
                "avgReadMicros" to metrics.avgReadLatencyMicros,
                "avgWriteMicros" to metrics.avgWriteLatencyMicros
            )
        )
    }

    override fun shutdown() {
        isRunning = false
        cleanupJob?.cancel()
        memoryStore.clear()
        storeScope.cancel()
        LunaLogger.i(TAG, "RuntimeStateStore permanently shutdown")
    }
}
