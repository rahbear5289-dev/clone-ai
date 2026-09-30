package com.jarvis.assistant.runtime

/**
 * Standard lifecycle interface for all Luna services and components.
 * Conforms to PRD Section 8.20 (FR-BG-4).
 */
interface LunaLifecycle {
    /**
     * Start or initialize the service/component.
     */
    fun start()

    /**
     * Temporarily pause active operations (e.g. during screen-off, incoming call).
     */
    fun pause()

    /**
     * Resume operations after pause.
     */
    fun resume()

    /**
     * Stop active execution without full destruction.
     */
    fun stop()

    /**
     * Check component health and return diagnostic status.
     */
    fun health(): HealthStatus

    /**
     * Permanently shut down and release all resources (connections, hardware, locks, listeners).
     */
    fun shutdown()
}

/**
 * Standard health status report for any Luna runtime component.
 */
data class HealthStatus(
    val component: String,
    val healthy: Boolean,
    val status: String,
    val details: Map<String, Any> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)
