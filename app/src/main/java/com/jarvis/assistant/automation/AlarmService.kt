package com.jarvis.assistant.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import com.jarvis.assistant.util.CommandResult
import com.jarvis.assistant.util.LunaLogger
import java.util.Calendar

class AlarmService(private val context: Context) {

    companion object {
        private const val TAG = "AlarmService"

        @Volatile private var instance: AlarmService? = null
        fun getInstance(context: Context): AlarmService {
            return instance ?: synchronized(this) {
                instance ?: AlarmService(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Schedules a device alarm for the specified hour and minute.
     * Uses AlarmClock.ACTION_SET_ALARM first so the native system Clock app schedules it,
     * with AlarmManager fallback for custom alarm triggering.
     */
    fun scheduleAlarm(
        hour: Int,
        minute: Int,
        tomorrow: Boolean = false,
        message: String? = null
    ): CommandResult {
        val safeHour = hour.coerceIn(0, 23)
        val safeMinute = minute.coerceIn(0, 59)
        val alarmLabel = message ?: "Luna Alarm"

        // 1. Try official system Clock intent (AlarmClock.ACTION_SET_ALARM)
        val clockIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, safeHour)
            putExtra(AlarmClock.EXTRA_MINUTES, safeMinute)
            putExtra(AlarmClock.EXTRA_MESSAGE, alarmLabel)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val hasClockApp = clockIntent.resolveActivity(context.packageManager) != null
        if (hasClockApp) {
            try {
                context.startActivity(clockIntent)
                val timeStr = formatTime(safeHour, safeMinute)
                LunaLogger.i(TAG, "Native Alarm scheduled successfully via AlarmClock intent for $timeStr")
                return CommandResult(true, "Alarm $timeStr ka set kar diya gaya hai.")
            } catch (e: Exception) {
                LunaLogger.w(TAG, "Clock intent failed, falling back to AlarmManager: ${e.message}")
            }
        }

        // 2. Fallback: Schedule directly via AlarmManager
        return try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, safeHour)
                set(Calendar.MINUTE, safeMinute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (tomorrow || before(Calendar.getInstance())) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            val intent = Intent(context, AlarmReceiver::class.java).apply {
                action = AlarmReceiver.ACTION_ALARM_TRIGGER
                putExtra("alarm_type", "alarm")
                putExtra("alarm_message", alarmLabel)
            }

            val requestCode = safeHour * 100 + safeMinute
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Handle Android 12+ (SDK 31+) exact alarm permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
            }

            val timeStr = formatTime(safeHour, safeMinute)
            LunaLogger.i(TAG, "Alarm scheduled successfully via AlarmManager for $timeStr")
            CommandResult(true, "Alarm $timeStr ka set ho gaya.")
        } catch (e: Exception) {
            LunaLogger.e(TAG, "Alarm scheduling failed: ${e.message}", e)
            CommandResult(false, "Alarm schedule nahi ho saka.")
        }
    }

    /**
     * Schedules a device timer for the specified duration in seconds.
     */
    fun scheduleTimer(durationSeconds: Int, message: String? = null): CommandResult {
        val timerLabel = message ?: "Luna Timer"
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, durationSeconds.coerceAtLeast(1))
            putExtra(AlarmClock.EXTRA_MESSAGE, timerLabel)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val hasTimerApp = intent.resolveActivity(context.packageManager) != null
        if (hasTimerApp) {
            try {
                context.startActivity(intent)
                LunaLogger.i(TAG, "Native Timer scheduled successfully via AlarmClock intent for $durationSeconds sec")
                return CommandResult(true, "$durationSeconds seconds ka timer lag gaya.")
            } catch (e: Exception) {
                LunaLogger.w(TAG, "Timer intent failed, falling back to AlarmManager: ${e.message}")
            }
        }

        return try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerTime = System.currentTimeMillis() + (durationSeconds * 1000L)
            val intentReceiver = Intent(context, AlarmReceiver::class.java).apply {
                action = AlarmReceiver.ACTION_ALARM_TRIGGER
                putExtra("alarm_type", "timer")
                putExtra("alarm_message", timerLabel)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                (System.currentTimeMillis() % 100000).toInt(),
                intentReceiver,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            }
            LunaLogger.i(TAG, "Timer scheduled via AlarmManager for $durationSeconds sec")
            CommandResult(true, "$durationSeconds seconds ka timer set ho gaya.")
        } catch (e: Exception) {
            LunaLogger.e(TAG, "Timer scheduling failed: ${e.message}", e)
            CommandResult(false, "Timer set nahi ho saka.")
        }
    }

    private fun formatTime(hour: Int, minute: Int): String {
        val amPm = if (hour >= 12) "PM" else "AM"
        val displayHour = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        val displayMinute = minute.toString().padStart(2, '0')
        return "$displayHour:$displayMinute $amPm"
    }
}
