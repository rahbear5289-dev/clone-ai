package com.jarvis.assistant.ui.settings

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.automation.LunaPermission
import com.jarvis.assistant.automation.PermissionCenter
import com.jarvis.assistant.automation.ProjectionPermissionActivity
import com.jarvis.assistant.databinding.ActivityPermissionsBinding

class PermissionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPermissionsBinding
    private val handler = Handler(Looper.getMainLooper())

    // Track sequential grant flow state
    private var isGrantingAll = false
    private var pendingSequentialGrants = mutableListOf<GrantStep>()

    private data class GrantStep(
        val permission: LunaPermission,
        val label: String,
        val grantFn: () -> Unit
    )

    // Runtime permission launcher — refreshes UI on return
    private val runtimeLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        handler.postDelayed({
            refresh()
            if (isGrantingAll && pendingSequentialGrants.isNotEmpty()) {
                triggerNextGrant()
            } else if (isGrantingAll && pendingSequentialGrants.isEmpty()) {
                isGrantingAll = false
                handler.postDelayed({ refresh() }, 600)
            }
        }, 400)
    }

    // Special permission result launcher (overlay, accessibility etc.)
    private val specialPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        handler.postDelayed({
            refresh()
            if (isGrantingAll && pendingSequentialGrants.isNotEmpty()) {
                handler.postDelayed({ triggerNextGrant() }, 500)
            } else if (isGrantingAll && pendingSequentialGrants.isEmpty()) {
                isGrantingAll = false
                handler.postDelayed({ refresh() }, 600)
            }
        }, 600)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPermissionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // If we were in sequential mode and came back from a settings screen
        if (isGrantingAll && pendingSequentialGrants.isNotEmpty()) {
            handler.postDelayed({ triggerNextGrant() }, 800)
        }
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }

        // ── Microphone ──
        binding.btnMicGrant.setOnClickListener {
            if (!PermissionCenter.status(this, LunaPermission.MICROPHONE).granted)
                runtimeLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            else showAlreadyGrantedToast("Microphone")
        }

        // ── Camera ──
        binding.btnCameraGrant.setOnClickListener {
            if (!PermissionCenter.status(this, LunaPermission.CAMERA).granted)
                runtimeLauncher.launch(arrayOf(Manifest.permission.CAMERA))
            else showAlreadyGrantedToast("Camera")
        }

        // ── Contacts ──
        binding.btnContactsGrant.setOnClickListener {
            if (!PermissionCenter.status(this, LunaPermission.CONTACTS).granted)
                runtimeLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS))
            else showAlreadyGrantedToast("Contacts")
        }

        // ── Phone / Calls ──
        binding.btnPhoneGrant.setOnClickListener {
            if (!PermissionCenter.status(this, LunaPermission.PHONE).granted)
                runtimeLauncher.launch(arrayOf(
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.READ_PHONE_STATE,
                    if (Build.VERSION.SDK_INT >= 26) Manifest.permission.ANSWER_PHONE_CALLS else Manifest.permission.CALL_PHONE
                ))
            else showAlreadyGrantedToast("Phone")
        }

        // ── Location ──
        binding.btnLocationGrant.setOnClickListener {
            if (!PermissionCenter.status(this, LunaPermission.LOCATION).granted)
                runtimeLauncher.launch(arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ))
            else showAlreadyGrantedToast("Location")
        }

        // ── Notifications ──
        binding.btnNotificationsGrant.setOnClickListener {
            if (!PermissionCenter.status(this, LunaPermission.POST_NOTIFICATIONS).granted) {
                if (Build.VERSION.SDK_INT >= 33)
                    runtimeLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                else
                    openSpecialSetting(LunaPermission.NOTIFICATION_LISTENER)
            } else showAlreadyGrantedToast("Notifications")
        }

        // ── Display Over Other Apps (Overlay) ──
        binding.btnOverlayGrant.setOnClickListener {
            if (!PermissionCenter.status(this, LunaPermission.OVERLAY).granted)
                openSpecialSetting(LunaPermission.OVERLAY)
            else showAlreadyGrantedToast("Overlay")
        }

        // ── Accessibility Service (All Accessibility Mode) ──
        binding.btnAccessibilityGrant.setOnClickListener {
            if (!PermissionCenter.status(this, LunaPermission.ACCESSIBILITY).granted)
                openSpecialSetting(LunaPermission.ACCESSIBILITY)
            else showAlreadyGrantedToast("Accessibility Service")
        }

        // ── SMS & Messages ──
        binding.btnSmsGrant.setOnClickListener {
            if (!PermissionCenter.status(this, LunaPermission.SMS).granted)
                runtimeLauncher.launch(arrayOf(
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.READ_SMS,
                    Manifest.permission.SEND_SMS
                ))
            else showAlreadyGrantedToast("SMS & Messages")
        }

        // ── Notification Listener ──
        binding.btnNotifListenerGrant.setOnClickListener {
            if (!PermissionCenter.status(this, LunaPermission.NOTIFICATION_LISTENER).granted)
                openSpecialSetting(LunaPermission.NOTIFICATION_LISTENER)
            else showAlreadyGrantedToast("Notification Listener")
        }

        // ── Write Settings ──
        binding.btnWriteSettingsGrant.setOnClickListener {
            if (!PermissionCenter.status(this, LunaPermission.WRITE_SETTINGS).granted)
                openSpecialSetting(LunaPermission.WRITE_SETTINGS)
            else showAlreadyGrantedToast("Write Settings")
        }

        // ── Usage Stats ──
        binding.btnUsageStatsGrant.setOnClickListener {
            if (!PermissionCenter.status(this, LunaPermission.USAGE_STATS).granted)
                openSpecialSetting(LunaPermission.USAGE_STATS)
            else showAlreadyGrantedToast("Usage Access")
        }

        // ── Battery Optimization ──
        binding.btnBatteryGrant.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${packageName}")
                }
                specialPermLauncher.launch(intent)
            } catch (e: Exception) {
                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                specialPermLauncher.launch(fallback)
            }
        }

        // ── Screen Capture ──
        binding.btnScreenCaptureGrant.setOnClickListener {
            if (!PermissionCenter.status(this, LunaPermission.SCREEN_CAPTURE).granted)
                openSpecialSetting(LunaPermission.SCREEN_CAPTURE)
            else showAlreadyGrantedToast("Screen Capture")
        }

        // ── GRANT ALL — sequential one by one ──
        binding.btnGrantAll.setOnClickListener {
            startSequentialGrantFlow()
        }

        // ── RE-CHECK — refresh statuses and re-fire only ungranted popups ──
        binding.btnRecheck.setOnClickListener {
            refresh()
            val missing = getMissingPermissions()
            if (missing.isEmpty()) {
                Toast.makeText(this, "✅ All permissions are already granted!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "🔁 Re-checking ${missing.size} missing permission(s)…", Toast.LENGTH_SHORT).show()
                handler.postDelayed({
                    isGrantingAll = true
                    pendingSequentialGrants = buildGrantSteps(missing).toMutableList()
                    triggerNextGrant()
                }, 400)
            }
        }
    }

    /**
     * Builds sequential grant steps for all missing permissions.
     */
    private fun buildGrantSteps(missing: List<LunaPermission>): List<GrantStep> {
        val steps = mutableListOf<GrantStep>()

        // Batch all runtime permissions together first
        val runtimePerms = mutableListOf<String>()
        if (LunaPermission.MICROPHONE in missing) runtimePerms.add(Manifest.permission.RECORD_AUDIO)
        if (LunaPermission.CAMERA in missing) runtimePerms.add(Manifest.permission.CAMERA)
        if (LunaPermission.CONTACTS in missing) runtimePerms.add(Manifest.permission.READ_CONTACTS)
        if (LunaPermission.PHONE in missing) {
            runtimePerms.add(Manifest.permission.CALL_PHONE)
            runtimePerms.add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= 26) runtimePerms.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }
        if (LunaPermission.LOCATION in missing) {
            runtimePerms.add(Manifest.permission.ACCESS_FINE_LOCATION)
            runtimePerms.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33 && LunaPermission.POST_NOTIFICATIONS in missing) {
            runtimePerms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= 31 && LunaPermission.BLUETOOTH_SCAN in missing) {
            runtimePerms.add("android.permission.BLUETOOTH_SCAN")
            runtimePerms.add("android.permission.BLUETOOTH_CONNECT")
        }
        if (Build.VERSION.SDK_INT <= 32 && LunaPermission.STORAGE in missing) {
            runtimePerms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= 33 && LunaPermission.STORAGE in missing) {
            runtimePerms.add("android.permission.READ_MEDIA_IMAGES")
            runtimePerms.add("android.permission.READ_MEDIA_VIDEO")
            runtimePerms.add("android.permission.READ_MEDIA_AUDIO")
        }
        if (LunaPermission.SMS in missing) {
            runtimePerms.add(Manifest.permission.RECEIVE_SMS)
            runtimePerms.add(Manifest.permission.READ_SMS)
            runtimePerms.add(Manifest.permission.SEND_SMS)
        }

        if (runtimePerms.isNotEmpty()) {
            steps.add(GrantStep(LunaPermission.MICROPHONE, "Runtime Permissions") {
                runtimeLauncher.launch(runtimePerms.toTypedArray())
            })
        }

        // Special permissions — each opens its own system settings screen
        if (LunaPermission.OVERLAY in missing) {
            steps.add(GrantStep(LunaPermission.OVERLAY, "Display Over Other Apps") {
                openSpecialSetting(LunaPermission.OVERLAY)
            })
        }
        if (LunaPermission.ACCESSIBILITY in missing) {
            steps.add(GrantStep(LunaPermission.ACCESSIBILITY, "Accessibility Service") {
                openSpecialSetting(LunaPermission.ACCESSIBILITY)
            })
        }
        if (LunaPermission.NOTIFICATION_LISTENER in missing) {
            steps.add(GrantStep(LunaPermission.NOTIFICATION_LISTENER, "Notification Listener") {
                openSpecialSetting(LunaPermission.NOTIFICATION_LISTENER)
            })
        }
        if (LunaPermission.WRITE_SETTINGS in missing) {
            steps.add(GrantStep(LunaPermission.WRITE_SETTINGS, "Modify System Settings") {
                openSpecialSetting(LunaPermission.WRITE_SETTINGS)
            })
        }
        if (LunaPermission.USAGE_STATS in missing) {
            steps.add(GrantStep(LunaPermission.USAGE_STATS, "Usage Access") {
                openSpecialSetting(LunaPermission.USAGE_STATS)
            })
        }
        if (LunaPermission.BATTERY_OPTIMIZATION in missing) {
            steps.add(GrantStep(LunaPermission.BATTERY_OPTIMIZATION, "Battery Optimization") {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    specialPermLauncher.launch(intent)
                } catch (_: Exception) {
                    specialPermLauncher.launch(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            })
        }
        if (LunaPermission.SCREEN_CAPTURE in missing) {
            steps.add(GrantStep(LunaPermission.SCREEN_CAPTURE, "Screen Capture") {
                openSpecialSetting(LunaPermission.SCREEN_CAPTURE)
            })
        }

        return steps
    }

    private fun startSequentialGrantFlow() {
        val missing = getMissingPermissions()
        if (missing.isEmpty()) {
            Toast.makeText(this, "✅ All permissions are already granted!", Toast.LENGTH_SHORT).show()
            return
        }

        isGrantingAll = true
        pendingSequentialGrants = buildGrantSteps(missing).toMutableList()
        Toast.makeText(this, "🔒 Granting ${missing.size} permission(s) one by one…", Toast.LENGTH_SHORT).show()
        handler.postDelayed({ triggerNextGrant() }, 300)
    }

    private fun triggerNextGrant() {
        if (pendingSequentialGrants.isEmpty()) {
            isGrantingAll = false
            handler.postDelayed({ refresh() }, 500)
            return
        }
        val step = pendingSequentialGrants.removeAt(0)
        step.grantFn()
    }

    private fun getMissingPermissions(): List<LunaPermission> {
        // Track ALL permissions the app uses
        val tracked = mutableListOf(
            LunaPermission.MICROPHONE,
            LunaPermission.CAMERA,
            LunaPermission.CONTACTS,
            LunaPermission.PHONE,
            LunaPermission.LOCATION,
            LunaPermission.OVERLAY,
            LunaPermission.POST_NOTIFICATIONS,
            LunaPermission.ACCESSIBILITY,
            LunaPermission.NOTIFICATION_LISTENER,
            LunaPermission.WRITE_SETTINGS,
            LunaPermission.USAGE_STATS,
            LunaPermission.BATTERY_OPTIMIZATION,
            LunaPermission.SCREEN_CAPTURE,
            LunaPermission.SMS
        )
        // Add storage if relevant
        if (Build.VERSION.SDK_INT <= 32) tracked.add(LunaPermission.STORAGE)
        // Add Bluetooth permissions on Android 12+
        if (Build.VERSION.SDK_INT >= 31) tracked.add(LunaPermission.BLUETOOTH_SCAN)
        return tracked.filter { !PermissionCenter.status(this, it).granted }
    }

    private fun openSpecialSetting(permission: LunaPermission) {
        try {
            PermissionCenter.openSettings(this, permission)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open settings for $permission", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAlreadyGrantedToast(name: String) {
        Toast.makeText(this, "✅ $name is already granted!", Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        handler.postDelayed({ refresh() }, 300)
    }

    /** Refreshes all permission card badges and the status banner. */
    private fun refresh() {
        val micGranted        = PermissionCenter.status(this, LunaPermission.MICROPHONE).granted
        val cameraGranted     = PermissionCenter.status(this, LunaPermission.CAMERA).granted
        val contactsGranted   = PermissionCenter.status(this, LunaPermission.CONTACTS).granted
        val phoneGranted      = PermissionCenter.status(this, LunaPermission.PHONE).granted
        val locationGranted   = PermissionCenter.status(this, LunaPermission.LOCATION).granted
        val notifGranted      = PermissionCenter.status(this, LunaPermission.POST_NOTIFICATIONS).granted
        val overlayGranted    = PermissionCenter.status(this, LunaPermission.OVERLAY).granted
        val accessGranted     = PermissionCenter.status(this, LunaPermission.ACCESSIBILITY).granted
        val listenerGranted   = PermissionCenter.status(this, LunaPermission.NOTIFICATION_LISTENER).granted
        val writeSetGranted   = PermissionCenter.status(this, LunaPermission.WRITE_SETTINGS).granted
        val usageGranted      = PermissionCenter.status(this, LunaPermission.USAGE_STATS).granted
        val batteryGranted    = PermissionCenter.status(this, LunaPermission.BATTERY_OPTIMIZATION).granted
        val screenGranted     = PermissionCenter.status(this, LunaPermission.SCREEN_CAPTURE).granted
        val smsGranted        = PermissionCenter.status(this, LunaPermission.SMS).granted

        paintAnimated(binding.btnMicGrant,           micGranted)
        paintAnimated(binding.btnCameraGrant,         cameraGranted)
        paintAnimated(binding.btnContactsGrant,       contactsGranted)
        paintAnimated(binding.btnPhoneGrant,          phoneGranted)
        paintAnimated(binding.btnLocationGrant,       locationGranted)
        paintAnimated(binding.btnNotificationsGrant,  notifGranted)
        paintAnimated(binding.btnOverlayGrant,        overlayGranted)
        paintAnimated(binding.btnAccessibilityGrant,  accessGranted)
        paintAnimated(binding.btnSmsGrant,            smsGranted)
        paintAnimated(binding.btnNotifListenerGrant,  listenerGranted)
        paintAnimated(binding.btnWriteSettingsGrant,  writeSetGranted)
        paintAnimated(binding.btnUsageStatsGrant,     usageGranted)
        paintAnimated(binding.btnBatteryGrant,        batteryGranted)
        paintAnimated(binding.btnScreenCaptureGrant,  screenGranted)

        val missing = getMissingPermissions()
        updateBanner(missing.size)
    }

    private fun updateBanner(missingCount: Int) {
        if (missingCount == 0) {
            binding.tvBannerTitle.text = "ALL REQUIRED PERMISSIONS GRANTED ✓"
            binding.tvBannerSubtitle.text = "Voice, overlay, notifications, location and accessibility are ready."
            binding.cardStatusBanner.setBackgroundResource(R.drawable.bg_card_green_outline)
            binding.ivBannerIcon.setImageResource(R.drawable.ic_check_circle)
            binding.ivBannerIcon.clearColorFilter()
            binding.ivBannerIcon.setColorFilter(
                ContextCompat.getColor(this, R.color.status_green)
            )
            binding.tvBannerTitle.setTextColor(ContextCompat.getColor(this, R.color.status_green))
        } else {
            binding.tvBannerTitle.text = "ACTION REQUIRED: $missingCount PERMISSION(S) MISSING"
            binding.tvBannerSubtitle.text = "Grant remaining access so Luna can run in background."
            binding.cardStatusBanner.setBackgroundResource(R.drawable.bg_card_red_outline)
            binding.ivBannerIcon.setImageResource(R.drawable.ic_shield_alert)
            binding.ivBannerIcon.setColorFilter(
                ContextCompat.getColor(this, R.color.luna_red_light)
            )
            binding.tvBannerTitle.setTextColor(ContextCompat.getColor(this, R.color.luna_red_light))
        }
    }

    /**
     * Animates the grant button smoothly:
     * - Orange "GRANT" → Green "GRANTED" with a scale+fade pulse animation.
     * - Skips animation if state hasn't changed.
     */
    private fun paintAnimated(button: TextView, granted: Boolean) {
        val targetText = if (granted) "GRANTED" else "GRANT"
        if (button.text == targetText) return  // no change, skip animation

        val scaleDown = ObjectAnimator.ofFloat(button, View.SCALE_X, 1f, 0.85f)
        val scaleDownY = ObjectAnimator.ofFloat(button, View.SCALE_Y, 1f, 0.85f)
        val fadeOut = ObjectAnimator.ofFloat(button, View.ALPHA, 1f, 0.4f)

        val shrink = AnimatorSet().apply {
            playTogether(scaleDown, scaleDownY, fadeOut)
            duration = 120
            interpolator = AccelerateDecelerateInterpolator()
        }

        shrink.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                // Apply new state at midpoint
                applyGrantState(button, granted)

                val scaleUp = ObjectAnimator.ofFloat(button, View.SCALE_X, 0.85f, 1.05f)
                val scaleUpY = ObjectAnimator.ofFloat(button, View.SCALE_Y, 0.85f, 1.05f)
                val fadeIn = ObjectAnimator.ofFloat(button, View.ALPHA, 0.4f, 1f)
                val grow = AnimatorSet().apply {
                    playTogether(scaleUp, scaleUpY, fadeIn)
                    duration = 160
                    interpolator = AccelerateDecelerateInterpolator()
                }
                grow.addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        val settle = ObjectAnimator.ofFloat(button, View.SCALE_X, 1.05f, 1f)
                        val settleY = ObjectAnimator.ofFloat(button, View.SCALE_Y, 1.05f, 1f)
                        AnimatorSet().apply {
                            playTogether(settle, settleY)
                            duration = 80
                            start()
                        }
                    }
                })
                grow.start()
            }
        })
        shrink.start()
    }

    private fun applyGrantState(button: TextView, granted: Boolean) {
        if (granted) {
            button.text = "GRANTED"
            button.setBackgroundResource(R.drawable.bg_btn_grant_green)
            button.setTextColor(ContextCompat.getColor(this, R.color.status_green))
        } else {
            button.text = "GRANT"
            button.setBackgroundResource(R.drawable.bg_btn_grant_orange)
            button.setTextColor(ContextCompat.getColor(this, R.color.luna_orange))
        }
    }
}
