package com.jarvis.assistant.runtime

import com.jarvis.assistant.util.DeviceCommand
import com.jarvis.assistant.util.VoiceCommandParser
import java.util.Locale

/**
 * High-level intent router that categorizes user voice input / text queries:
 *
 *               USER INPUT
 *                   ↓
 *             INTENT ROUTER
 *                   ↓
 *  ┌────────────────┼────────────────┐
 *  ↓                ↓                ↓
 * NORMAL AI    DEEP RESEARCH       ACTION
 * (Chat/General) (Tavily Search)  (Maps, Alarm, AutoResponse, Calls, etc.)
 */
object SmartIntentRouter {

    enum class UserIntent {
        CHAT,
        RESEARCH,
        NAVIGATION,
        ALARM,
        AUTO_RESPONSE,
        CALL,
        NOTIFICATION,
        WEB_AUTOMATION,
        YOUTUBE,
        SETTINGS,
        PERMISSION,
        SYSTEM_ACTION
    }

    data class IntentResolution(
        val intent: UserIntent,
        val rawQuery: String,
        val parsedCommand: DeviceCommand? = null,
        val parameters: Map<String, String> = emptyMap()
    )

    fun route(rawQuery: String): IntentResolution {
        val query = rawQuery.trim()
        val text = query.lowercase(Locale.ROOT)
            .replace(Regex("[,;!?:]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (text.isBlank()) {
            return IntentResolution(UserIntent.CHAT, query)
        }

        // 1. Check AUTO RESPONSE command
        if (text.contains("auto response") || text.contains("auto reply") || text.contains("automated response")) {
            val isEnable = !text.contains("off") && !text.contains("disable") && !text.contains("band")
            val customMsg = extractAutoReplyMessage(text)
            return IntentResolution(
                UserIntent.AUTO_RESPONSE,
                query,
                parameters = mapOf(
                    "enable" to isEnable.toString(),
                    "message" to (customMsg ?: "")
                )
            )
        }

        // 2. Check NAVIGATION commands
        if (isNavigationCommand(text)) {
            val destination = extractNavigationDestination(text)
            return IntentResolution(
                UserIntent.NAVIGATION,
                query,
                parameters = mapOf("destination" to destination)
            )
        }

        // 3. Check ALARM / WAKE-UP commands
        if (isAlarmCommand(text)) {
            val parsed = VoiceCommandParser.parse(text)
            return IntentResolution(
                UserIntent.ALARM,
                query,
                parsedCommand = parsed
            )
        }

        // 4. Check CALL / DIAL commands or Call status queries
        if (isCallCommand(text)) {
            val parsed = VoiceCommandParser.parse(text)
            return IntentResolution(
                UserIntent.CALL,
                query,
                parsedCommand = parsed
            )
        }

        // 5. Check NOTIFICATION / MESSAGE reading commands
        if (isNotificationCommand(text)) {
            val parsed = VoiceCommandParser.parse(text)
            return IntentResolution(
                UserIntent.NOTIFICATION,
                query,
                parsedCommand = parsed
            )
        }

        // 6. Check YOUTUBE specific commands
        if (isYouTubeCommand(text)) {
            val parsed = VoiceCommandParser.parse(text)
            return IntentResolution(
                UserIntent.YOUTUBE,
                query,
                parsedCommand = parsed
            )
        }

        // 7. Check SETTINGS / PERMISSIONS
        if (text.contains("settings") || text.contains("setting kholo") || text.contains("open settings")) {
            return IntentResolution(UserIntent.SETTINGS, query)
        }
        if (text.contains("permission") || text.contains("permissions kholo")) {
            return IntentResolution(UserIntent.PERMISSION, query)
        }

        // 8. Check other local device actions (Flashlight, Volume, Brightness, Apps, Screen, Bluetooth)
        val deviceCommand = VoiceCommandParser.parse(text)
        if (deviceCommand != null) {
            return IntentResolution(UserIntent.SYSTEM_ACTION, query, parsedCommand = deviceCommand)
        }

        // 9. Check KNOWLEDGE GAP DETECTION (Does query require real-time Web Research via Tavily?)
        val gapDecision = KnowledgeGapDetector.evaluate(query)
        if (gapDecision.requiresResearch) {
            return IntentResolution(
                UserIntent.RESEARCH,
                query,
                parameters = mapOf(
                    "reason" to gapDecision.reason,
                    "searchQuery" to gapDecision.suggestedSearchQuery
                )
            )
        }

        // 10. Default: Normal conversational AI
        return IntentResolution(UserIntent.CHAT, query)
    }

    private fun isNavigationCommand(text: String): Boolean {
        return text.startsWith("navigate to ") ||
                text.startsWith("take me to ") ||
                text.startsWith("directions to ") ||
                text.startsWith("open directions to ") ||
                text.startsWith("start navigation to ") ||
                text.startsWith("start navigation") ||
                text.startsWith("show me the route to ") ||
                text.contains("ka rasta dikhao") ||
                text.contains("ka route dikhao") ||
                text.contains("navigate karo") ||
                text.contains("le chalo") ||
                (text.startsWith("how far is ") && !text.contains("sun") && !text.contains("moon") && !text.contains("mars"))
    }

    fun extractNavigationDestination(text: String): String {
        return text
            .replace(Regex("^(?:please |can you )?(?:navigate to|take me to|open directions to|directions to|start navigation to|show me the route to|route to|how far is)\\s+"), "")
            .replace(Regex("\\s+(?:ka rasta dikhao|ka route dikhao|le chalo|tak navigate karo|navigate karo|rasta dikhao)$"), "")
            .trim()
            .ifBlank { "current location" }
    }

    private fun isAlarmCommand(text: String): Boolean {
        return text.contains("alarm") || text.contains("wake me up") || text.contains("utha dena") ||
                (text.contains("wake up") && (text.contains("at") || text.contains("tomorrow") || text.contains("kal")))
    }

    private fun isCallCommand(text: String): Boolean {
        return text.contains("who is calling") || text.contains("kiska phone") || text.contains("kaun call kar") ||
                text.contains("tell me who is calling") || text.startsWith("call ") || text.startsWith("dial ") ||
                text.contains("answer call") || text.contains("end call")
    }

    private fun isNotificationCommand(text: String): Boolean {
        return text.contains("read my latest notification") || text.contains("read notification") ||
                text.contains("tell me who messaged me") || text.contains("kiska message aaya") ||
                text.contains("message padho") || text.contains("notification padho")
    }

    private fun isYouTubeCommand(text: String): Boolean {
        return text.contains("youtube")
    }

    private fun extractAutoReplyMessage(text: String): String? {
        val tellMatch = Regex("(?:tell them|bata do|bol do|reply with|reply that|message karo ki|kah do ki)\\s*[:,-]?\\s*(.+)").find(text)
        return tellMatch?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }
}
