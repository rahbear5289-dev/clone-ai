package com.jarvis.assistant.data.model.contract

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class EventContractTest {

    @Test
    fun testEventCreationAndSerialization() {
        val event = LunaEvent.create(
            event = LunaEventTypes.USER_COMMAND,
            sessionId = "sess_test_123",
            intent = "open_application",
            command = "Open Chrome",
            confidence = 0.98f,
            payload = mapOf("target" to "Chrome")
        )

        assertEquals(LunaEventTypes.USER_COMMAND, event.event)
        assertEquals("sess_test_123", event.sessionId)
        assertEquals("open_application", event.intent)
        assertEquals("Open Chrome", event.command)
        assertEquals(0.98f, event.confidence, 0.001f)
        assertNotNull(event.timestamp)

        val json = event.toJson()
        val jsonObject = JsonParser.parseString(json).asJsonObject

        assertEquals("user_command", jsonObject.get("event").asString)
        assertEquals("sess_test_123", jsonObject.get("session_id").asString)
        assertEquals("open_application", jsonObject.get("intent").asString)
    }
}
