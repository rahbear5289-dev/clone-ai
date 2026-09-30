package com.jarvis.assistant.automation

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.util.CommandResult

enum class LunaPermission {
    MICROPHONE,
    CAMERA,
    CONTACTS,
    PHONE,
    LOCATION,
    OVERLAY,
    ACCESSIBILITY,
    NOTIFICATION_LISTENER,
    POST_NOTIFICATIONS,
    WRITE_SETTINGS,
    USAGE_STATS,
    SCREEN_CAPTURE,
    BATTERY_OPTIMIZATION,
    STORAGE,
    BLUETOOTH_SCAN,
    SMS
}

data class PermissionStatus(
    val permission: LunaPermission,
    val granted: Boolean,
    val label: String,
    val why: String
)

object PermissionCenter {

    fun status(context: Context, permission: LunaPermission): PermissionStatus {
        val granted = when (permission) {
            LunaPermission.MICROPHONE -> hasRuntime(context, Manifest.permission.RECORD_AUDIO)
            LunaPermission.CAMERA -> hasRuntime(context, Manifest.permission.CAMERA)
            LunaPermission.CONTACTS -> hasRuntime(context, Manifest.permission.READ_CONTACTS)
            LunaPermission.PHONE -> hasRuntime(context, Manifest.permission.CALL_PHONE) &&
                    hasRuntime(context, Manifest.permission.READ_PHONE_STATE)
            LunaPermission.LOCATION ->
                hasRuntime(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
                    hasRuntime(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            LunaPermission.OVERLAY -> Settings.canDrawOverlays(context)
            LunaPermission.ACCESSIBILITY -> isAccessibilityEnabled(context)
            LunaPermission.NOTIFICATION_LISTENER -> isNotificationListenerEnabled(context)
            LunaPermission.POST_NOTIFICATIONS ->
                if (Build.VERSION.SDK_INT >= 33) hasRuntime(context, Manifest.permission.POST_NOTIFICATIONS)
                else true
            LunaPermission.WRITE_SETTINGS -> Settings.System.canWrite(context)
            LunaPermission.USAGE_STATS -> hasUsageStats(context)
            LunaPermission.SCREEN_CAPTURE -> ScreenCaptureHolder.hasProjection()
            LunaPermission.BATTERY_OPTIMIZATION -> isBatteryOptimizationIgnored(context)
            LunaPermission.STORAGE ->
                if (Build.VERSION.SDK_INT >= 33)
                    hasRuntime(context, "android.permission.READ_MEDIA_IMAGES")
                else if (Build.VERSION.SDK_INT <= 32)
                    hasRuntime(context, Manifest.permission.READ_EXTERNAL_STORAGE)
                else true
            LunaPermission.BLUETOOTH_SCAN ->
                if (Build.VERSION.SDK_INT >= 31)
                    hasRuntime(context, "android.permission.BLUETOOTH_SCAN") &&
                    hasRuntime(context, "android.permission.BLUETOOTH_CONNECT")
                else true
            LunaPermission.SMS ->
                hasRuntime(context, Manifest.permission.RECEIVE_SMS) &&
                hasRuntime(context, Manifest.permission.READ_SMS)
        }
        val (label, why) = when (permission) {
            LunaPermission.MICROPHONE -> "Microphone" to "Voice commands need the microphone."
            LunaPermission.CAMERA -> "Camera" to "Camera vision needs camera access."
            LunaPermission.CONTACTS -> "Contacts" to "Name-based calls and WhatsApp need contacts."
            LunaPermission.PHONE -> "Phone & Calls" to "Placing, answering, and controlling calls needs phone permission."
            LunaPermission.LOCATION -> "Location" to "Location and weather need GPS permission."
            LunaPermission.OVERLAY -> "Display over other apps" to "Background app switching and the screen border need overlay permission."
            LunaPermission.ACCESSIBILITY -> "Accessibility Service (All Mode)" to "Auto-answering calls, gestures (left/right/up/down scroll), app killing and UI automation need Accessibility."
            LunaPermission.NOTIFICATION_LISTENER -> "Notification access" to "Reading messages and media session control need notification access."
            LunaPermission.POST_NOTIFICATIONS -> "Notifications" to "Background status uses a persistent notification."
            LunaPermission.WRITE_SETTINGS -> "Modify system settings" to "Brightness changes need write-settings permission."
            LunaPermission.USAGE_STATS -> "Usage access" to "Foreground-app verification and running app counts need usage access."
            LunaPermission.SCREEN_CAPTURE -> "Screen capture" to "Screen visualization needs a one-time screen-capture grant."
            LunaPermission.BATTERY_OPTIMIZATION -> "Battery optimization" to "Background service stays alive without battery restrictions."
            LunaPermission.STORAGE -> "Storage access" to "Reading and writing media files needs storage permission."
            LunaPermission.BLUETOOTH_SCAN -> "Bluetooth" to "Bluetooth device detection needs Bluetooth Scan & Connect permissions."
            LunaPermission.SMS -> "SMS & Messages" to "Auto-reading incoming SMS and message announcements need SMS permission."
        }
        return PermissionStatus(permission, granted, label, why)
    }

    fun require(context: Context, vararg needed: LunaPermission): CommandResult? {
        for (p in needed) {
            val s = status(context, p)
            if (!s.granted) {
                openSettings(context, p)
                return CommandResult(
                    false,
                    "${s.label} permission currently off hai. ${s.why} Settings mein enable karne ke baad main retry kar sakti hoon."
                )
            }
        }
        return null
    }

    fun openSettings(context: Context, permission: LunaPermission) {
        val intent = when (permission) {
            LunaPermission.OVERLAY -> Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            LunaPermission.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            LunaPermission.NOTIFICATION_LISTENER -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            LunaPermission.WRITE_SETTINGS -> Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
            LunaPermission.USAGE_STATS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            LunaPermission.POST_NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
            LunaPermission.SCREEN_CAPTURE -> Intent(context, ProjectionPermissionActivity::class.java)
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
            }
        }
    }

    fun allStatuses(context: Context): List<PermissionStatus> =
        LunaPermission.values().map { status(context, it) }

    fun missing(context: Context): List<PermissionStatus> =
        allStatuses(context).filter { !it.granted }

    private fun hasRuntime(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun isAccessibilityEnabled(context: Context): Boolean {
        if (LunaAccessibilityService.isConnected()) return true
        try {
            val expected = ComponentName(context, LunaAccessibilityService::class.java).flattenToString()
            val shortExpected = "${context.packageName}/.automation.LunaAccessibilityService"
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            if (!enabled.isNullOrEmpty()) {
                val splitter = TextUtils.SimpleStringSplitter(':')
                splitter.setString(enabled)
                while (splitter.hasNext()) {
                    val s = splitter.next()
                    if (s.equals(expected, ignoreCase = true) ||
                        s.equals(shortExpected, ignoreCase = true) ||
                        s.contains("${context.packageName}/")
                    ) return true
                }
            }
        } catch (_: Exception) {}

        return try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
            val list = am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).orEmpty()
            list.any { it.resolveInfo?.serviceInfo?.packageName == context.packageName }
        } catch (_: Exception) {
            false
        }
    }

    fun isNotificationListenerEnabled(context: Context): Boolean {
        val cn = ComponentName(context, NotificationReaderService::class.java)
        return NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName) ||
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            )?.contains(cn.flattenToString()) == true
    }

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: Exception) { false }
    }

    private fun hasUsageStats(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = appOps.checkOpNoThrow(
                "android:get_usage_stats",
                android.os.Process.myUid(),
                context.packageName
            )
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    fun areNotificationsEnabled(context: Context): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.areNotificationsEnabled()
    }
}
