package com.jarvis.assistant.automation

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import kotlinx.coroutines.delay

object ForegroundVerifier {

    suspend fun waitForPackage(context: Context, packageName: String, timeoutMs: Long = 3_500): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var trampolineAttempted = false
        while (System.currentTimeMillis() < deadline) {
            if (DeviceStateStore.isCancelled()) return false
            val current = currentPackage(context)
            if (current == packageName || isMatchingPackage(current, packageName)) {
                DeviceStateStore.noteForeground(packageName)
                return true
            }
            // If after 900ms it hasn't reached foreground, reinforce via trampoline
            if (!trampolineAttempted && (deadline - System.currentTimeMillis()) < (timeoutMs - 900)) {
                trampolineAttempted = true
                try {
                    val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                    if (intent != null) {
                        BackgroundActivityLauncher.launchViaTrampoline(context, intent)
                    }
                } catch (_: Exception) {}
            }
            delay(200)
        }
        val finalCurrent = currentPackage(context)
        val success = finalCurrent == packageName || isMatchingPackage(finalCurrent, packageName)
        if (success) {
            DeviceStateStore.noteForeground(packageName)
        }
        return success
    }

    fun isMatchingPackage(current: String?, target: String): Boolean {
        if (current == null) return false
        if (current == target) return true
        if (target == "camera" && current.contains("camera")) return true
        if (target == "gallery" && (current.contains("gallery") || current.contains("photos"))) return true
        if ((target == "com.whatsapp" || target == "com.whatsapp.w4b") && current.startsWith("com.whatsapp")) return true
        if (target == "com.google.android.youtube" && (current.contains("youtube") || current.contains("video"))) return true
        if (target == "com.android.chrome" && (current.contains("chrome") || current.contains("browser"))) return true
        return false
    }

    fun currentPackage(context: Context): String? {
        LunaAccessibilityService.instance?.foregroundPackage()?.let { return it }
        return usageForeground(context)
    }

    private fun usageForeground(context: Context): String? {
        if (!PermissionCenter.status(context, LunaPermission.USAGE_STATS).granted) return null
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
            val end = System.currentTimeMillis()
            val begin = end - 8_000
            val events = usm.queryEvents(begin, end)
            val event = UsageEvents.Event()
            var last: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    last = event.packageName
                }
            }
            last
        } catch (_: Exception) {
            null
        }
    }
}
