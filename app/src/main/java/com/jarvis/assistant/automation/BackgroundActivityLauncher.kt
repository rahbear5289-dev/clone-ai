package com.jarvis.assistant.automation

import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.util.LunaLogger

/**
 * Starts activities reliably while the assistant UI is minimized or backgrounded.
 *
 * Android 10+ (and especially Android 14 API 34) enforces Background Activity
 * Launch (BAL) restrictions. To ensure YouTube, Chrome, WhatsApp, and other apps
 * reliably open and switch from background:
 * 1. Prepares intent with NEW_TASK | REORDER_TO_FRONT | RESET_TASK_IF_NEEDED.
 * 2. If SYSTEM_ALERT_WINDOW is granted, invokes direct launch and trampoline.
 * 3. Uses PendingIntent with ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED (API 34+).
 * 4. Dispatches full-screen high-priority notification trampoline for OEM compatibility.
 */
object BackgroundActivityLauncher {
    private const val TAG = "BgLaunch"
    private const val CHANNEL_ID = "luna_bg_launch"
    private const val NOTIF_ID = 77

    fun launch(context: Context, target: Intent): Boolean {
        target.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        )

        // Try direct launch via application context or overlay permission
        var directStarted = false
        try {
            val options = createBalActivityOptions()
            if (options != null) {
                context.applicationContext.startActivity(target, options.toBundle())
            } else {
                context.applicationContext.startActivity(target)
            }
            directStarted = true
            LunaLogger.d(TAG, "Direct startActivity invoked")
        } catch (e: Exception) {
            LunaLogger.w(TAG, "Direct startActivity failed: ${e.message}")
        }

        // Accessibility service context attempt (if available)
        val a11y = LunaAccessibilityService.instance
        if (a11y != null) {
            try {
                a11y.startActivity(target)
                LunaLogger.d(TAG, "Accessibility service startActivity invoked")
            } catch (e: Exception) {
                LunaLogger.w(TAG, "a11y startActivity failed: ${e.message}")
            }
        }

        // Always back up with the Trampoline PendingIntent launch to ensure BAL pass-through
        val trampolineSuccess = launchViaTrampoline(context, target)
        return directStarted || trampolineSuccess
    }

    fun launchPackage(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        )
        return launch(context, intent)
    }

    fun launchViaTrampoline(context: Context, target: Intent): Boolean {
        return try {
            ensureChannel(context)
            val trampoline = Intent(context, LaunchTrampolineActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
                putExtra(LaunchTrampolineActivity.EXTRA_TARGET, target)
            }

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val requestCode = (System.currentTimeMillis() % 100000).toInt()
            val pi = PendingIntent.getActivity(context, requestCode, trampoline, flags)

            val opts = createBalActivityOptions()

            // 1. Try sending the PendingIntent directly with background activity start mode
            var piSent = false
            try {
                if (opts != null) {
                    pi.send(context, 0, null, null, null, null, opts.toBundle())
                } else {
                    pi.send()
                }
                piSent = true
                LunaLogger.d(TAG, "PendingIntent sent with BAL mode allowed")
            } catch (e: Exception) {
                LunaLogger.w(TAG, "Direct PI send failed: ${e.message}")
            }

            // 2. Only fall back to high-priority notification trampoline if direct PI send failed
            // Conforms to FR-NTF-1 and resolves Bug #10 (Excessive notifications)
            if (!piSent) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_open_in_new)
                    .setContentTitle("Luna Assistant")
                    .setContentText("Opening requested application...")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                    .setFullScreenIntent(pi, true)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setTimeoutAfter(2_000)
                    .build()
                nm.notify(NOTIF_ID, notif)
            }

            try {
                context.startActivity(trampoline)
            } catch (_: Exception) {}

            true
        } catch (e: Exception) {
            LunaLogger.e(TAG, "Trampoline launch failed: ${e.message}", e)
            false
        }
    }

    private fun createBalActivityOptions(): ActivityOptions? {
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                val opts = ActivityOptions.makeBasic()
                opts.setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
                return opts
            } catch (_: Throwable) {}
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ActivityOptions.makeBasic()
        } else null
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Background app launch",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Used when Luna must open an app while minimized"
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(ch)
    }
}
