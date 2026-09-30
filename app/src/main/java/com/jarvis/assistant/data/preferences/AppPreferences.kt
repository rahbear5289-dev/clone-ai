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
    }

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

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
