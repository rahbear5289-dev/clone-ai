package com.jarvis.assistant.automation

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Transparent trampoline used to start target apps from background.
 * Android 10+ BAL often blocks Service.startActivity; starting this activity
 * from a high-priority notification PendingIntent (or overlay grant) is the
 * supported path, then this activity immediately launches the real target.
 */
class LaunchTrampolineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_TARGET, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_TARGET)
        }
        val pkg = intent.getStringExtra(EXTRA_PACKAGE)
        try {
            when {
                target != null -> {
                    target.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    )
                    startActivity(target)
                }
                !pkg.isNullOrBlank() -> {
                    val launch = packageManager.getLaunchIntentForPackage(pkg)
                    if (launch != null) {
                        launch.addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        )
                        startActivity(launch)
                    }
                }
            }
        } catch (_: Exception) {
        }
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_TARGET = "extra_target_intent"
        const val EXTRA_PACKAGE = "extra_target_package"
    }
}
