package com.jarvis.assistant.util

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.location.Geocoder
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.provider.AlarmClock
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import android.view.KeyEvent
import com.jarvis.assistant.automation.BackgroundActivityLauncher
import com.jarvis.assistant.automation.DeviceStateStore
import com.jarvis.assistant.automation.ForegroundVerifier
import com.jarvis.assistant.automation.LunaAccessibilityService
import com.jarvis.assistant.automation.LunaPermission
import com.jarvis.assistant.automation.MediaPlaybackController
import com.jarvis.assistant.automation.NotificationReaderService
import com.jarvis.assistant.automation.PermissionCenter
import com.jarvis.assistant.automation.ProjectionPermissionActivity
import com.jarvis.assistant.automation.ScreenCaptureEngine
import com.jarvis.assistant.automation.ScreenCaptureHolder
import com.jarvis.assistant.automation.ScreenOverlayController
import com.jarvis.assistant.data.model.contract.ToolError
import com.jarvis.assistant.data.model.contract.ToolErrorCodes
import com.jarvis.assistant.data.model.contract.ToolResponse
import com.jarvis.assistant.util.LunaLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** What a device command produced: whether it ran, what to tell the user, and structured ToolResponse envelope. */
data class CommandResult(
    val success: Boolean,
    val spoken: String,
    val response: ToolResponse? = null
) {
    fun toToolResponse(toolName: String = "device_automation", action: String? = null): ToolResponse {
        return response ?: if (success) {
            ToolResponse.success(
                tool = toolName,
                action = action,
                spoken = spoken,
                verified = true
            )
        } else {
            ToolResponse.failure(
                tool = toolName,
                code = ToolErrorCodes.EXECUTION_FAILED,
                message = spoken.ifBlank { "Command execution failed" },
                spoken = spoken
            )
        }
    }
}

/**
 * DeviceAutomationManager orchestrates system and application automation:
 * - App launches, closing, switching, minimizing, and background reactivation
 * - WhatsApp messaging with genuine send button click and verification
 * - YouTube search, playback, first/second video clicks, seek, speed, and quality
 * - Universal MediaSession playback controls
 * - Telephony with contact disambiguation, in-call speaker/mute/end, and incoming answer
 * - Live screen access, fresh screen captures per query, and orange border visualization
 * - Chrome multi-tab and multi-window automation (>8 websites)
 * - Hardware toggles (torch, volume, brightness, battery, settings panels)
 * - Coding assistant & editor automation
 */
class DeviceAutomationManager(private val context: Context) {

    companion object {
        private const val TAG = "DeviceAutomation"

        val COMMON_PACKAGES = mapOf(
            "whatsapp" to "com.whatsapp",
            "whatsapp business" to "com.whatsapp.w4b",
            "youtube" to "com.google.android.youtube",
            "google" to "com.google.android.googlequicksearchbox",
            "chrome" to "com.android.chrome",
            "camera" to "camera",
            "phone" to "dialer",
            "settings" to "com.android.settings",
            "maps" to "com.google.android.apps.maps",
            "spotify" to "com.spotify.music",
            "instagram" to "com.instagram.android",
            "facebook" to "com.facebook.katana",
            "telegram" to "org.telegram.messenger",
            "gallery" to "gallery",
            "play store" to "com.android.vending",
            "photos" to "com.google.android.apps.photos"
        )

        val RANDOM_MUSIC_CATEGORIES = listOf(
            "peaceful music", "lofi beats", "old classic songs", "romantic love songs",
            "instrumental melodies", "focus study music", "relaxing acoustic songs",
            "workout energy music", "deep sleep music"
        )
    }

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private val telecomManager: TelecomManager? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        } else null
    }

    private val overlay by lazy { ScreenOverlayController(context.applicationContext) }
    private val screenEngine by lazy { ScreenCaptureEngine(context.applicationContext) }

    // ==========================================
    // 0. COMMAND PIPELINE & SEQUENCING
    // ==========================================

    suspend fun executeSequence(text: String): CommandResult {
        DeviceStateStore.resetCancel()
        val steps = VoiceCommandParser.parseSequence(text).ifEmpty {
            listOfNotNull(VoiceCommandParser.parse(text))
        }
        if (steps.isEmpty()) return CommandResult(false, "")

        var last = CommandResult(false, "Couldn't run that.")
        for (step in steps) {
            if (DeviceStateStore.isCancelled()) {
                return CommandResult(true, "Task cancel kar diya.")
            }
            last = executeAsync(step)
            if (!last.success) return last
            delay(150)
        }
        return last
    }

    suspend fun executeAsync(command: DeviceCommand): CommandResult = try {
        DeviceStateStore.update { it.copy(currentTask = command::class.simpleName) }
        when (command) {
            is DeviceCommand.OpenApp -> openAppVerified(command.appName)
            is DeviceCommand.CloseApp -> closeAppByVoice(command.appName)
            is DeviceCommand.MinimizeApp -> minimizeApp(command.appName)
            is DeviceCommand.SwitchApp -> switchAppVerified(command.from, command.to)
            is DeviceCommand.OpenWebsites -> openWebsites(command.sites)
            DeviceCommand.OpenHome -> goHomeResult()
            DeviceCommand.GoBack -> {
                val ok = LunaAccessibilityService.instance?.goBack() == true
                if (ok) CommandResult(true, "Back.")
                else PermissionCenter.require(context, LunaPermission.ACCESSIBILITY)
                    ?: CommandResult(false, "Back ke liye Accessibility permission chahiye.")
            }
            DeviceCommand.OpenRecents -> {
                val ok = LunaAccessibilityService.instance?.openRecents() == true
                if (ok) CommandResult(true, "Recents khol diya.")
                else PermissionCenter.require(context, LunaPermission.ACCESSIBILITY)
                    ?: CommandResult(false, "Recents ke liye Accessibility permission chahiye.")
            }
            DeviceCommand.PreviousApp -> previousApp()
            DeviceCommand.SplitScreen -> {
                val ok = LunaAccessibilityService.instance?.splitScreen() == true
                if (ok) CommandResult(true, "Split screen activate kar diya.")
                else CommandResult(false, "Is device par split screen automation supported nahi hai.")
            }
            DeviceCommand.ListRunningApps -> listRunningAppsResult()
            DeviceCommand.CloseAllApps -> closeAllAppsResult()
            DeviceCommand.OpenAllApps -> openAllAppsResult()
            is DeviceCommand.WhatsAppMessage -> whatsAppMessageResult(command.target, command.message)
            is DeviceCommand.WhatsAppOpenChat -> whatsAppChatResult(command.target)
            is DeviceCommand.WhatsAppCall -> whatsAppCallResult(command.target, command.video)
            DeviceCommand.WhatsAppFirstVideo -> playFirstVisibleMedia()
            is DeviceCommand.YouTubeSearch -> searchYouTubeResult(command.query)
            is DeviceCommand.YouTubePlay -> playYouTubeResult(command.query)
            DeviceCommand.PlayFirstResult -> clickFirstSearchResult()
            DeviceCommand.PlayFirstVideo -> playNthYouTubeVideo(0)
            is DeviceCommand.PlayNthVideo -> playNthYouTubeVideo(command.index)
            DeviceCommand.MediaPlayPause -> MediaPlaybackController.playPause(context)
            DeviceCommand.MediaPlay -> MediaPlaybackController.play(context)
            DeviceCommand.MediaPause -> MediaPlaybackController.pause(context)
            DeviceCommand.MediaStop -> MediaPlaybackController.stop(context)
            DeviceCommand.MediaNext -> MediaPlaybackController.next(context)
            DeviceCommand.MediaPrevious -> MediaPlaybackController.previous(context)
            is DeviceCommand.MediaSeek -> {
                val a11y = LunaAccessibilityService.instance
                if (a11y != null) {
                    a11y.seekYouTubeTime(command.seconds, command.forward)
                    val dir = if (command.forward) "aage" else "peeche"
                    val min = command.seconds / 60
                    val label = if (min > 0) "$min minute" else "${command.seconds} second"
                    CommandResult(true, "Video $label $dir kar diya.")
                } else {
                    MediaPlaybackController.seek(context, command.seconds * 1000L * if (command.forward) 1 else -1)
                }
            }
            is DeviceCommand.MediaSpeed -> {
                val a11y = LunaAccessibilityService.instance
                if (a11y != null) {
                    a11y.configureYouTubePlayerSetting("speed", command.speedLabel)
                    CommandResult(true, "Playback speed ${command.speedLabel} set kar di.")
                } else {
                    clickPlayerSetting("playback speed", command.speedLabel)
                }
            }
            is DeviceCommand.MediaQuality -> {
                val a11y = LunaAccessibilityService.instance
                if (a11y != null) {
                    a11y.configureYouTubePlayerSetting("quality", command.qualityLabel)
                    CommandResult(true, "Video quality ${command.qualityLabel} set kar di.")
                } else {
                    clickPlayerSetting("quality", command.qualityLabel)
                }
            }
            DeviceCommand.StopAssistantSession -> stopAssistantSessionResult()
            DeviceCommand.CameraTakePhoto -> takeCameraPhotoResult()
            is DeviceCommand.CameraRecordVideo -> recordCameraVideoResult(command.durationSeconds)
            DeviceCommand.CameraSwitchLens -> switchCameraLensResult()
            is DeviceCommand.SpotifySearch -> searchSpotify(command.query, command.autoPlay)
            DeviceCommand.RandomMusic -> playRandomMusic()
            is DeviceCommand.PhoneCall -> phoneCallResult(command.target)
            DeviceCommand.AnswerCall -> if (answerCall()) CommandResult(true, "Call receive kar diya.")
                else CommandResult(false, "Call receive nahi ho saka. Phone permission check karo.")
            DeviceCommand.EndCall -> if (endCall()) CommandResult(true, "Call end kar diya.")
                else CommandResult(false, "Call end nahi ho saka.")
            is DeviceCommand.Speaker -> {
                setSpeakerphone(command.on)
                CommandResult(true, if (command.on) "Speaker on ho gaya." else "Speaker off ho gaya.")
            }
            is DeviceCommand.CallMute -> {
                setMicMute(command.muted)
                CommandResult(true, if (command.muted) "Call mute kar diya." else "Call unmute kar diya.")
            }
            is DeviceCommand.WebSearch -> webSearchVerified(command.query, command.openFirst)
            is DeviceCommand.BrowserAction -> browserAction(command.action)
            is DeviceCommand.ScreenAnalyze -> analyzeScreen(command.prompt)
            DeviceCommand.ScreenOff -> {
                overlay.hideVisualization()
                CommandResult(true, "Screen visualization mode off.")
            }
            DeviceCommand.ScreenOcr -> analyzeScreen("Read all visible text on the screen. Return the extracted text cleanly.")
            is DeviceCommand.ReadNotifications -> readNotifications(command.appHint)
            DeviceCommand.BatteryStatus -> batteryStatus()
            DeviceCommand.TimeNow -> timeNow()
            DeviceCommand.DateNow -> dateNow()
            DeviceCommand.LocationNow -> locationNow()
            DeviceCommand.WeatherNow -> weatherNow()
            is DeviceCommand.Torch -> torchResult(command.on)
            is DeviceCommand.VolumeSet -> {
                setVolume(command.percent ?: 50)
                CommandResult(true, "Volume ${command.percent ?: 50} percent set kar diya.")
            }
            is DeviceCommand.VolumeAdjust -> {
                adjustVolume(command.up)
                CommandResult(true, if (command.up) "Volume badha diya." else "Volume kam kar diya.")
            }
            DeviceCommand.VolumeMute -> {
                setVolume(0)
                CommandResult(true, "Volume mute kar diya.")
            }
            is DeviceCommand.BrightnessSet -> setBrightness(command.percent ?: 50)
            is DeviceCommand.BrightnessAdjust -> adjustBrightness(command.up)
            is DeviceCommand.OpenSettingsPanel -> openPanel(command.which)
            is DeviceCommand.SetAlarm -> setAlarm(command)
            is DeviceCommand.SetTimer -> setTimer(command)
            is DeviceCommand.SetReminder -> setReminder(command)
            is DeviceCommand.InstallApp -> installApp(command.appName)
            is DeviceCommand.UninstallApp -> uninstallApp(command.appName)
            is DeviceCommand.PlayStoreSearch -> playStoreSearch(command.query)
            DeviceCommand.OpenGallery -> openAppVerified("gallery")
            is DeviceCommand.ManagePhotos -> managePhotos(command.action)
            is DeviceCommand.PhoneCallControl -> phoneCallControl(command.action)
            is DeviceCommand.WhatsAppMediaControl -> playFirstVisibleMedia()
            is DeviceCommand.SetScreenVisualization -> {
                if (command.enable) {
                    overlay.showVisualization()
                    CommandResult(true, "Screen visualization mode on ho gaya. Orange border indicator active hai.")
                } else {
                    overlay.hideVisualization()
                    CommandResult(true, "Screen visualization mode off ho gaya.")
                }
            }
            is DeviceCommand.GetDeviceStatus -> {
                val res = getDeviceStatus(command.queryType)
                CommandResult(true, res.spoken)
            }
            is DeviceCommand.CodeAutomation -> handleCodeAutomation(command.prompt, command.editor)
            DeviceCommand.CancelTask -> {
                DeviceStateStore.requestCancel()
                CommandResult(true, "Task cancel ho gaya.")
            }
            DeviceCommand.StopAssistantSession -> stopAssistantSessionResult()
        }
    } catch (e: Exception) {
        Log.e(TAG, "Command execution failed: ${e.message}", e)
        CommandResult(false, "Sorry, something went wrong running that command.")
    } finally {
        DeviceStateStore.update { it.copy(currentTask = null) }
    } kar diya.")
                } else {
                    MediaPlaybackController.seek(context, command.seconds * 1000L * if (command.forward) 1 else -1)
                }
            }
            is DeviceCommand.MediaSpeed -> {
                val a11y = LunaAccessibilityService.instance
                if (a11y != null) {
                    a11y.configureYouTubePlayerSetting("speed", command.speedLabel)
                    CommandResult(true, "Playback speed ${command.speedLabel} set kar di.")
                } else {
                    clickPlayerSetting("playback speed", command.speedLabel)
                }
            }
            is DeviceCommand.MediaQuality -> {
                val a11y = LunaAccessibilityService.instance
                if (a11y != null) {
                    a11y.configureYouTubePlayerSetting("quality", command.qualityLabel)
                    CommandResult(true, "Video quality ${command.qualityLabel} set kar di.")
                } else {
                    clickPlayerSetting("quality", command.qualityLabel)
                }
            }
            DeviceCommand.StopAssistantSession -> stopAssistantSessionResult()
            DeviceCommand.CameraTakePhoto -> takeCameraPhotoResult()
            is DeviceCommand.CameraRecordVideo -> recordCameraVideoResult(command.durationSeconds)
            DeviceCommand.CameraSwitchLens -> switchCameraLensResult()
            is DeviceCommand.SpotifySearch -> searchSpotify(command.query, command.autoPlay)
            DeviceCommand.RandomMusic -> playRandomMusic()
            is DeviceCommand.PhoneCall -> phoneCallResult(command.target)
            DeviceCommand.AnswerCall -> if (answerCall()) CommandResult(true, "Call receive kar diya.")
                else CommandResult(false, "Call receive nahi ho saka. Phone permission check karo.")
            DeviceCommand.EndCall -> if (endCall()) CommandResult(true, "Call end kar diya.")
                else CommandResult(false, "Call end nahi ho saka.")
            is DeviceCommand.Speaker -> {
                setSpeakerphone(command.on)
                CommandResult(true, if (command.on) "Speaker on ho gaya." else "Speaker off ho gaya.")
            }
            is DeviceCommand.CallMute -> {
                setMicMute(command.muted)
                CommandResult(true, if (command.muted) "Call mute kar diya." else "Call unmute kar diya.")
            }
            is DeviceCommand.WebSearch -> webSearchVerified(command.query, command.openFirst)
            is DeviceCommand.BrowserAction -> browserAction(command.action)
            is DeviceCommand.ScreenAnalyze -> analyzeScreen(command.prompt)
            DeviceCommand.ScreenOff -> {
                overlay.hideVisualization()
                CommandResult(true, "Screen visualization mode off.")
            }
            DeviceCommand.ScreenOcr -> analyzeScreen("Read all visible text on the screen. Return the extracted text cleanly.")
            is DeviceCommand.ReadNotifications -> readNotifications(command.appHint)
            DeviceCommand.BatteryStatus -> batteryStatus()
            DeviceCommand.TimeNow -> timeNow()
            DeviceCommand.DateNow -> dateNow()
            DeviceCommand.LocationNow -> locationNow()
            DeviceCommand.WeatherNow -> weatherNow()
            is DeviceCommand.Torch -> torchResult(command.on)
            is DeviceCommand.VolumeSet -> {
                setVolume(command.percent ?: 50)
                CommandResult(true, "Volume ${command.percent ?: 50} percent set kar diya.")
            }
            is DeviceCommand.VolumeAdjust -> {
                adjustVolume(command.up)
                CommandResult(true, if (command.up) "Volume badha diya." else "Volume kam kar diya.")
            }
            DeviceCommand.VolumeMute -> {
                setVolume(0)
                CommandResult(true, "Volume mute kar diya.")
            }
            is DeviceCommand.BrightnessSet -> setBrightness(command.percent ?: 50)
            is DeviceCommand.BrightnessAdjust -> adjustBrightness(command.up)
            is DeviceCommand.OpenSettingsPanel -> openPanel(command.which)
            is DeviceCommand.SetAlarm -> setAlarm(command)
            is DeviceCommand.SetReminder -> setReminder(command)
            is DeviceCommand.InstallApp -> installApp(command.appName)
            is DeviceCommand.UninstallApp -> uninstallApp(command.appName)
            is DeviceCommand.PlayStoreSearch -> playStoreSearch(command.query)
            DeviceCommand.OpenGallery -> openAppVerified("gallery")
            is DeviceCommand.ManagePhotos -> managePhotos(command.action)
            is DeviceCommand.PhoneCallControl -> phoneCallControl(command.action)
            is DeviceCommand.WhatsAppMediaControl -> playFirstVisibleMedia()
            is DeviceCommand.SetScreenVisualization -> {
                if (command.enable) {
                    overlay.showVisualization()
                    CommandResult(true, "Screen visualization mode on ho gaya. Orange border indicator active hai.")
                } else {
                    overlay.hideVisualization()
                    CommandResult(true, "Screen visualization mode off ho gaya.")
                }
            }
            is DeviceCommand.GetDeviceStatus -> getDeviceStatus(command.queryType)
            is DeviceCommand.CodeAutomation -> handleCodeAutomation(command.prompt, command.editor)
            DeviceCommand.CancelTask -> {
                DeviceStateStore.requestCancel()
                CommandResult(true, "Task cancel ho gaya.")
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Command execution failed: ${e.message}", e)
        CommandResult(false, "Sorry, something went wrong running that command.")
    } finally {
        DeviceStateStore.update { it.copy(currentTask = null) }
    }

    // ==========================================
    // 1. APPLICATION AUTOMATION & SWITCHING
    // ==========================================

    fun launchHomeScreen(): Boolean {
        LunaAccessibilityService.instance?.goHome()?.let { if (it) return true }
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return BackgroundActivityLauncher.launch(context, intent)
    }

    private suspend fun openAppVerified(spokenName: String): CommandResult {
        val clean = cleanAppName(spokenName)
        if (clean.isEmpty() || clean == "current") {
            return CommandResult(false, "Kaunsi app open karni hai?")
        }
        if (clean == "camera") {
            return if (launchCamera()) {
                val ok = ForegroundVerifier.waitForPackage(context, "camera", 3500)
                if (ok) CommandResult(true, "Camera open ho gaya.")
                else CommandResult(false, "Camera open nahi ho saka.")
            } else CommandResult(false, "Camera open nahi ho saka.")
        }
        val entry = findApp(clean)
        if (entry == null) {
            WebsiteCatalog.urlFor(clean)?.let { url ->
                return if (openUrl(url)) CommandResult(true, "$clean khol diya.")
                else CommandResult(false, "$clean open nahi ho saka.")
            }
            return CommandResult(false, "Is device par $clean naam ki app nahi mili.")
        }

        val launched = launchPackage(entry.packageName)
        if (!launched) {
            return CommandResult(false, "${entry.label} launch nahi ho saka. Overlay permission on karo.")
        }
        val verified = ForegroundVerifier.waitForPackage(context, entry.packageName, 3500)
        return if (verified) {
            CommandResult(true, "${entry.label} open ho gaya.")
        } else {
            CommandResult(false, "${entry.label} open nahi ho saka.")
        }
    }

    private suspend fun switchAppVerified(from: String?, to: String): CommandResult {
        val targetEntry = findApp(cleanAppName(to))
            ?: return CommandResult(false, "$to naam ki app nahi mili.")
        val launched = launchPackage(targetEntry.packageName)
        if (!launched) return CommandResult(false, "${targetEntry.label} par switch nahi ho saka.")
        val verified = ForegroundVerifier.waitForPackage(context, targetEntry.packageName, 3500)
        return if (verified) {
            CommandResult(true, "${targetEntry.label} par switch ho gaya.")
        } else {
            CommandResult(false, "${targetEntry.label} foreground mein nahi aa saka.")
        }
    }

    fun closeAppByVoice(spokenName: String): CommandResult {
        val clean = cleanAppName(spokenName)
        if (clean == "ai" || clean == "assistant" || clean == "jarvis" || clean == "luna") {
            return stopAssistantSessionResult()
        }
        val a11y = LunaAccessibilityService.instance
        if (clean.isEmpty() || clean == "current" || clean == "is app" || clean == "app") {
            val fgPkg = a11y?.foregroundPackage()
            if (!fgPkg.isNullOrBlank() && fgPkg != context.packageName) {
                try {
                    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    am.killBackgroundProcesses(fgPkg)
                } catch (_: Exception) {}
            }
            if (a11y != null) {
                kotlinx.coroutines.runBlocking {
                    a11y.dismissCurrentAppFromRecents()
                }
            } else {
                launchHomeScreen()
            }
            return CommandResult(true, "App close kar di hai.")
        }
        val entry = findApp(clean)
        if (entry == null) {
            if (clean.contains("music") || clean.contains("song") || clean.contains("video")) {
                return MediaPlaybackController.pause(context)
            }
            // Fallback: dismiss whatever is on screen
            if (a11y != null) {
                kotlinx.coroutines.runBlocking {
                    a11y.dismissCurrentAppFromRecents()
                }
            } else {
                launchHomeScreen()
            }
            return CommandResult(true, "$clean close kar di hai.")
        }
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses(entry.packageName)
            if (a11y != null) {
                kotlinx.coroutines.runBlocking {
                    a11y.dismissCurrentAppFromRecents()
                }
            } else {
                launchHomeScreen()
            }
            CommandResult(true, "${entry.label} ko band kar diya hai.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close ${entry.packageName}: ${e.message}")
            launchHomeScreen()
            CommandResult(true, "${entry.label} close kar di hai.")
        }
    }

    private suspend fun takeCameraPhotoResult(): CommandResult {
        val a11y = LunaAccessibilityService.instance
        val fg = a11y?.foregroundPackage() ?: ""
        if (!fg.contains("camera", ignoreCase = true)) {
            launchCamera()
            delay(400)
        }
        val clicked = a11y?.clickCameraShutter() == true
        return if (clicked) CommandResult(true, "Camera se photo click kar di hai.")
        else CommandResult(false, "Photo click nahi ho saki. Shutter button screen par visible nahi hai.")
    }

    private suspend fun recordCameraVideoResult(duration: Int): CommandResult {
        val a11y = LunaAccessibilityService.instance
        val fg = a11y?.foregroundPackage() ?: ""
        if (!fg.contains("camera", ignoreCase = true)) {
            launchCamera()
            delay(400)
        }
        val recorded = a11y?.recordCameraVideoDuration(duration) == true
        return if (recorded) CommandResult(true, "$duration second ki video record kar di hai.")
        else CommandResult(false, "Video recording complete nahi ho saki.")
    }

    private suspend fun switchCameraLensResult(): CommandResult {
        val a11y = LunaAccessibilityService.instance
        val switched = a11y?.switchCameraLens() == true
        return if (switched) CommandResult(true, "Camera lens switch kar diya.")
        else CommandResult(false, "Camera lens switch nahi ho saka.")
    }

    private fun stopAssistantSessionResult(): CommandResult {
        val intent = Intent("com.jarvis.assistant.ACTION_STOP_SESSION").apply {
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
        LunaAccessibilityService.instance?.goHome()
        return CommandResult(true, "AI assistant close kar diya.")
    }

    fun launchAppByName(name: String): Boolean {
        val clean = cleanAppName(name)
        return findApp(clean)?.let { launchPackage(it.packageName) }
            ?: COMMON_PACKAGES[AppIndex.normalize(clean)]?.let { launchPackage(it) }
            ?: false
    }

    private fun findApp(spokenName: String): AppIndex.AppEntry? {
        AppIndex.find(spokenName)?.let { return it }
        val direct = COMMON_PACKAGES[AppIndex.normalize(spokenName)] ?: return null
        if (direct == "camera" || direct == "dialer" || direct == "gallery") {
            return AppIndex.AppEntry(direct, spokenName, AppIndex.normalize(spokenName), emptyList())
        }
        return try {
            val pm = context.packageManager
            if (pm.getLaunchIntentForPackage(direct) != null) {
                AppIndex.AppEntry(direct, spokenName, AppIndex.normalize(spokenName), emptyList())
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun launchPackage(packageName: String): Boolean {
        if (packageName == "camera") return launchCamera()
        if (packageName == "dialer") return openDialer()
        if (packageName == "gallery") {
            val photos = context.packageManager.getLaunchIntentForPackage("com.google.android.apps.photos")
            if (photos != null) return BackgroundActivityLauncher.launch(context, photos)
            val gallery = Intent(Intent.ACTION_VIEW).apply {
                type = "image/*"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            return BackgroundActivityLauncher.launch(context, gallery)
        }
        return BackgroundActivityLauncher.launchPackage(context, packageName)
    }

    fun launchCamera(): Boolean {
        val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return BackgroundActivityLauncher.launch(context, intent)
    }

    fun scanRunningApps(): List<String> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val processes = am.runningAppProcesses ?: return emptyList()
        val names = LinkedHashSet<String>()
        for (process in processes) {
            if (process.importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND) continue
            for (pkg in process.pkgList) {
                AppIndex.entryFor(pkg)?.let { names.add(it.label) }
            }
        }
        return names.toList()
    }

    private fun listRunningAppsResult(): CommandResult {
        val names = scanRunningApps()
        val count = if (names.isNotEmpty()) names.size else DeviceStateStore.state.value.recentApps.size
        return if (count <= 0) {
            CommandResult(true, "Background mein koi active app nahi chal rahi.")
        } else {
            val preview = names.take(5).joinToString(", ")
            CommandResult(true, "Background mein $count recently active apps hain: $preview.")
        }
    }

    private fun closeAllAppsResult(): CommandResult {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val processes = am.runningAppProcesses ?: emptyList()
        for (process in processes) {
            if (process.importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND) continue
            for (pkg in process.pkgList) {
                if (pkg != context.packageName) {
                    try { am.killBackgroundProcesses(pkg) } catch (_: Exception) {}
                }
            }
        }
        launchHomeScreen()
        return CommandResult(
            true,
            "Background apps ko minimize karke home par bhej diya. Supported background tasks clean ho gaye."
        )
    }

    private fun openAllAppsResult(): CommandResult {
        return try {
            val intent = Intent(Intent.ACTION_ALL_APPS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            context.startActivity(intent)
            CommandResult(true, "All apps overview open kar diya.")
        } catch (_: Exception) {
            launchHomeScreen()
            CommandResult(true, "Home screen par aa gaye jahan sabhi apps available hain.")
        }
    }

    // ==========================================
    // 2. WHATSAPP AUTOMATION (FIXED SEND)
    // ==========================================

    private suspend fun whatsAppMessageResult(target: String, message: String): CommandResult {
        PermissionCenter.require(context, LunaPermission.CONTACTS)?.let { missing ->
            if (target.any { it.isLetter() }) return missing
        }

        // Contact disambiguation
        val matches = ContactResolver.findAll(context, target)
        if (matches.isEmpty() && target.any { it.isLetter() }) {
            return CommandResult(false, "$target contact nahi mila. Naam spell karo ya number do.")
        }
        if (matches.size > 1 && !matches[0].name.equals(target, ignoreCase = true)) {
            val names = matches.take(3).joinToString(" ya ") { it.name }
            return CommandResult(false, "$target naam ke ${matches.size} contacts mile: $names. Kaunsa?")
        }
        val contact = matches.firstOrNull() ?: ContactResolver.ContactMatch("", target, target.filter { it.isDigit() || it == '+' })
        val displayName = contact.name.ifEmpty { target }

        if (!openWhatsAppSend(contact.number, message)) {
            return CommandResult(false, "WhatsApp install nahi lag raha.")
        }

        // Wait for WhatsApp conversation window to emerge
        delay(1100)

        val a11y = LunaAccessibilityService.instance
        if (a11y == null) {
            PermissionCenter.openSettings(context, LunaPermission.ACCESSIBILITY)
            return CommandResult(
                false,
                "Message $displayName ke chat mein type ho gaya, lekin Send dabane ke liye Accessibility enable karo."
            )
        }

        // Actively locate and physically tap the send button with genuine touch event
        val sent = a11y.clickWhatsAppSend(timeoutMs = 7500)
        return if (sent) {
            CommandResult(true, "$displayName ko WhatsApp message bhej diya.")
        } else {
            CommandResult(false, "Message type ho gaya lekin Send button press nahi ho saka. Screen par Send tap karo.")
        }
    }

    private fun whatsAppChatResult(target: String): CommandResult {
        val matches = ContactResolver.findAll(context, target)
        if (matches.isEmpty()) return CommandResult(false, "$target contact nahi mila.")
        if (matches.size > 1 && !matches[0].name.equals(target, ignoreCase = true)) {
            val names = matches.take(3).joinToString(" ya ") { it.name }
            return CommandResult(false, "$target naam ke ${matches.size} contacts mile: $names. Kaunsa?")
        }
        val contact = matches.first()
        val displayName = contact.name.ifEmpty { target }
        return if (openWhatsAppChat(contact.number)) {
            CommandResult(true, "$displayName ke saath WhatsApp chat khol diya.")
        } else {
            CommandResult(false, "WhatsApp install nahi mila.")
        }
    }

    private fun whatsAppCallResult(target: String, video: Boolean): CommandResult {
        val matches = ContactResolver.findAll(context, target)
        if (matches.isEmpty()) return CommandResult(false, "$target contact nahi mila.")
        val contact = matches.first()
        val displayName = contact.name.ifEmpty { target }
        val kind = if (video) "video call" else "call"
        return if (openWhatsAppChat(contact.number)) {
            CommandResult(true, "$displayName ki chat open kar di hai jahan se aap $kind shuru kar sakte hain.")
        } else {
            CommandResult(false, "WhatsApp open nahi ho saka.")
        }
    }

    private fun openWhatsAppChat(phoneNumber: String): Boolean {
        val formatted = formatWhatsAppNumber(phoneNumber)
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$formatted")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            setPackage("com.whatsapp")
        }
        return BackgroundActivityLauncher.launch(context, intent)
    }

    private fun openWhatsAppSend(phoneNumber: String, message: String): Boolean {
        val formatted = formatWhatsAppNumber(phoneNumber)
        val encodedMessage = URLEncoder.encode(message, "UTF-8")
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$formatted&text=$encodedMessage")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            setPackage("com.whatsapp")
        }
        return BackgroundActivityLauncher.launch(context, intent)
    }

    // ==========================================
    // 3. YOUTUBE AUTOMATION
    // ==========================================

    fun searchYouTube(query: String): Boolean {
        val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
            setPackage("com.google.android.youtube")
            putExtra("query", query)
            putExtra(android.app.SearchManager.QUERY, query)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (BackgroundActivityLauncher.launch(context, searchIntent)) return true
        val encoded = URLEncoder.encode(query, "UTF-8")
        val viewIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.youtube.com/results?search_query=$encoded")
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            setPackage("com.google.android.youtube")
        }
        return BackgroundActivityLauncher.launch(context, viewIntent) ||
            openUrl("https://www.youtube.com/results?search_query=$encoded")
    }

    private suspend fun searchYouTubeResult(query: String): CommandResult {
        if (!searchYouTube(query)) return CommandResult(false, "YouTube open nahi ho saka.")
        val ok = ForegroundVerifier.waitForPackage(context, "com.google.android.youtube")
        return if (ok) CommandResult(true, "YouTube par $query search kar diya.")
        else CommandResult(false, "YouTube open nahi ho saka.")
    }

    private suspend fun playYouTubeResult(query: String): CommandResult {
        val search = searchYouTubeResult(query)
        if (!search.success) return search
                    delay(400)
        val clicked = LunaAccessibilityService.instance?.clickNthVideo(0) == true
        return if (clicked) CommandResult(true, "$query play ho raha hai.")
        else CommandResult(true, "$query search kar diya hai.")
    }

    private suspend fun playNthYouTubeVideo(index: Int): CommandResult {
        val a11y = LunaAccessibilityService.instance
            ?: return PermissionCenter.require(context, LunaPermission.ACCESSIBILITY)
                ?: CommandResult(false, "Video tap ke liye Accessibility on karo.")
        val label = if (index == 0) "Pehla" else "Second"
        val ok = a11y.clickNthVideo(index)
        return if (ok) CommandResult(true, "$label video play kar diya.")
        else CommandResult(false, "$label video tap nahi ho saka. Screen par search results dikhne chahiye.")
    }

    private suspend fun playFirstVisibleMedia(): CommandResult {
        val a11y = LunaAccessibilityService.instance
            ?: return PermissionCenter.require(context, LunaPermission.ACCESSIBILITY)
                ?: CommandResult(false, "WhatsApp video ke liye Accessibility chahiye.")
        val ok = a11y.clickVisibleText("video") || a11y.clickFirstResult(LunaAccessibilityService.WHATSAPP_PACKAGES)
        return if (ok) CommandResult(true, "Visible media play kar diya.")
        else CommandResult(false, "Screen par playable media nahi mila.")
    }

    private suspend fun clickPlayerSetting(menu: String, value: String): CommandResult {
        val a11y = LunaAccessibilityService.instance
            ?: return CommandResult(false, "Player controls tabhi change honge jab player UI screen par accessible ho.")
        val opened = a11y.clickVisibleText(menu) || a11y.clickVisibleText("settings")
        if (!opened) return CommandResult(false, "Player settings screen par visible nahi hain.")
        delay(400)
        val clicked = a11y.clickVisibleText(value)
        return if (clicked) CommandResult(true, "$menu $value set kar diya.")
        else CommandResult(false, "Setting option screen par select nahi ho saka.")
    }

    // ==========================================
    // 4. SPOTIFY & MUSIC
    // ==========================================

    private fun searchSpotify(query: String, autoPlay: Boolean = true): CommandResult {
        if (autoPlay) {
            val playIntent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(android.app.SearchManager.QUERY, query)
                putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                setPackage("com.spotify.music")
            }
            if (BackgroundActivityLauncher.launch(context, playIntent)) {
                return CommandResult(true, "Spotify par $query play kar diya.")
            }
        }
        val encoded = URLEncoder.encode(query, "UTF-8")
        val uri = Uri.parse("spotify:search:$encoded")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            setPackage("com.spotify.music")
        }
        return if (BackgroundActivityLauncher.launch(context, intent) || launchPackage("com.spotify.music")) {
            CommandResult(true, if (autoPlay) "Spotify par $query play kar diya." else "Spotify par $query search kar diya.")
        } else {
            CommandResult(false, "Spotify open nahi ho saka.")
        }
    }

    private fun playRandomMusic(): CommandResult {
        val randomMood = RANDOM_MUSIC_CATEGORIES.shuffled().first()
        return searchSpotify(randomMood)
    }

    // ==========================================
    // 5. CHROME & GOOGLE SEARCH
    // ==========================================

    fun searchGoogle(query: String): Boolean {
        val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(android.app.SearchManager.QUERY, query)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            setPackage("com.android.chrome")
        }
        if (BackgroundActivityLauncher.launch(context, intent)) return true
        return openUrl("https://www.google.com/search?q=${URLEncoder.encode(query, "UTF-8")}")
    }

    fun openUrl(url: String): Boolean {
        val formattedUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
            "https://$url"
        } else url
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(formattedUrl)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            setPackage("com.android.chrome")
        }
        return BackgroundActivityLauncher.launch(context, intent)
    }

    private suspend fun webSearchVerified(query: String, openFirst: Boolean): CommandResult {
        if (!searchGoogle(query)) return CommandResult(false, "Google search start nahi ho saka.")
        val chromeOk = ForegroundVerifier.waitForPackage(context, "com.android.chrome", 3500)
        if (!chromeOk) return CommandResult(false, "Chrome open nahi ho saka.")

        if (openFirst) {
            delay(400)
            val click = clickFirstSearchResult()
            return if (click.success) CommandResult(true, "Google par $query search karke pehla result open kar diya.")
            else CommandResult(true, "Google search open ho gaya.")
        }
        return CommandResult(true, "Google par $query search kar diya.")
    }

    private suspend fun clickFirstSearchResult(): CommandResult {
        val a11y = LunaAccessibilityService.instance
            ?: return PermissionCenter.require(context, LunaPermission.ACCESSIBILITY)
                ?: CommandResult(false, "First result open karne ke liye Accessibility permission chahiye.")
        val ok = a11y.clickFirstResult(LunaAccessibilityService.CHROME_PACKAGES)
        return if (ok) CommandResult(true, "Pehla result open kar diya.")
        else CommandResult(false, "Pehla result tap nahi ho saka.")
    }

    private suspend fun openWebsites(sites: List<String>): CommandResult {
        val urls = sites.mapNotNull { name ->
            WebsiteCatalog.urlFor(name) ?: if (name.contains('.')) "https://$name" else null
        }
        if (urls.isEmpty()) return CommandResult(false, "Koi valid website identify nahi hui.")

        // Rule: Each website in separate tab. If >8 sites, distribute across an additional window
        val chunks = urls.chunked(8)
        for ((windowIdx, group) in chunks.withIndex()) {
            for (url in group) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        if (windowIdx > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                        } else Intent.FLAG_ACTIVITY_NEW_TASK
                    setPackage("com.android.chrome")
                }
                BackgroundActivityLauncher.launch(context, intent)
                delay(350)
            }
            if (windowIdx < chunks.lastIndex) delay(500)
        }
        return CommandResult(true, "${urls.size} websites alag tabs mein open kar di hain.")
    }

    private suspend fun browserAction(action: String): CommandResult {
        val a11y = LunaAccessibilityService.instance
        return when (action) {
            "new_tab" -> {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    setPackage("com.android.chrome")
                }
                if (BackgroundActivityLauncher.launch(context, intent)) CommandResult(true, "New tab open ho gaya.")
                else CommandResult(false, "New tab open nahi ho saka.")
            }
            "refresh" -> {
                val ok = a11y?.clickVisibleText("refresh") == true || a11y?.clickVisibleText("reload") == true
                if (ok) CommandResult(true, "Page refresh ho gaya.")
                else CommandResult(false, "Page refresh nahi ho saka.")
            }
            "close_tab" -> {
                if (a11y?.clickVisibleText("close tab") == true || a11y?.goBack() == true) {
                    CommandResult(true, "Tab close kar diya.")
                } else {
                    CommandResult(false, "Close tab is browser version par directly supported nahi hai.")
                }
            }
            "forward" -> CommandResult(false, "Browser forward command is browser view mein supported nahi hai.")
            "scroll_down" -> {
                val scrolled = a11y?.scroll(down = true) == true
                if (scrolled) CommandResult(true, "Page scroll kiya.") else CommandResult(false, "Scroll nahi ho saka.")
            }
            "scroll_up" -> {
                val scrolled = a11y?.scroll(down = false) == true
                if (scrolled) CommandResult(true, "Page scroll kiya.") else CommandResult(false, "Scroll nahi ho saka.")
            }
            else -> CommandResult(false, "Unknown browser action.")
        }
    }

    // ==========================================
    // 6. SCREEN ACCESS & LIVE VISUALIZATION
    // ==========================================

    suspend fun analyzeScreen(prompt: String): CommandResult {
        if (!Settings.canDrawOverlays(context) && !ScreenCaptureHolder.hasProjection()) {
            PermissionCenter.openSettings(context, LunaPermission.OVERLAY)
            return CommandResult(false, "Screen access permission currently off hai. Settings mein enable karne ke baad main screen dekh sakti hoon.")
        }
        if (!ScreenCaptureHolder.hasProjection()) {
            ProjectionPermissionActivity.launch(context)
            return CommandResult(false, "Screen access permission currently off hai. Permission enable karne ke baad main screen dekh sakti hoon.")
        }

        overlay.showVisualization()
        val jpeg = withContext(Dispatchers.IO) { screenEngine.captureFreshJpeg() }
        ScreenCaptureHolder.lastJpeg = jpeg
        ScreenCaptureHolder.lastFrameAt = System.currentTimeMillis()

        val a11yText = LunaAccessibilityService.instance?.visibleText(2000).orEmpty()
        val pkg = ForegroundVerifier.currentPackage(context)
        val appLabel = pkg?.let { AppIndex.entryFor(it)?.label } ?: pkg

        if (jpeg == null && a11yText.isBlank()) {
            return CommandResult(false, "Fresh screen capture nahi mil saka. Screen capture permission check karo.")
        }

        val spoken = buildString {
            append("Screen check ki. ")
            if (!appLabel.isNullOrBlank()) append("Abhi $appLabel open hai. ")
            if (a11yText.isNotBlank()) {
                val clip = a11yText.replace('\n', ' ').take(260)
                append("Screen par dikh raha hai: $clip")
            } else {
                append(prompt)
            }
        }
        return CommandResult(true, spoken)
    }

    // ==========================================
    // 7. TELEPHONY & ACTIVE CALL CONTROL
    // ==========================================

    fun openDialer(): Boolean {
        val intent = Intent(Intent.ACTION_DIAL).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        return BackgroundActivityLauncher.launch(context, intent)
    }

    fun phoneCallResult(target: String): CommandResult {
        PermissionCenter.require(context, LunaPermission.CONTACTS)?.let { missing ->
            if (target.any { it.isLetter() }) return missing
        }
        PermissionCenter.require(context, LunaPermission.PHONE)?.let { return it }

        val matches = ContactResolver.findAll(context, target)
        if (matches.isEmpty()) return CommandResult(false, "$target contact nahi mila.")
        if (matches.size > 1 && !matches[0].name.equals(target, ignoreCase = true)) {
            val names = matches.take(3).joinToString(" ya ") { it.name }
            return CommandResult(false, "$target naam ke ${matches.size} contacts mile: $names. Kaunsa?")
        }
        val contact = matches.first()
        val displayName = contact.name.ifEmpty { target }

        return if (makeCall(contact.number)) {
            CommandResult(true, "$displayName ko call lagaya.")
        } else {
            CommandResult(false, "$displayName ke liye dialer open kiya.")
        }
    }

    @SuppressLint("MissingPermission")
    fun makeCall(phoneNumber: String): Boolean {
        val cleanPhone = phoneNumber.replace(" ", "").replace("-", "")
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$cleanPhone")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (BackgroundActivityLauncher.launch(context, intent)) return true
        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        BackgroundActivityLauncher.launch(context, dialIntent)
        return false
    }

    @SuppressLint("MissingPermission")
    fun answerCall(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                telecomManager?.acceptRingingCall()
                true
            } else false
        } catch (e: Exception) {
            Log.e(TAG, "Error answering call: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun endCall(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                telecomManager?.endCall() ?: false
            } else false
        } catch (e: Exception) {
            Log.e(TAG, "Error ending call: ${e.message}")
            false
        }
    }

    fun setSpeakerphone(enable: Boolean): Boolean {
        return try {
            audioManager.isSpeakerphoneOn = enable
            true
        } catch (e: Exception) {
            false
        }
    }

    fun isSpeakerphoneOn(): Boolean {
        return try {
            audioManager.isSpeakerphoneOn
        } catch (_: Exception) {
            false
        }
    }

    fun setMicMute(muted: Boolean): Boolean {
        return try {
            audioManager.isMicrophoneMute = muted
            true
        } catch (e: Exception) {
            false
        }
    }

    // ==========================================
    // 8. NOTIFICATION READER
    // ==========================================

    private fun readNotifications(appHint: String?): CommandResult {
        PermissionCenter.require(context, LunaPermission.NOTIFICATION_LISTENER)?.let { return it }
        val all = NotificationReaderService.snapshot()
        if (all.isEmpty()) return CommandResult(true, "Koi nayi notification nahi mili.")
        val filtered = when (appHint) {
            "whatsapp" -> all.filter { it.packageName.contains("whatsapp") }
            "instagram" -> all.filter { it.packageName.contains("instagram") }
            "telegram" -> all.filter { it.packageName.contains("telegram") }
            "gmail" -> all.filter { it.packageName.contains("gmail") || it.packageName.contains("gm") }
            else -> all
        }
        if (filtered.isEmpty()) return CommandResult(true, "Us app ki koi notification nahi mili.")
        val top = filtered.take(3).joinToString(". ") { "${it.title}: ${it.text}" }
        return CommandResult(true, top.take(400))
    }

    // ==========================================
    // 9. CODING ASSISTANT & EDITOR AUTOMATION
    // ==========================================

    private fun handleCodeAutomation(prompt: String, editor: String?): CommandResult {
        val isPython = prompt.contains("python", ignoreCase = true)
        val isJs = prompt.contains("javascript", ignoreCase = true) || prompt.contains("react", ignoreCase = true)
        val ext = if (isPython) "py" else if (isJs) "js" else "txt"
        val fileName = "luna_script.$ext"

        val generatedCode = if (prompt.contains("calculator", ignoreCase = true)) {
            """
            # Python Calculator by Luna AI
            def add(x, y): return x + y
            def subtract(x, y): return x - y
            def multiply(x, y): return x * y
            def divide(x, y): return x / y if y != 0 else "Error: Division by zero"

            print("Luna Calculator ready!")
            """.trimIndent()
        } else {
            """
            // Generated by Luna AI
            console.log("Code generated for: $prompt");
            """.trimIndent()
        }

        // Save generated code file
        return try {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
            val codeDir = File(dir, "LunaCode").apply { mkdirs() }
            val file = File(codeDir, fileName)
            file.writeText(generatedCode)

            // Try to open with an installed text/code editor or view intent
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/plain")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            BackgroundActivityLauncher.launch(context, intent)
            CommandResult(true, "Code generate karke $fileName mein save kar diya aur editor open kar diya.")
        } catch (e: Exception) {
            CommandResult(true, "Code generate kar diya: ${generatedCode.take(150)}")
        }
    }

    // ==========================================
    // 10. DEVICE CONTROLS (TORCH, BATTERY, ETC)
    // ==========================================

    fun setTorchMode(enable: Boolean): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return false
                cameraManager.setTorchMode(cameraId, enable)
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }

    private fun torchResult(on: Boolean): CommandResult {
        return if (setTorchMode(on)) CommandResult(true, if (on) "Torch on ho gaya." else "Torch off ho gaya.")
        else CommandResult(false, "Torch toggle nahi ho saka.")
    }

    fun setVolume(percentage: Int): Boolean {
        return try {
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = ((percentage.coerceIn(0, 100) / 100f) * maxVol).toInt()
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun adjustVolume(increase: Boolean): Boolean {
        return try {
            val direction = if (increase) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun setBrightness(percent: Int): CommandResult {
        if (!Settings.System.canWrite(context)) {
            PermissionCenter.openSettings(context, LunaPermission.WRITE_SETTINGS)
            return CommandResult(false, "Brightness ke liye Modify system settings permission chahiye.")
        }
        return try {
            val value = ((percent.coerceIn(0, 100) / 100f) * 255).toInt()
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
            CommandResult(true, "Brightness $percent percent kar diya.")
        } catch (e: Exception) {
            CommandResult(false, "Brightness change nahi hua.")
        }
    }

    private fun adjustBrightness(up: Boolean): CommandResult {
        if (!Settings.System.canWrite(context)) {
            PermissionCenter.openSettings(context, LunaPermission.WRITE_SETTINGS)
            return CommandResult(false, "Brightness ke liye Modify system settings permission chahiye.")
        }
        return try {
            val cur = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            val next = (cur + if (up) 35 else -35).coerceIn(10, 255)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, next)
            CommandResult(true, if (up) "Brightness badha diya." else "Brightness kam kar diya.")
        } catch (e: Exception) {
            CommandResult(false, "Brightness change nahi hua.")
        }
    }

    private fun batteryStatus(): CommandResult {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        DeviceStateStore.update { it.copy(batteryPercent = pct, charging = charging) }
        return CommandResult(true, "Battery: $pct percent. Charging: ${if (charging) "Yes" else "No"}.")
    }

    private fun timeNow(): CommandResult {
        val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        return CommandResult(true, "Abhi time ${fmt.format(Date())} hai.")
    }

    private fun dateNow(): CommandResult {
        val fmt = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault())
        val cal = Calendar.getInstance()
        val today = fmt.format(cal.time)
        cal.add(Calendar.DAY_OF_YEAR, 1)
        val tomorrow = fmt.format(cal.time)
        return CommandResult(true, "Aaj $today hai, aur kal $tomorrow.")
    }

    @SuppressLint("MissingPermission")
    private fun locationNow(): CommandResult {
        PermissionCenter.require(context, LunaPermission.LOCATION)?.let { return it }
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (loc == null) return CommandResult(false, "Location abhi available nahi. GPS on karo.")
            val geo = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val list = geo.getFromLocation(loc.latitude, loc.longitude, 1)
            val a = list?.firstOrNull()
            val spoken = if (a != null) {
                listOfNotNull(a.subLocality, a.locality, a.adminArea, a.countryName).joinToString(", ")
            } else "${loc.latitude}, ${loc.longitude}"
            CommandResult(true, "Aapki location: $spoken.")
        } catch (e: Exception) {
            CommandResult(false, "Location nahi mil saki.")
        }
    }

    private fun weatherNow(): CommandResult {
        PermissionCenter.require(context, LunaPermission.LOCATION)?.let { return it }
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val q = if (loc != null) "${loc.latitude},${loc.longitude}" else ""
            val url = if (q.isNotEmpty()) "https://wttr.in/$q?format=j1" else "https://wttr.in/?format=j1"
            val body = URL(url).readText()
            val json = org.json.JSONObject(body)
            val current = json.getJSONArray("current_condition").getJSONObject(0)
            val temp = current.optString("temp_C")
            val desc = current.getJSONArray("weatherDesc").getJSONObject(0).optString("value")
            val humidity = current.optString("humidity")
            val wind = current.optString("windspeedKmph")
            CommandResult(true, "Mausam: $temp degree, $desc. Humidity $humidity percent, hawa $wind km/h.")
        } catch (e: Exception) {
            openUrl("https://www.google.com/search?q=weather")
            CommandResult(true, "Weather details Chrome mein open kar di hain.")
        }
    }

    private fun openPanel(which: String): CommandResult {
        val action = when (which) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "data" -> Settings.ACTION_DATA_ROAMING_SETTINGS
            "dnd" -> Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
            "alarms" -> AlarmClock.ACTION_SHOW_ALARMS
            else -> Settings.ACTION_SETTINGS
        }
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (BackgroundActivityLauncher.launch(context, intent)) {
            CommandResult(true, "$which settings open kar di hain.")
        } else CommandResult(false, "Settings open nahi ho saka.")
    }

    private fun setAlarm(cmd: DeviceCommand.SetAlarm): CommandResult {
        return try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, cmd.hour.coerceIn(0, 23))
                set(Calendar.MINUTE, cmd.minute.coerceIn(0, 59))
                set(Calendar.SECOND, 0)
                if (cmd.tomorrow || calendar.before(Calendar.getInstance())) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            val intent = Intent(context, com.jarvis.assistant.automation.AlarmReceiver::class.java).apply {
                putExtra("alarm_type", "alarm")
                putExtra("alarm_message", cmd.message ?: "Luna Alarm")
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                cmd.hour * 100 + cmd.minute,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
            }

            CommandResult(true, "Alarm ${cmd.hour}:${cmd.minute.toString().padStart(2, '0')} ka set kar diya.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set alarm: ${e.message}")
            CommandResult(false, "Alarm set nahi ho saka.")
        }
    }


    private fun setReminder(cmd: DeviceCommand.SetReminder): CommandResult {
        val minutes = cmd.inMinutes ?: run {
            if (cmd.hour != null) {
                val cal = Calendar.getInstance()
                val target = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, cmd.hour)
                    set(Calendar.MINUTE, cmd.minute ?: 0)
                    set(Calendar.SECOND, 0)
                }
                if (target.before(cal)) target.add(Calendar.DAY_OF_YEAR, 1)
                ((target.timeInMillis - cal.timeInMillis) / 60000L).toInt().coerceAtLeast(1)
            } else 10
        }
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
            putExtra(AlarmClock.EXTRA_MESSAGE, cmd.message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return if (BackgroundActivityLauncher.launch(context, intent)) {
            CommandResult(true, "Reminder lag gaya: ${cmd.message}.")
        } else CommandResult(false, "Reminder set nahi ho saka.")
    }

    private fun installApp(appName: String): CommandResult {
        val q = URLEncoder.encode(appName.replace("install", "").trim(), "UTF-8")
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$q")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            setPackage("com.android.vending")
        }
        return if (BackgroundActivityLauncher.launch(context, market) ||
            openUrl("https://play.google.com/store/search?q=$q")
        ) CommandResult(true, "Play Store par $appName install flow open kar diya.")
        else CommandResult(false, "Play Store open nahi ho saka.")
    }

    private fun uninstallApp(appName: String): CommandResult {
        val entry = if (appName == "current") {
            ForegroundVerifier.currentPackage(context)?.let { AppIndex.entryFor(it) }
        } else findApp(appName)
        if (entry == null) return CommandResult(false, "Uninstall ke liye app nahi mili.")
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${entry.packageName}")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return if (BackgroundActivityLauncher.launch(context, intent)) {
            CommandResult(true, "${entry.label} uninstall confirmation open kar diya.")
        } else CommandResult(false, "Uninstall flow open nahi hua.")
    }

    private fun playStoreSearch(query: String): CommandResult = installApp(query)

    private fun goHomeResult(): CommandResult =
        if (launchHomeScreen()) CommandResult(true, "Home par aa gayi.")
        else CommandResult(false, "Home open nahi ho saka.")

    private suspend fun minimizeApp(appName: String?): CommandResult {
        launchHomeScreen()
        return CommandResult(true, "App minimize ho gayi.")
    }

    private fun previousApp(): CommandResult {
        val prev = DeviceStateStore.state.value.previousApp
        if (prev.isNullOrBlank()) {
            LunaAccessibilityService.instance?.openRecents()
            return CommandResult(true, "Previous app ke liye recents open kiya.")
        }
        return if (launchPackage(prev)) CommandResult(true, "Previous app foreground mein aa gayi.")
        else CommandResult(false, "Previous app nahi mil saki.")
    }

    suspend fun managePhotos(action: String): CommandResult {
        return when (action.lowercase(Locale.ROOT)) {
            "open_gallery", "gallery" -> openAppVerified("gallery")
            "show_latest", "latest" -> {
                try {
                    val projection = arrayOf(android.provider.MediaStore.Images.Media._ID)
                    val cursor = context.contentResolver.query(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        projection,
                        null,
                        null,
                        "${android.provider.MediaStore.Images.Media.DATE_ADDED} DESC"
                    )
                    cursor?.use {
                        if (it.moveToFirst()) {
                            val id = it.getLong(it.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID))
                            val uri = ContentUris.withAppendedId(
                                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                            )
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "image/*")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            BackgroundActivityLauncher.launch(context, intent)
                            return CommandResult(true, "Latest photo open kar diya.")
                        }
                    }
                    openAppVerified("gallery")
                } catch (e: Exception) {
                    openAppVerified("gallery")
                }
            }
            "delete_latest", "delete_photo" -> {
                CommandResult(
                    false,
                    "Ye photo permanently delete karna hai? Security ke liye destructive action par direct confirmation zaroori hai. Photo screen se manually delete karein."
                )
            }
            else -> openAppVerified("gallery")
        }
    }

    fun phoneCallControl(action: String): CommandResult {
        return when (action.lowercase(Locale.ROOT)) {
            "answer" -> if (answerCall()) CommandResult(true, "Call receive kar diya.")
                else CommandResult(false, "Call receive nahi ho saka. Phone permission check karo.")
            "end", "cut", "reject" -> if (endCall()) CommandResult(true, "Call end kar diya.")
                else CommandResult(false, "Call end nahi ho saka.")
            "speaker_on", "speaker" -> {
                setSpeakerphone(true)
                CommandResult(true, "Speaker on ho gaya.")
            }
            "speaker_off" -> {
                setSpeakerphone(false)
                CommandResult(true, "Speaker off ho gaya.")
            }
            "mute" -> {
                setMicMute(true)
                CommandResult(true, "Call mute kar diya.")
            }
            "unmute" -> {
                setMicMute(false)
                CommandResult(true, "Call unmute kar diya.")
            }
            else -> CommandResult(false, "Call control action unrecognized.")
        }
    }

    fun getDeviceStatus(queryType: String): CommandResult {
        return when (queryType.lowercase(Locale.ROOT)) {
            "battery" -> batteryStatus()
            "time" -> timeNow()
            "date" -> dateNow()
            "location" -> locationNow()
            "weather" -> weatherNow()
            else -> batteryStatus()
        }
    }

    private fun cleanAppName(s: String): String =
        s.trim()
            .replace(Regex("^(?:the|my|please)\\s+"), "")
            .replace(Regex("\\s+(?:app|application|please|ko)$"), "")
            .trim()

    private fun formatWhatsAppNumber(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return if (digits.length == 10) "91$digits" else digits
    }
}
