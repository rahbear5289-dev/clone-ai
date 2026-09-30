package com.jarvis.assistant.ui.home

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.marginStart
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.jarvis.assistant.R
import com.jarvis.assistant.data.model.ConversationState
import com.jarvis.assistant.databinding.ActivityMainBinding
import com.jarvis.assistant.databinding.DialogStartSessionBinding
import com.jarvis.assistant.ui.chat.ChatHistoryBottomSheet
import com.jarvis.assistant.ui.orb.OrbHelper
import com.jarvis.assistant.ui.settings.SettingsActivity
import com.jarvis.assistant.util.CameraVisionManager
import com.jarvis.assistant.util.DeviceAutomationManager
import com.jarvis.assistant.util.VisionFeedMode
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var orbHelper: OrbHelper

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            viewModel.toggleSession()
        } else {
            Toast.makeText(
                this,
                "Microphone permission is required for voice conversation.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            cameraVisionManager.toggleCameraFeed(binding.cameraPreviewView)
        } else {
            Toast.makeText(
                this,
                "Camera permission is required for camera vision feed.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private val requestContactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Contacts power "call <name>" / WhatsApp commands; absence is handled with a polite fallback. */ }

    private lateinit var deviceAutomation: DeviceAutomationManager
    private lateinit var cameraVisionManager: CameraVisionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceAutomation = DeviceAutomationManager(this)
        cameraVisionManager = CameraVisionManager(this)
        cameraVisionManager.attachTextureView(binding.cameraPreviewView)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestContactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }

        setupOrb()
        setupListeners()
        setupCallControls()
        setupVisionObservers()
        observeViewModel()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraVisionManager.closeCamera()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshSettings()
    }

    private fun setupOrb() {
        orbHelper = OrbHelper(binding.webViewOrb)
        orbHelper.setup()
    }

    private fun setupListeners() {
        binding.btnSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        // Center Slide-To-Power Control (Slide Left->Right to start, Right->Left to stop)
        setupPowerSlider()

        // Left Eye Vision Button: Dual-Feed Toggle (Camera / Screen)
        binding.btnEye.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                cameraVisionManager.toggleCameraFeed(binding.cameraPreviewView)
            } else {
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        // Switch Lens Button
        binding.btnSwitchLens.setOnClickListener {
            cameraVisionManager.switchCameraLens()
        }

        // Right Mic Mute Toggle
        binding.btnMic.setOnClickListener {
            viewModel.toggleMicMute()
        }

        // Title opens Chat History
        binding.boxLunaTitle.setOnClickListener {
            showChatHistory()
        }
    }

    private fun setupCallControls() {
        // 1. Pick Up / Answer Call
        binding.btnCallAnswer.setOnClickListener {
            val answered = deviceAutomation.answerCall()
            if (!answered) {
                deviceAutomation.openDialer()
            }
            Toast.makeText(this, "Call Connected", Toast.LENGTH_SHORT).show()
        }

        // 2. Hang Up / End Call
        binding.btnCallEnd.setOnClickListener {
            deviceAutomation.endCall()
            Toast.makeText(this, "Call Ended", Toast.LENGTH_SHORT).show()
        }

        // 3. Speakerphone Mode Toggle
        binding.btnCallSpeaker.setOnClickListener {
            val newSpeakerState = !deviceAutomation.isSpeakerphoneOn()
            deviceAutomation.setSpeakerphone(newSpeakerState)
            updateSpeakerUi(newSpeakerState)
            val msg = if (newSpeakerState) "Speakerphone ON" else "Speakerphone OFF"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // 4. Call Mute Toggle
        binding.btnCallMute.setOnClickListener {
            viewModel.toggleMicMute()
        }
    }

    private fun updateSpeakerUi(isSpeakerOn: Boolean) {
        if (isSpeakerOn) {
            binding.btnCallSpeaker.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.luna_orange)
            )
            binding.btnCallSpeaker.setBackgroundResource(R.drawable.bg_action_circle_orange)
        } else {
            binding.btnCallSpeaker.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.luna_orange)
            )
            binding.btnCallSpeaker.setBackgroundResource(R.drawable.bg_action_circle_dark)
        }
    }

    private fun setupVisionObservers() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    cameraVisionManager.feedMode.collect { mode ->
                        when (mode) {
                            com.jarvis.assistant.util.VisionFeedMode.OFF -> {
                                binding.cameraPreviewView.visibility = View.GONE
                                binding.layoutVisionBadge.visibility = View.GONE
                                binding.webViewOrb.visibility = View.VISIBLE
                            }
                            com.jarvis.assistant.util.VisionFeedMode.CAMERA_REAR,
                            com.jarvis.assistant.util.VisionFeedMode.CAMERA_FRONT -> {
                                binding.cameraPreviewView.visibility = View.VISIBLE
                                binding.layoutVisionBadge.visibility = View.VISIBLE
                                binding.webViewOrb.visibility = View.GONE
                            }
                            com.jarvis.assistant.util.VisionFeedMode.SCREEN_FEED -> {
                                binding.cameraPreviewView.visibility = View.GONE
                                binding.layoutVisionBadge.visibility = View.VISIBLE
                                binding.webViewOrb.visibility = View.VISIBLE
                            }
                        }
                    }
                }

                launch {
                    cameraVisionManager.statusDescription.collect { desc ->
                        binding.tvVisionStatus.text = desc.uppercase(Locale.ROOT)
                    }
                }
            }
        }
    }

    /**
     * Shows the "Start AI Session" confirmation dialog (matching Image 3 style).
     * Tapping Continue checks microphone permission and starts the session.
     * Tapping X or outside dismisses without starting.
     */
    private fun showStartSessionDialog() {
        val dialog = Dialog(this, R.style.Theme_TransparentDialog)
        val dialogBinding = DialogStartSessionBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        // Size: 88% screen width, wrap height
        dialog.window?.apply {
            setLayout(
                (resources.displayMetrics.widthPixels * 0.88).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundDrawableResource(android.R.color.transparent)
        }
        dialog.setCanceledOnTouchOutside(true)

        // X close button
        dialogBinding.btnDialogClose.setOnClickListener {
            dialog.dismiss()
        }

        // Continue button → check permission then start AI
        dialogBinding.btnDialogContinue.setOnClickListener {
            dialog.dismiss()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                viewModel.toggleSession()
            } else {
                requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        dialog.show()
    }

    private fun showChatHistory() {
        ChatHistoryBottomSheet.newInstance().show(
            supportFragmentManager,
            ChatHistoryBottomSheet.TAG
        )
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.isSessionOn.collect { isOn ->
                        updateSessionToggleUi(isOn)
                    }
                }

                launch {
                    viewModel.isMicMuted.collect { isMuted ->
                        updateMicMuteUi(isMuted)
                    }
                }

                launch {
                    viewModel.conversationState.collect { state ->
                        updateConversationStateUi(state)
                    }
                }

                launch {
                    viewModel.audioLevel.collect { level ->
                        orbHelper.updateAudioLevel(level)
                    }
                }

                launch {
                    viewModel.liveTime.collect { timeText ->
                        binding.tvTelemetryTime.text = if (timeText.isNotBlank()) timeText else "17:01:11"
                    }
                }

                launch {
                    viewModel.eventFlow.collect { message ->
                        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPowerSlider() {
        val track = binding.layoutPowerToggle
        val thumb = binding.sliderThumb

        var startX = 0f
        var initialTranslationX = 0f
        var isDragging = false

        thumb.setOnTouchListener { v, event ->
            val maxDrag = (track.width - thumb.width - thumb.marginStart * 2).toFloat().coerceAtLeast(1f)
            val isOnline = viewModel.isSessionOn.value

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    initialTranslationX = thumb.translationX
                    isDragging = true
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isDragging) return@setOnTouchListener false
                    val deltaX = event.rawX - startX

                    if (!isOnline) {
                        // OFFLINE: User drags LEFT to RIGHT (from 0 up to maxDrag)
                        val newX = (initialTranslationX + deltaX).coerceIn(0f, maxDrag)
                        thumb.translationX = newX
                        val progress = newX / maxDrag
                        binding.layoutSlideTextOffline.alpha = (1f - progress * 1.5f).coerceIn(0f, 1f)
                    } else {
                        // ONLINE: User drags RIGHT to LEFT (from maxDrag down to 0)
                        val newX = (initialTranslationX + deltaX).coerceIn(0f, maxDrag)
                        thumb.translationX = newX
                        val progress = (maxDrag - newX) / maxDrag
                        binding.layoutSlideTextOnline.alpha = (1f - progress * 1.5f).coerceIn(0f, 1f)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isDragging) return@setOnTouchListener false
                    isDragging = false
                    val currentX = thumb.translationX

                    if (!isOnline) {
                        // OFFLINE: If dragged >= 50% of track to right, complete activation
                        if (currentX >= maxDrag * 0.50f) {
                            thumb.animate()
                                .translationX(maxDrag)
                                .setDuration(120)
                                .withEndAction {
                                    v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                    onSlideToTurnOn()
                                }
                                .start()
                        } else {
                            // Snap back to left (0f)
                            thumb.animate()
                                .translationX(0f)
                                .setDuration(180)
                                .withEndAction {
                                    binding.layoutSlideTextOffline.alpha = 1f
                                }
                                .start()
                        }
                    } else {
                        // ONLINE: If dragged left <= 50% of maxDrag, complete stop
                        if (currentX <= maxDrag * 0.50f) {
                            thumb.animate()
                                .translationX(0f)
                                .setDuration(120)
                                .withEndAction {
                                    v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                    onSlideToTurnOff()
                                }
                                .start()
                        } else {
                            // Snap back to right (maxDrag)
                            thumb.animate()
                                .translationX(maxDrag)
                                .setDuration(180)
                                .withEndAction {
                                    binding.layoutSlideTextOnline.alpha = 1f
                                }
                                .start()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun onSlideToTurnOn() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            if (!viewModel.isSessionOn.value) {
                viewModel.toggleSession()
            }
        } else {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun onSlideToTurnOff() {
        if (viewModel.isSessionOn.value) {
            viewModel.toggleSession()
        }
    }

    private fun updateSessionToggleUi(isOn: Boolean) {
        val track = binding.layoutPowerToggle
        val thumb = binding.sliderThumb

        if (isOn) {
            // Online Mode (matching luna.mp4 00:10): Thumb on RIGHT, STOP AI / ONLINE text
            binding.layoutSlideTextOffline.visibility = View.GONE
            binding.layoutSlideTextOnline.visibility = View.VISIBLE
            binding.layoutSlideTextOnline.alpha = 1f
            binding.ivSliderThumbIcon.setImageResource(R.drawable.ic_call_end)
            orbHelper.setSessionActive(true)

            track.post {
                val maxDrag = (track.width - thumb.width - thumb.marginStart * 2).toFloat().coerceAtLeast(0f)
                thumb.animate().translationX(maxDrag).setDuration(160).start()
            }

            // Enable Eye & Mic controls when AI is active
            binding.btnEye.isEnabled = true
            binding.btnEye.alpha = 1.0f
            binding.btnEye.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.luna_red)
            )

            binding.btnMic.isEnabled = true
            binding.btnMic.alpha = 1.0f
            updateMicMuteUi(viewModel.isMicMuted.value)
        } else {
            // Offline Mode (matching luna.mp4 00:00): Thumb on LEFT, START AI / OFFLINE text
            binding.layoutSlideTextOnline.visibility = View.GONE
            binding.layoutSlideTextOffline.visibility = View.VISIBLE
            binding.layoutSlideTextOffline.alpha = 1f
            binding.ivSliderThumbIcon.setImageResource(R.drawable.ic_call)
            orbHelper.setSessionActive(false)

            track.post {
                thumb.animate().translationX(0f).setDuration(160).start()
            }

            // Darken and disable Eye and Mic when offline
            binding.btnEye.isEnabled = false
            binding.btnEye.alpha = 0.22f
            binding.btnEye.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.border_control)
            )

            binding.btnMic.isEnabled = false
            binding.btnMic.alpha = 0.22f
            binding.btnMic.setImageResource(R.drawable.ic_mic)
            binding.btnMic.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.border_control)
            )

            // Close any active camera feed
            cameraVisionManager.closeCamera()
            binding.cameraPreviewView.visibility = View.GONE
            binding.layoutVisionBadge.visibility = View.GONE
            binding.webViewOrb.visibility = View.VISIBLE
        }
    }

    private fun updateMicMuteUi(isMuted: Boolean) {
        if (!viewModel.isSessionOn.value) return // Stay disabled when offline

        if (isMuted) {
            binding.btnMic.setImageResource(R.drawable.ic_mic_off)
            binding.btnMic.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.luna_red_glow)
            )
        } else {
            binding.btnMic.setImageResource(R.drawable.ic_mic)
            binding.btnMic.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.luna_red)
            )
        }
    }

    private fun updateConversationStateUi(state: ConversationState) {
        orbHelper.updateState(state)
    }
}
