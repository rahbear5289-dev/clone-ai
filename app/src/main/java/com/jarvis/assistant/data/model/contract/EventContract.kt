package com.jarvis.assistant.data.model.contract

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Standard typed events for the LUNA AI internal Event Bus.
 * Conforms to FR-EVT-1 and FR-JSON-1.
 */
object LunaEventTypes {
    const val USER_COMMAND = "user_command"
    const val VOICE_STARTED = "voice_started"
    const val VOICE_STOPPED = "voice_stopped"
    const val WAKE_WORD_DETECTED = "wake_word_detected"
    const val SCREEN_UPDATED = "screen_updated"
    const val CAMERA_UPDATED = "camera_updated"
    const val APP_OPENED = "app_opened"
    const val APP_CLOSED = "app_closed"
    const val WINDOW_CHANGED = "window_changed"
    const val TOOL_STARTED = "tool_started"
    const val TOOL_COMPLETED = "tool_completed"
    const val TOOL_FAILED = "tool_failed"
    const val MEMORY_CREATED = "memory_created"
    const val MEMORY_RETRIEVED = "memory_retrieved"
    const val AI_STARTED = "ai_started"
    const val AI_STOPPED = "ai_stopped"
    const val ERROR_OCCURRED = "error_occurred"
}

data class LunaEvent(
    @SerializedName("event")
    var event: String = "",

    @SerializedName("timestamp")
    val timestamp: String = currentIsoTimestamp(),

    @SerializedName("session_id")
    val sessionId: String = "session_${System.currentTimeMillis()}",

    @SerializedName("user_id")
    val userId: String = "default_user",

    @SerializedName("intent")
    val intent: String? = null,

    @SerializedName("command")
    val command: String? = null,

    @SerializedName("confidence")
    val confidence: Float = 1.0f,

    @SerializedName("requires_confirmation")
    val requiresConfirmation: Boolean = false,

    @SerializedName("payload")
    val payload: Map<String, Any?>? = null,

    var type: String = event,
    val eventId: String = "${if (event.isNotEmpty()) event else type}_${System.currentTimeMillis()}"
) {
    init {
        if (event.isEmpty() && type.isNotEmpty()) {
            event = type
        } else if (type.isEmpty() && event.isNotEmpty()) {
            type = event
        }
    }
    fun toJson(): String = gson.toJson(this)

    companion object {
        private val gson = Gson()

        private fun currentIsoTimestamp(): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            return sdf.format(Date())
        }

        fun create(
            event: String,
            sessionId: String,
            intent: String? = null,
            command: String? = null,
            confidence: Float = 1.0f,
            payload: Map<String, Any?>? = null
        ): LunaEvent = LunaEvent(
            event = event,
            sessionId = sessionId,
            intent = intent,
            command = command,
            confidence = confidence,
            payload = payload
        )
    }
}
