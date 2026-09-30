package com.jarvis.assistant.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.util.ContactResolver
import com.jarvis.assistant.util.LunaLogger

class IncomingCallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "IncomingCallReceiver"
        @Volatile var lastRingingNumber: String? = null
        @Volatile var isRinging: Boolean = false
        @Volatile var lastRingingCallerName: String? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        LunaLogger.i(TAG, "Phone state changed: $state, number: $incomingNumber")

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                isRinging = true
                val number = incomingNumber?.trim().orEmpty()
                lastRingingNumber = number

                val callerName = if (number.isNotEmpty()) {
                    val resolved = ContactResolver.findExact(context, number)?.name
                    if (!resolved.isNullOrBlank()) resolved else null
                } else null

                lastRingingCallerName = callerName

                val announcement = if (callerName != null) {
                    "$callerName ka phone aa raha hai. Uthana hai ya cancel karna hai?"
                } else if (number.isNotEmpty()) {
                    "Unknown number $number se call aa raha hai. Uthana hai ya cancel karna hai?"
                } else {
                    "Ek naya call aa raha hai. Uthana hai ya cancel karna hai?"
                }

                // Announce to user immediately via active assistant service
                JarvisConversationService.instance?.announceIncomingCall(announcement, number, callerName)
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                isRinging = false
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                isRinging = false
                lastRingingNumber = null
                lastRingingCallerName = null
                JarvisConversationService.instance?.onCallEnded()
            }
        }
    }
}
