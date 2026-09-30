package com.jarvis.assistant.automation

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

data class CapturedNotification(
    val packageName: String,
    val title: String,
    val text: String,
    val postedAt: Long
)

class NotificationReaderService : NotificationListenerService() {

    companion object {
        @Volatile var instance: NotificationReaderService? = null
            private set

        private val recent = ConcurrentHashMap<String, CapturedNotification>()

        fun snapshot(): List<CapturedNotification> =
            recent.values.sortedByDescending { it.postedAt }

        fun forPackage(pkg: String): List<CapturedNotification> =
            snapshot().filter { it.packageName == pkg }
    }

    override fun onListenerConnected() {
        instance = this
        try {
            activeNotifications?.forEach { ingest(it) }
        } catch (_: Exception) {
        }
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) ingest(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.key?.let { recent.remove(it) }
    }

    private fun ingest(sbn: StatusBarNotification) {
        if (sbn.isOngoing) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        recent[sbn.key] = CapturedNotification(
            packageName = sbn.packageName,
            title = title,
            text = text,
            postedAt = sbn.postTime
        )
        if (recent.size > 40) {
            val oldest = recent.entries.minByOrNull { it.value.postedAt }?.key
            if (oldest != null) recent.remove(oldest)
        }

        // Real-time automatic reading for messaging apps (WhatsApp, Telegram, Signal, Messages)
        val pkg = sbn.packageName.lowercase()
        if (pkg.contains("whatsapp") || pkg.contains("telegram") || pkg.contains("messaging") || pkg.contains("mms")) {
            val lowerText = text.lowercase()
            val mediaDescription = when {
                lowerText.contains("photo") || lowerText.contains("image") || lowerText.contains("फ़ोटो") || lowerText.contains("तस्वीर") ->
                    "$title ne photo bheji hai."
                lowerText.contains("video") || lowerText.contains("वीडियो") ->
                    "$title ne video bheji hai."
                lowerText.contains("voice message") || lowerText.contains("audio") || lowerText.contains("ऑडियो") ->
                    "$title ne voice note bheja hai."
                else ->
                    "$title ka message aaya hai: $text"
            }
            com.jarvis.assistant.background.JarvisConversationService.instance?.announceMessage(mediaDescription)
        }
    }
}
