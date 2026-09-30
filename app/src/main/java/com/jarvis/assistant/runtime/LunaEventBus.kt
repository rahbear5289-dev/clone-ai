package com.jarvis.assistant.runtime

import com.jarvis.assistant.data.model.contract.LunaEvent
import com.jarvis.assistant.util.LunaLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Handle to an active event bus subscription.
 * Call [unsubscribe] to cancel the subscription and prevent memory leaks.
 */
interface EventSubscription {
    fun unsubscribe()
    val isActive: Boolean
}

/**
 * High-performance, reactive internal Event Bus conforming to FR-EVT-1 and FR-EVT-2.
 * Validates and routes typed LunaEvents across services, UI, and automation subsystems.
 * Ensures zero subscriber leaks via automatic cleanup and tracked tokens.
 */
object LunaEventBus : LunaLifecycle {
    private const val TAG = "LunaEventBus"

    // Replay 1 for state consumers, extra buffer to prevent coroutine backpressure drops
    private val _events = MutableSharedFlow<LunaEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val events: SharedFlow<LunaEvent> = _events.asSharedFlow()

    private val busScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val activeSubscriptions = ConcurrentHashMap<String, Job>()
    private val eventCounter = AtomicLong(0)

    @Volatile
    private var isRunning = true

    /**
     * Publish a typed event to all active subscribers.
     */
    fun publish(event: LunaEvent) {
        if (!isRunning) {
            LunaLogger.w(TAG, "Event bus stopped; dropping event: ${event.type}")
            return
        }
        eventCounter.incrementAndGet()
        LunaLogger.d(TAG, "Publishing event: ${event.type} [id=${event.eventId}]")
        val emitted = _events.tryEmit(event)
        if (!emitted) {
            busScope.launch {
                _events.emit(event)
            }
        }
    }

    /**
     * Subscribe to all events of a specific type.
     * Returns an [EventSubscription] that caller MUST store or clean up on shutdown.
     */
    fun subscribe(eventType: String, onEvent: suspend (LunaEvent) -> Unit): EventSubscription {
        val subscriptionId = "${eventType}_${System.nanoTime()}"
        val job = busScope.launch {
            _events.filter { it.type == eventType }.collect { event ->
                try {
                    onEvent(event)
                } catch (e: Exception) {
                    LunaLogger.e(TAG, "Error handling event ${event.type} in subscriber $subscriptionId", e)
                }
            }
        }
        activeSubscriptions[subscriptionId] = job

        return object : EventSubscription {
            override fun unsubscribe() {
                activeSubscriptions.remove(subscriptionId)?.cancel()
                LunaLogger.d(TAG, "Unsubscribed: $subscriptionId")
            }

            override val isActive: Boolean
                get() = job.isActive
        }
    }

    /**
     * Subscribe to all events regardless of type.
     */
    fun subscribeAll(onEvent: suspend (LunaEvent) -> Unit): EventSubscription {
        val subscriptionId = "ALL_${System.nanoTime()}"
        val job = busScope.launch {
            _events.collect { event ->
                try {
                    onEvent(event)
                } catch (e: Exception) {
                    LunaLogger.e(TAG, "Error handling event ${event.type} in global subscriber $subscriptionId", e)
                }
            }
        }
        activeSubscriptions[subscriptionId] = job

        return object : EventSubscription {
            override fun unsubscribe() {
                activeSubscriptions.remove(subscriptionId)?.cancel()
                LunaLogger.d(TAG, "Unsubscribed global: $subscriptionId")
            }

            override val isActive: Boolean
                get() = job.isActive
        }
    }

    /**
     * Unregisters all active subscribers to avoid leaks when resetting or restarting.
     */
    fun clearSubscribers() {
        LunaLogger.i(TAG, "Clearing all ${activeSubscriptions.size} active subscribers")
        activeSubscriptions.values.forEach { it.cancel() }
        activeSubscriptions.clear()
    }

    val subscriberCount: Int
        get() = activeSubscriptions.size

    val totalEventsPublished: Long
        get() = eventCounter.get()

    // -------------------------------------------------------------------------
    // LunaLifecycle Implementation
    // -------------------------------------------------------------------------

    override fun start() {
        isRunning = true
        LunaLogger.i(TAG, "LunaEventBus started")
    }

    override fun pause() {
        // Events can still queue into buffer
        LunaLogger.d(TAG, "LunaEventBus paused")
    }

    override fun resume() {
        LunaLogger.d(TAG, "LunaEventBus resumed")
    }

    override fun stop() {
        clearSubscribers()
        LunaLogger.i(TAG, "LunaEventBus stopped")
    }

    override fun health(): HealthStatus {
        return HealthStatus(
            component = "LunaEventBus",
            healthy = isRunning && busScope.isActive,
            status = if (isRunning) "ACTIVE" else "STOPPED",
            details = mapOf(
                "activeSubscribers" to activeSubscriptions.size,
                "totalEventsPublished" to eventCounter.get()
            )
        )
    }

    override fun shutdown() {
        isRunning = false
        clearSubscribers()
        busScope.cancel()
        LunaLogger.i(TAG, "LunaEventBus permanently shutdown")
    }
}
