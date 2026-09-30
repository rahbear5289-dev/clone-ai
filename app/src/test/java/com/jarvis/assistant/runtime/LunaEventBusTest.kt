package com.jarvis.assistant.runtime

import com.jarvis.assistant.data.model.contract.LunaEvent
import com.jarvis.assistant.data.model.contract.LunaEventTypes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [LunaEventBus] — FR-EVT-1 (typed events), FR-EVT-2 (no duplicate listeners after unsubscribe).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LunaEventBusTest {

    @Before
    fun setup() {
        LunaEventBus.start()
    }

    @After
    fun tearDown() {
        LunaEventBus.clearSubscribers()
    }

    @Test
    fun `publish and receive typed event`() = runTest(UnconfinedTestDispatcher()) {
        val received = mutableListOf<LunaEvent>()

        val subscription = LunaEventBus.subscribe(LunaEventTypes.VOICE_STARTED) { event ->
            received.add(event)
        }

        val event = LunaEvent(type = LunaEventTypes.VOICE_STARTED, payload = mapOf("source" to "test"))
        LunaEventBus.publish(event)

        delay(50)

        assertTrue("Should receive published event", received.isNotEmpty())
        assertEquals(LunaEventTypes.VOICE_STARTED, received.first().type)
        assertEquals("test", received.first().payload["source"])

        subscription.unsubscribe()
    }

    @Test
    fun `subscriber receives only its event type`() = runTest(UnconfinedTestDispatcher()) {
        val voiceEvents = mutableListOf<LunaEvent>()
        val toolEvents = mutableListOf<LunaEvent>()

        val sub1 = LunaEventBus.subscribe(LunaEventTypes.VOICE_STARTED) { voiceEvents.add(it) }
        val sub2 = LunaEventBus.subscribe(LunaEventTypes.TOOL_COMPLETED) { toolEvents.add(it) }

        LunaEventBus.publish(LunaEvent(type = LunaEventTypes.VOICE_STARTED))
        LunaEventBus.publish(LunaEvent(type = LunaEventTypes.TOOL_COMPLETED, payload = mapOf("function" to "open_app")))
        LunaEventBus.publish(LunaEvent(type = LunaEventTypes.WAKE_WORD_DETECTED))

        delay(50)

        assertEquals(1, voiceEvents.size)
        assertEquals(1, toolEvents.size)

        sub1.unsubscribe()
        sub2.unsubscribe()
    }

    @Test
    fun `unsubscribed listener does not receive events`() = runTest(UnconfinedTestDispatcher()) {
        val received = mutableListOf<LunaEvent>()
        val subscription = LunaEventBus.subscribe(LunaEventTypes.VOICE_STOPPED) { received.add(it) }

        subscription.unsubscribe()
        assertFalse("Subscription should be inactive after unsubscribe", subscription.isActive)

        LunaEventBus.publish(LunaEvent(type = LunaEventTypes.VOICE_STOPPED))
        delay(50)

        assertTrue("No events should be received after unsubscribe", received.isEmpty())
    }

    @Test
    fun `subscribeAll receives events of any type`() = runTest(UnconfinedTestDispatcher()) {
        val received = mutableListOf<LunaEvent>()
        val sub = LunaEventBus.subscribeAll { received.add(it) }

        LunaEventBus.publish(LunaEvent(type = LunaEventTypes.VOICE_STARTED))
        LunaEventBus.publish(LunaEvent(type = LunaEventTypes.TOOL_STARTED))
        LunaEventBus.publish(LunaEvent(type = LunaEventTypes.WAKE_WORD_DETECTED))

        delay(100)

        assertTrue("subscribeAll should receive multiple event types", received.size >= 3)

        sub.unsubscribe()
    }

    @Test
    fun `clearSubscribers removes all active listeners`() = runTest(UnconfinedTestDispatcher()) {
        LunaEventBus.subscribe(LunaEventTypes.AI_STARTED) { }
        LunaEventBus.subscribe(LunaEventTypes.AI_STOPPED) { }
        LunaEventBus.subscribe(LunaEventTypes.MEMORY_CREATED) { }

        assertTrue(LunaEventBus.subscriberCount > 0)
        LunaEventBus.clearSubscribers()
        assertEquals(0, LunaEventBus.subscriberCount)
    }

    @Test
    fun `health check reports ACTIVE status when running`() {
        val health = LunaEventBus.health()
        assertTrue("LunaEventBus should be healthy", health.healthy)
        assertEquals("ACTIVE", health.status)
        assertEquals("LunaEventBus", health.component)
    }

    @Test
    fun `event payload is preserved through publish and receive`() = runTest(UnconfinedTestDispatcher()) {
        val payload = mapOf("function" to "open_app", "callId" to "abc123", "success" to "true")
        val received = mutableListOf<LunaEvent>()
        val sub = LunaEventBus.subscribe(LunaEventTypes.TOOL_COMPLETED) { received.add(it) }

        LunaEventBus.publish(LunaEvent(type = LunaEventTypes.TOOL_COMPLETED, payload = payload))
        delay(50)

        assertEquals(1, received.size)
        assertEquals("open_app", received.first().payload["function"])
        assertEquals("abc123", received.first().payload["callId"])
        assertEquals("true", received.first().payload["success"])

        sub.unsubscribe()
    }
}
