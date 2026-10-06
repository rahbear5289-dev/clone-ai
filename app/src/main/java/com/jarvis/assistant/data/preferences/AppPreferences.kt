package com.jarvis.assistant.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.data.model.GeminiConstants

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val PREF_NAME = "jarvis_prefs"
        private const val KEY_API_KEY = "key_api_key"
        private const val KEY_MODEL = "key_model"
        private const val KEY_VOICE = "key_voice"
        private const val KEY_PERSONALITY = "key_personality"
        private const val KEY_USER_NAME = "key_user_name"
        private const val KEY_MIC_MUTED = "key_mic_muted"
        private const val KEY_CHAT_HISTORY = "key_chat_history"
        private const val KEY_MEMORY_ENABLED = "key_memory_enabled"
        private const val KEY_SCREEN_CAPTURE_ENABLED = "key_screen_capture_enabled"
        private const val KEY_CAMERA_VISION_ENABLED = "key_camera_vision_enabled"
        private const val KEY_BG_LISTENING_ENABLED = "key_bg_listening_enabled"
        private const val KEY_NOTIF_ALERTS_ENABLED = "key_notif_alerts_enabled"
        private const val KEY_WAKE_WORD_ENABLED = "key_wake_word_enabled"
        private const val KEY_TAVILY_KEY = "key_tavily_api_key"
        private const val KEY_DEEP_RESEARCH_ENABLED = "key_deep_research_enabled"
        private const val KEY_AUTO_RESPONSE_ENABLED = "key_auto_response_enabled"
        private const val KEY_AUTO_RESPONSE_MSG = "key_auto_response_msg"
        private const val KEY_AUTO_RESPONSE_APPS = "key_auto_response_apps"
        private const val KEY_AUTO_RESPONSE_UNKNOWN = "key_auto_response_unknown"
        private const val KEY_AUTO_RESPONSE_KNOWN = "key_auto_response_known"
        private const val KEY_VOICE_NOTIF_READING = "key_voice_notif_reading"
        private const val KEY_CALLER_ANNOUNCE = "key_caller_announce"
        private const val KEY_HOME_ADDRESS = "key_home_address"
        private const val KEY_OFFICE_ADDRESS = "key_office_address"
    }

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    var tavilyApiKey: String
        get() = prefs.getString(KEY_TAVILY_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TAVILY_KEY, value.trim()).apply()

    var isDeepResearchEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEEP_RESEARCH_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_DEEP_RESEARCH_ENABLED, value).apply()

    var isAutoResponseEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RESPONSE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_RESPONSE_ENABLED, value).apply()

    var autoResponseMessage: String
        get() = prefs.getString(KEY_AUTO_RESPONSE_MSG, "Sir is currently sleeping.") ?: "Sir is currently sleeping."
        set(value) = prefs.edit().putString(KEY_AUTO_RESPONSE_MSG, value.trim()).apply()

    var autoResponseAllowedApps: Set<String>
        get() = prefs.getStringSet(KEY_AUTO_RESPONSE_APPS, setOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.google.android.apps.messaging")) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_AUTO_RESPONSE_APPS, value).apply()

    var autoResponseReplyUnknown: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RESPONSE_UNKNOWN, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_RESPONSE_UNKNOWN, value).apply()

    var autoResponseReplyKnown: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RESPONSE_KNOWN, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_RESPONSE_KNOWN, value).apply()

    var isVoiceNotificationReadingEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE_NOTIF_READING, false)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_NOTIF_READING, value).apply()

    var isCallerAnnouncementEnabled: Boolean
        get() = prefs.getBoolean(KEY_CALLER_ANNOUNCE, true)
        set(value) = prefs.edit().putBoolean(KEY_CALLER_ANNOUNCE, value).apply()

    var homeAddress: String
        get() = prefs.getString(KEY_HOME_ADDRESS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HOME_ADDRESS, value.trim()).apply()

    var officeAddress: String
        get() = prefs.getString(KEY_OFFICE_ADDRESS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OFFICE_ADDRESS, value.trim()).apply()

    var aiModel: String
        get() {
            val stored = prefs.getString(KEY_MODEL, GeminiConstants.DEFAULT_MODEL) ?: GeminiConstants.DEFAULT_MODEL
            return if (GeminiConstants.SUPPORTED_MODELS.contains(stored)) {
                stored
            } else {
                GeminiConstants.DEFAULT_MODEL
            }
        }
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    var voice: String
        get() = prefs.getString(KEY_VOICE, "Aoede") ?: "Aoede"
        set(value) = prefs.edit().putString(KEY_VOICE, value).apply()

    var personality: String
        get() = prefs.getString(KEY_PERSONALITY, GeminiConstants.PERSONALITY_ASSISTANT) ?: GeminiConstants.PERSONALITY_ASSISTANT
        set(value) = prefs.edit().putString(KEY_PERSONALITY, value).apply()

    var userName: String
        get() = prefs.getString(KEY_USER_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USER_NAME, value.trim()).apply()

    var isMicMuted: Boolean
        get() = prefs.getBoolean(KEY_MIC_MUTED, false)
        set(value) = prefs.edit().putBoolean(KEY_MIC_MUTED, value).apply()

    var isMemoryEnabled: Boolean
        get() = prefs.getBoolean(KEY_MEMORY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MEMORY_ENABLED, value).apply()

    var isScreenCaptureEnabled: Boolean
        get() = prefs.getBoolean(KEY_SCREEN_CAPTURE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SCREEN_CAPTURE_ENABLED, value).apply()

    var isCameraVisionEnabled: Boolean
        get() = prefs.getBoolean(KEY_CAMERA_VISION_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_CAMERA_VISION_ENABLED, value).apply()

    var isBackgroundListeningEnabled: Boolean
        get() = prefs.getBoolean(KEY_BG_LISTENING_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_BG_LISTENING_ENABLED, value).apply()

    var isNotificationAlertsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIF_ALERTS_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIF_ALERTS_ENABLED, value).apply()

    var isWakeWordEnabled: Boolean
        get() = prefs.getBoolean(KEY_WAKE_WORD_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_WAKE_WORD_ENABLED, value).apply()

    fun loadChatHistory(): List<ChatTurn> {
        val json = prefs.getString(KEY_CHAT_HISTORY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ChatTurn>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveChatHistory(history: List<ChatTurn>) {
        val json = gson.toJson(history)
        prefs.edit().putString(KEY_CHAT_HISTORY, json).apply()
    }

    fun clearChatHistory() {
        prefs.edit().remove(KEY_CHAT_HISTORY).apply()
    }
}
