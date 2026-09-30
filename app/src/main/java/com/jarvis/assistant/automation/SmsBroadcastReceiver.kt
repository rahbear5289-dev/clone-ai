package com.jarvis.assistant.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.util.ContactResolver
import com.jarvis.assistant.util.LunaLogger

class SmsBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsBroadcastReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        try {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isNullOrEmpty()) return

            val senderNumber = messages[0].displayOriginatingAddress.orEmpty()
            val fullMessage = messages.joinToString("") { it.displayMessageBody.orEmpty() }

            val contactName = if (senderNumber.isNotEmpty()) {
                val resolved = ContactResolver.findExact(context, senderNumber)?.name
                if (!resolved.isNullOrBlank()) resolved else null
            } else null

            val senderLabel = contactName ?: "Unknown number $senderNumber"
            val announcement = "$senderLabel ka message aaya hai: $fullMessage"

            LunaLogger.i(TAG, "Incoming SMS from $senderLabel: $fullMessage")

            JarvisConversationService.instance?.announceMessage(announcement)
        } catch (e: Exception) {
            LunaLogger.e(TAG, "Error receiving SMS: ${e.message}", e)
        }
    }
}
