package com.jarvis.assistant.util

/** Every device action Luna can execute locally. */
sealed class DeviceCommand {
    data class OpenApp(val appName: String) : DeviceCommand()
    data class CloseApp(val appName: String) : DeviceCommand()
    data class MinimizeApp(val appName: String?) : DeviceCommand()
    data class SwitchApp(val from: String?, val to: String) : DeviceCommand()
    data class OpenWebsites(val sites: List<String>) : DeviceCommand()
    object OpenHome : DeviceCommand()
    object GoBack : DeviceCommand()
    object OpenRecents : DeviceCommand()
    object PreviousApp : DeviceCommand()
    object SplitScreen : DeviceCommand()
    object ListRunningApps : DeviceCommand()
    object CloseAllApps : DeviceCommand()
    object OpenAllApps : DeviceCommand()
    data class WhatsAppMessage(val target: String, val message: String) : DeviceCommand()
    data class WhatsAppOpenChat(val target: String) : DeviceCommand()
    data class WhatsAppCall(val target: String, val video: Boolean) : DeviceCommand()
    object WhatsAppFirstVideo : DeviceCommand()
    data class YouTubeSearch(val query: String) : DeviceCommand()
    data class YouTubePlay(val query: String) : DeviceCommand()
    object PlayFirstResult : DeviceCommand()
    object PlayFirstVideo : DeviceCommand()
    data class PlayNthVideo(val index: Int) : DeviceCommand()
    object MediaPlayPause : DeviceCommand()
    object MediaPlay : DeviceCommand()
    object MediaPause : DeviceCommand()
    object MediaStop : DeviceCommand()
    object MediaNext : DeviceCommand()
    object MediaPrevious : DeviceCommand()
    data class MediaSeek(val seconds: Int, val forward: Boolean) : DeviceCommand()
    data class MediaSpeed(val speedLabel: String) : DeviceCommand()
    data class MediaQuality(val qualityLabel: String) : DeviceCommand()
    data class SpotifySearch(val query: String, val autoPlay: Boolean = true) : DeviceCommand()
    object RandomMusic : DeviceCommand()
    data class PhoneCall(val target: String) : DeviceCommand()
    object AnswerCall : DeviceCommand()
    object EndCall : DeviceCommand()
    data class Speaker(val on: Boolean) : DeviceCommand()
    data class CallMute(val muted: Boolean) : DeviceCommand()
    data class WebSearch(val query: String, val openFirst: Boolean = false) : DeviceCommand()
    data class BrowserAction(val action: String) : DeviceCommand()
    data class ScreenAnalyze(val prompt: String) : DeviceCommand()
    object ScreenOff : DeviceCommand()
    object ScreenOcr : DeviceCommand()
    data class ReadNotifications(val appHint: String?) : DeviceCommand()
    object BatteryStatus : DeviceCommand()
    object TimeNow : DeviceCommand()
    object DateNow : DeviceCommand()
    object LocationNow : DeviceCommand()
    object WeatherNow : DeviceCommand()
    data class Torch(val on: Boolean) : DeviceCommand()
    data class VolumeSet(val percent: Int?) : DeviceCommand()
    data class VolumeAdjust(val up: Boolean) : DeviceCommand()
    object VolumeMute : DeviceCommand()
    data class BrightnessSet(val percent: Int?) : DeviceCommand()
    data class BrightnessAdjust(val up: Boolean) : DeviceCommand()
    data class OpenSettingsPanel(val which: String) : DeviceCommand()
    data class SetAlarm(val hour: Int, val minute: Int, val tomorrow: Boolean, val recurring: Boolean, val message: String?) : DeviceCommand()
    data class SetTimer(val durationSeconds: Int, val message: String?) : DeviceCommand()
    data class SetReminder(val message: String, val hour: Int?, val minute: Int?, val inMinutes: Int?) : DeviceCommand()
    data class InstallApp(val appName: String) : DeviceCommand()
    data class UninstallApp(val appName: String) : DeviceCommand()
    data class PlayStoreSearch(val query: String) : DeviceCommand()
    object OpenGallery : DeviceCommand()
    data class ManagePhotos(val action: String) : DeviceCommand()
    data class PhoneCallControl(val action: String) : DeviceCommand()
    data class WhatsAppMediaControl(val action: String) : DeviceCommand()
    data class SetScreenVisualization(val enable: Boolean) : DeviceCommand()
    data class GetDeviceStatus(val queryType: String) : DeviceCommand()
    data class CodeAutomation(val prompt: String, val editor: String? = null) : DeviceCommand()
    object CancelTask : DeviceCommand()
    object StopAssistantSession : DeviceCommand()
    object CameraTakePhoto : DeviceCommand()
    data class CameraRecordVideo(val durationSeconds: Int = 30) : DeviceCommand()
    object CameraSwitchLens : DeviceCommand()
    data class Navigate(val destination: String) : DeviceCommand()
    data class ConfigureAutoResponse(val enabled: Boolean, val message: String? = null) : DeviceCommand()
}

object WebsiteCatalog {
    val sites = mapOf(
        "google" to "https://www.google.com",
        "youtube" to "https://www.youtube.com",
        "github" to "https://github.com",
        "instagram" to "https://www.instagram.com",
        "facebook" to "https://www.facebook.com",
        "twitter" to "https://x.com",
        "x" to "https://x.com",
        "reddit" to "https://www.reddit.com",
        "gmail" to "https://mail.google.com",
        "wikipedia" to "https://www.wikipedia.org",
        "whatsapp web" to "https://web.whatsapp.com",
        "linkedin" to "https://www.linkedin.com",
        "netflix" to "https://www.netflix.com",
        "amazon" to "https://www.amazon.com"
    )

    fun urlFor(name: String): String? {
        val n = name.trim().lowercase()
        sites[n]?.let { return it }
        if (n.startsWith("http://") || n.startsWith("https://")) return n
        if (n.contains('.') && !n.contains(' ')) return "https://$n"
        return null
    }
}
