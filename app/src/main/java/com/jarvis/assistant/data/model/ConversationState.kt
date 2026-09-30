package com.jarvis.assistant.data.model

enum class ConversationState(val displayName: String, val orbKey: String) {
    IDLE("IDLE", "idle"),
    LISTENING("LISTENING", "listening"),
    THINKING("THINKING", "thinking"),
    SPEAKING("SPEAKING", "speaking"),
    EXECUTING("EXECUTING", "thinking"),
    SCREEN_VIEWING("SCREEN VIEWING", "listening"),
    CALL_ACTIVE("CALL ACTIVE", "speaking"),
    MEDIA_PLAYING("MEDIA PLAYING", "idle"),
    BACKGROUND("BACKGROUND", "idle"),
    ERROR("ERROR", "idle"),
    PERMISSION_REQUIRED("PERMISSION REQUIRED", "idle")
}
