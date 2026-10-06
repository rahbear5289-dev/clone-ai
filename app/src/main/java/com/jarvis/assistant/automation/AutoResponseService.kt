package com.jarvis.assistant.automation

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.util.CommandResult
import com.jarvis.assistant.util.ContactResolver
import com.jarvis.assistant.util.LunaLogger
import java.util.concurrent.ConcurrentHashMap

data class AutoResponseRuleConfig(
    val enabled: Boolean,
    val responseText: String,
    val activeUntilMs: Long? = null,
    val allowedApps: Set<String> = setOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.google.android.apps.messaging"),
    val replyToUnknownSenders: Boolean = true,
    val replyToKnownContacts: Boolean = true
)

class AutoResponseService(private val context: Context) {

    companion object {
        private const val TAG = "AutoResponseService"
        private const val COOLDOWN_PER_SENDER_MS = 3 * 60 * 1000L // 3 minutes cooldown to prevent loops

        @Volatile private var instance: AutoResponseService? = null
        fun getInstance(context: Context): AutoResponseService {
            return instance ?: synchronized(this) {
                instance ?: AutoResponseService(context.applicationContext).also { instance = it }
            }
        }

        // Tracks recent replies to avoid duplicate replies and infinite loops
        // Key: "$packageName:$senderKey", Value: timestamp
        private val lastReplyTimes = ConcurrentHashMap<String, Long>()
        // Tracks processed notification keys
        private val processedNotifKeys = ConcurrentHashMap<String, Long>()
    }

    private val prefs by lazy { (context.applicationContext as JarvisApp).preferences }

    fun getConfig(): AutoResponseRuleConfig {
        return AutoResponseRuleConfig(
            enabled = prefs.isAutoResponseEnabled,
            responseText = prefs.autoResponseMessage,
            allowedApps = prefs.autoResponseAllowedApps,
            replyToUnknownSenders = prefs.autoResponseReplyUnknown,
            replyToKnownContacts = prefs.autoResponseReplyKnown
        )
    }

    fun setAutoResponse(enabled: Boolean, customMessage: String? = null): CommandResult {
        prefs.isAutoResponseEnabled = enabled
        if (!customMessage.isNullOrBlank()) {
            prefs.autoResponseMessage = customMessage
        }

        val spokenMsg = if (enabled) {
            "Auto response chalu kar diya gaya hai. Message: '${prefs.autoResponseMessage}'"
        } else {
            "Auto response band kar diya gaya hai."
        }
        LunaLogger.i(TAG, "AutoResponse updated: enabled=$enabled, message='${prefs.autoResponseMessage}'")
        return CommandResult(true, spokenMsg)
    }

    /**
     * Examines an incoming StatusBarNotification. If Auto Response is enabled and matches rules,
     * attempts official RemoteInput inline reply.
     */
    fun handleIncomingNotification(sbn: StatusBarNotification): Boolean {
        val config = getConfig()
        if (!config.enabled) return false

        val pkg = sbn.packageName.lowercase()
        val isAllowedApp = config.allowedApps.any { pkg.contains(it.lowercase()) } ||
                pkg.contains("whatsapp") || pkg.contains("telegram") || pkg.contains("messaging")

        if (!isAllowedApp) return false

        // Check if notification already processed
        val notifKey = sbn.key
        if (processedNotifKeys.containsKey(notifKey)) return false
        processedNotifKeys[notifKey] = System.currentTimeMillis()

        // Clean up old processed notification keys
        if (processedNotifKeys.size > 100) {
            val cutoff = System.currentTimeMillis() - 10 * 60 * 1000L
            processedNotifKeys.entries.removeIf { it.value < cutoff }
        }

        val extras = sbn.notification.extras
        val senderTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val messageText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

        if (senderTitle.isBlank() || messageText.isBlank()) return false

        // Filter contact rules
        val isKnownContact = ContactResolver.findExact(context, senderTitle) != null
        if (isKnownContact && !config.replyToKnownContacts) return false
        if (!isKnownContact && !config.replyToUnknownSenders) return false

        // Loop & duplication prevention: check per-sender cooldown
        val senderKey = "$pkg:$senderTitle"
        val lastSent = lastReplyTimes[senderKey] ?: 0L
        val now = System.currentTimeMillis()
        if (now - lastSent < COOLDOWN_PER_SENDER_MS) {
            LunaLogger.d(TAG, "Skipping auto-reply to $senderKey (cooldown active to prevent loop)")
            return false
        }

        // Attempt official RemoteInput reply extraction
        val replyAction = findReplyAction(sbn.notification)
        if (replyAction == null) {
            LunaLogger.w(TAG, "Automatic reply is not supported by application: $pkg")
            return false
        }

        val (action, remoteInput) = replyAction
        return try {
            val replyIntent = Intent()
            val bundle = Bundle().apply {
                putCharSequence(remoteInput.resultKey, config.responseText)
            }
            RemoteInput.addResultsToIntent(arrayOf(remoteInput), replyIntent, bundle)
            action.actionIntent.send(context, 0, replyIntent)
            lastReplyTimes[senderKey] = now
            LunaLogger.i(TAG, "Successfully sent auto-reply to $senderTitle via RemoteInput: '${config.responseText}'")
            true
        } catch (e: Exception) {
            LunaLogger.e(TAG, "Failed sending RemoteInput reply: ${e.message}", e)
            false
        }
    }

    private fun findReplyAction(notification: Notification): Pair<Notification.Action, RemoteInput>? {
        val actions = notification.actions ?: return null
        for (action in actions) {
            val remoteInputs = action.remoteInputs ?: continue
            for (ri in remoteInputs) {
                // Actions with resultKey or allowFreeFormInput typically support text reply
                if (ri.allowFreeFormInput || ri.resultKey.isNotBlank()) {
                    return Pair(action, ri)
                }
            }
        }
        return null
    }
}
