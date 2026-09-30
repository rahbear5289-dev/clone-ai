package com.jarvis.assistant.data.model.contract

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ToolContractTest {

    @Test
    fun testToolResponseSuccessSerialization() {
        val response = ToolResponse.success(
            tool = "application_control",
            action = "open",
            target = "Chrome",
            spoken = "Chrome open ho gaya.",
            executionTimeMs = 42,
            verified = true,
            data = mapOf("package" to "com.android.chrome")
        )

        assertTrue(response.success)
        assertEquals("application_control", response.tool)
        assertEquals("open", response.action)
        assertEquals("Chrome", response.target)
        assertTrue(response.verified)
        assertEquals(42L, response.executionTimeMs)
        assertNull(response.error)

        val json = response.toJson()
        val jsonObject = JsonParser.parseString(json).asJsonObject

        assertEquals(true, jsonObject.get("success").asBoolean)
        assertEquals("application_control", jsonObject.get("tool").asString)
        assertEquals("open", jsonObject.get("action").asString)
        assertEquals(42, jsonObject.get("execution_time_ms").asInt)
        assertTrue(jsonObject.get("verified").asBoolean)
    }

    @Test
    fun testToolResponseFailureSerialization() {
        val response = ToolResponse.failure(
            tool = "application_control",
            code = ToolErrorCodes.APP_NOT_FOUND,
            message = "Application 'UnknownApp' was not found on this device.",
            spoken = "Is device par app nahi mili.",
            action = "retry",
            recoverable = true,
            executionTimeMs = 15
        )

        assertFalse(response.success)
        assertEquals("application_control", response.tool)
        assertFalse(response.verified)
        assertNotNull(response.error)
        assertEquals(ToolErrorCodes.APP_NOT_FOUND, response.error?.code)
        assertEquals("retry", response.error?.action)
        assertTrue(response.error?.recoverable == true)

        val json = response.toJson()
        val jsonObject = JsonParser.parseString(json).asJsonObject

        assertEquals(false, jsonObject.get("success").asBoolean)
        assertTrue(jsonObject.has("error"))
        val errorObj = jsonObject.getAsJsonObject("error")
        assertEquals(ToolErrorCodes.APP_NOT_FOUND, errorObj.get("code").asString)
        assertEquals("retry", errorObj.get("action").asString)
    }

    @Test
    fun testPermissionRequiredResponse() {
        val response = ToolResponse.permissionRequired(
            tool = "camera_vision",
            permissionName = "android.permission.CAMERA",
            spoken = "Camera permission is required."
        )

        assertFalse(response.success)
        assertEquals(ToolErrorCodes.PERMISSION_REQUIRED, response.error?.code)
        assertEquals("request_permission", response.error?.action)
        assertEquals("android.permission.CAMERA", response.error?.details?.get("permission"))
    }
}
