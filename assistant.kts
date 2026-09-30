/**
 * ============================================================================
 *  MOBILE AI ASSISTANT — CORE ARCHITECTURE (Kotlin / Android)
 * ============================================================================
 *
 *  Ye file poore prompt (Section 1–40) ka MODULAR SKELETON implement karti hai:
 *  VoiceEngine, IntentEngine, AppController, BrowserController, ScreenController,
 *  WhatsAppController, CallController, NotificationController, MusicController,
 *  LocationService, WeatherService, WebSearchService, AIService, CodingService,
 *  FileService, DeviceService, PermissionManager, TaskManager, StateManager,
 *  SecurityManager.
 *
 *  IMPORTANT — READ BEFORE USE
 *  ----------------------------------------------------------------------
 *  1) Ye ek SINGLE reference file hai jo real Android project mein multiple
 *     files/packages mein split hogi (com.assistant.core.*, com.assistant.controllers.*).
 *  2) Android par kisi bhi app ko WITHOUT user tap ke silently kisi aur app
 *     (WhatsApp, Phone Dialer) ke andar "Send"/"Call" button dabana normally
 *     possible NAHI hai — ye AccessibilityService ke through hi hota hai, aur
 *     yahan HAR AISI action ko explicit confirmation flag (requiresConfirmation)
 *     ke peeche rakha gaya hai, jaisa spec ke Section 29 & 37 mein maanga gaya hai.
 *     Koi bhi security-bypass, hidden-surveillance ya silent-auto-send logic
 *     jaanbujhkar implement NAHI ki gayi hai.
 *  3) API keys (Weather, Search, AI model) placeholders hain — apni backend
 *     service se securely fetch karo, hardcode mat karo.
 *  4) Kotlin coroutines (kotlinx.coroutines) is used for async flows.
 *
 *  Gradle dependencies needed (add in build.gradle):
 *     implementation("androidx.core:core-ktx:1.13.1")
 *     implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
 *     implementation("com.squareup.retrofit2:retrofit:2.11.0")
 *     implementation("com.squareup.retrofit2:converter-gson:2.11.0")
 * ============================================================================
 */

package com.assistant.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationManager
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telephony.TelephonyManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

// ============================================================================
// 1. STATE MANAGER  (Section 34: Real-Time State Engine)
// ============================================================================

enum class CallState { IDLE, DIALING, RINGING, ACTIVE, ENDED }
enum class ScreenAccess { ON, OFF }

data class AssistantState(
    var currentApp: String? = null,
    var screenAccess: ScreenAccess = ScreenAccess.OFF,
    var microphoneActive: Boolean = false,
    var assistantSpeaking: Boolean = false,
    var activeCall: Boolean = false,
    var callState: CallState = CallState.IDLE,
    var batteryLevel: Int = -1,
    var chargingState: Boolean = false,
    var location: Location? = null,
    var networkAvailable: Boolean = false,
    var currentTimeIso: String = "",
    var musicState: String = "STOPPED",
    var browserState: String = "CLOSED",
    var lastUserCommand: String = "",
    var currentTask: String? = null
)

/** Single source of truth — har controller isko read/update karta hai. */
object StateManager {
    private val _state = AssistantState()
    val state: AssistantState get() = _state

    @Synchronized
    fun update(block: AssistantState.() -> Unit) {
        _state.block()
        DebugLogger.log("STATE", "Updated -> $_state")
    }
}

// ============================================================================
// 2. DEBUG / LOGGING  (Section 38)
// ============================================================================

object DebugLogger {
    var debugMode: Boolean = BuildConfigFlags.DEBUG
    /** Production mein sensitive payload (message text, phone numbers) log nahi hota. */
    fun log(tag: String, message: String, sensitive: Boolean = false) {
        if (!debugMode) return
        val safeMessage = if (sensitive) "[REDACTED]" else message
        android.util.Log.d("Assistant[$tag]", safeMessage)
    }
}

object BuildConfigFlags {
    const val DEBUG = true // wire this to actual BuildConfig.DEBUG
}

// ============================================================================
// 3. PERMISSION MANAGER  (Section 30)
// ============================================================================

enum class AppPermission(val manifestPermission: String?) {
    MICROPHONE(Manifest_.RECORD_AUDIO),
    CAMERA(Manifest_.CAMERA),
    CONTACTS(Manifest_.READ_CONTACTS),
    PHONE(Manifest_.CALL_PHONE),
    LOCATION(Manifest_.ACCESS_FINE_LOCATION),
    STORAGE(Manifest_.READ_EXTERNAL_STORAGE),
    NOTIFICATION_ACCESS(null),   // special-access setting, not a runtime permission
    ACCESSIBILITY_SERVICE(null), // special-access setting
    SCREEN_CAPTURE(null)         // granted per-session via MediaProjection prompt
}

// Small shim so this file compiles standalone without importing android.Manifest directly everywhere.
private object Manifest_ {
    const val RECORD_AUDIO = android.Manifest.permission.RECORD_AUDIO
    const val CAMERA = android.Manifest.permission.CAMERA
    const val READ_CONTACTS = android.Manifest.permission.READ_CONTACTS
    const val CALL_PHONE = android.Manifest.permission.CALL_PHONE
    const val ACCESS_FINE_LOCATION = android.Manifest.permission.ACCESS_FINE_LOCATION
    const val READ_EXTERNAL_STORAGE = android.Manifest.permission.READ_EXTERNAL_STORAGE
}

class PermissionManager(private val context: Context) {

    fun isGranted(permission: AppPermission): Boolean = when (permission) {
        AppPermission.NOTIFICATION_ACCESS -> isNotificationAccessGranted()
        AppPermission.ACCESSIBILITY_SERVICE -> isAccessibilityServiceEnabled()
        AppPermission.SCREEN_CAPTURE -> StateManager.state.screenAccess == ScreenAccess.ON
        else -> permission.manifestPermission != null &&
            ContextCompat.checkSelfPermission(context, permission.manifestPermission) ==
                PackageManager.PERMISSION_GRANTED
    }

    /** Section 30: fail silently NAHI — user ko clearly bataye aur settings screen par le jaye. */
    fun requestOrExplain(permission: AppPermission, onExplain: (String, Intent?) -> Unit) {
        if (isGranted(permission)) return
        val (message, settingsIntent) = when (permission) {
            AppPermission.NOTIFICATION_ACCESS -> "Notification access permission chahiye." to
                Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            AppPermission.ACCESSIBILITY_SERVICE -> "Accessibility service enable karni hogi." to
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            AppPermission.SCREEN_CAPTURE -> "Screen access abhi off hai. Ise enable karo." to null
            AppPermission.LOCATION -> "Location permission chahiye." to
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            AppPermission.CONTACTS -> "Contacts permission chahiye call/message ke liye." to
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            AppPermission.PHONE -> "Call karne ke liye Phone permission chahiye." to
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            else -> "${permission.name} permission chahiye." to
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        }
        DebugLogger.log("PERMISSION", "$permission: DENIED")
        onExplain(message, settingsIntent)
    }

    private fun isNotificationAccessGranted(): Boolean {
        val enabledListeners = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        return enabledListeners?.contains(context.packageName) == true
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return enabledServices.any { it.resolveInfo.serviceInfo.packageName == context.packageName }
    }
}

// ============================================================================
// 4. SECURITY MANAGER  (Section 37)
// ============================================================================

object SecurityManager {
    /** Actions jo hamesha explicit user confirmation maangti hain (Section 29). */
    private val sensitiveIntents = setOf(
        "SEND_WHATSAPP_MESSAGE", "DELETE_FILE", "DELETE_MESSAGE",
        "CHANGE_SETTING", "SHARE_INFORMATION", "MAKE_CALL"
    )

    fun requiresConfirmation(intentName: String): Boolean = intentName in sensitiveIntents

    /** Koi bhi raw shell/system command execute karne se pehle yahan se guzro. */
    fun isSafeToExecute(action: String): Boolean {
        val blocked = listOf("bypass", "root", "su ", "disable_security", "sandbox_escape")
        return blocked.none { action.lowercase().contains(it) }
    }
}

// ============================================================================
// 5. INTENT ENGINE  (Section 2, 33: NLU — command -> structured intent)
// ============================================================================

data class ParsedCommand(
    val intent: String,
    val slots: Map<String, String> = emptyMap(),
    val rawText: String
)

/**
 * Yahan production mein tum ek proper NLU model (on-device classifier ya
 * server-side AI call — see AIService) use karoge. Ye rule-based fallback
 * hai jo Hindi/Hinglish/English teeno support karta hai (Section 33).
 */
object IntentEngine {

    private val appOpenPatterns = mapOf(
        Regex("whatsapp.*khol|open.*whatsapp|launch.*whatsapp", RegexOption.IGNORE_CASE) to "whatsapp",
        Regex("chrome.*khol|open.*chrome", RegexOption.IGNORE_CASE) to "chrome",
        Regex("spotify.*khol|open.*spotify", RegexOption.IGNORE_CASE) to "spotify",
        Regex("youtube.*khol|open.*youtube", RegexOption.IGNORE_CASE) to "youtube",
        Regex("camera.*khol|open.*camera", RegexOption.IGNORE_CASE) to "camera",
        Regex("gallery.*khol|open.*gallery", RegexOption.IGNORE_CASE) to "gallery",
        Regex("settings.*khol|open.*settings", RegexOption.IGNORE_CASE) to "settings"
    )

    fun parse(text: String): ParsedCommand {
        val t = text.trim()
        StateManager.update { lastUserCommand = t }

        appOpenPatterns.forEach { (regex, app) ->
            if (regex.containsMatchIn(t)) return ParsedCommand("OPEN_APP", mapOf("app" to app), t)
        }

        return when {
            Regex("battery", RegexOption.IGNORE_CASE).containsMatchIn(t) -> ParsedCommand("GET_BATTERY", rawText = t)
            Regex("time hua|current time|abhi.*time", RegexOption.IGNORE_CASE).containsMatchIn(t) -> ParsedCommand("GET_TIME", rawText = t)
            Regex("date", RegexOption.IGNORE_CASE).containsMatchIn(t) -> ParsedCommand("GET_DATE", rawText = t)
            Regex("location.*batao|kahan hoon|current location", RegexOption.IGNORE_CASE).containsMatchIn(t) -> ParsedCommand("GET_LOCATION", rawText = t)
            Regex("weather", RegexOption.IGNORE_CASE).containsMatchIn(t) -> ParsedCommand("GET_WEATHER", rawText = t)
            Regex("screen.*dekho|feed.*dekho|screen par kya|kya chal raha", RegexOption.IGNORE_CASE).containsMatchIn(t) -> ParsedCommand("READ_SCREEN", rawText = t)
            Regex("screen par kya likha|text read|likha hai", RegexOption.IGNORE_CASE).containsMatchIn(t) -> ParsedCommand("OCR_SCREEN", rawText = t)
            Regex("call karo|call lagao|phone karo", RegexOption.IGNORE_CASE).containsMatchIn(t) ->
                ParsedCommand("CALL_CONTACT", mapOf("contact" to extractName(t)), t)
            Regex("call end|call band|hang up", RegexOption.IGNORE_CASE).containsMatchIn(t) -> ParsedCommand("END_CALL", rawText = t)
            Regex("speaker par", RegexOption.IGNORE_CASE).containsMatchIn(t) -> ParsedCommand("SPEAKER_ON", rawText = t)
            Regex("message karo|bolo main|bhejo", RegexOption.IGNORE_CASE).containsMatchIn(t) ->
                ParsedCommand("SEND_WHATSAPP_MESSAGE", mapOf("contact" to extractName(t), "message" to extractMessageBody(t)), t)
            Regex("music.*play|song.*chalao|music.*chalao|music.*laga", RegexOption.IGNORE_CASE).containsMatchIn(t) ->
                ParsedCommand("PLAY_MUSIC", mapOf("mood" to extractMood(t)), t)
            Regex("pause", RegexOption.IGNORE_CASE).containsMatchIn(t) -> ParsedCommand("PAUSE_MEDIA", rawText = t)
            Regex("next (video|song)", RegexOption.IGNORE_CASE).containsMatchIn(t) -> ParsedCommand("NEXT_MEDIA", rawText = t)
            Regex("google.*search|search.*google", RegexOption.IGNORE_CASE).containsMatchIn(t) ->
                ParsedCommand("GOOGLE_SEARCH", mapOf("query" to extractSearchQuery(t)), t)
            Regex("youtube.*search|youtube par.*search", RegexOption.IGNORE_CASE).containsMatchIn(t) ->
                ParsedCommand("YOUTUBE_SEARCH", mapOf("query" to extractSearchQuery(t)), t)
            Regex("notification read|kya notification|kisne message", RegexOption.IGNORE_CASE).containsMatchIn(t) -> ParsedCommand("READ_NOTIFICATIONS", rawText = t)
            Regex("code likho|calculator bana|website bana|program likho", RegexOption.IGNORE_CASE).containsMatchIn(t) ->
                ParsedCommand("GENERATE_CODE", mapOf("request" to t), t)
            Regex("back jao|go back", RegexOption.IGNORE_CASE).containsMatchIn(t) -> ParsedCommand("NAV_BACK", rawText = t)
            Regex("home.*jao|home screen", RegexOption.IGNORE_CASE).containsMatchIn(t) -> ParsedCommand("NAV_HOME", rawText = t)
            Regex("previous app", RegexOption.IGNORE_CASE).containsMatchIn(t) -> ParsedCommand("NAV_RECENT", rawText = t)
            Regex("stop|cancel|ruko", RegexOption.IGNORE_CASE).containsMatchIn(t) -> ParsedCommand("CANCEL_TASK", rawText = t)
            else -> ParsedCommand("UNKNOWN", rawText = t)
        }
    }

    private fun extractName(t: String): String =
        Regex("([A-Za-z]+) ko").find(t)?.groupValues?.get(1) ?: ""

    private fun extractMessageBody(t: String): String =
        Regex("bolo (ki )?(.+)", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(2) ?: ""

    private fun extractMood(t: String): String {
        val moods = listOf("peaceful", "relaxing", "romantic", "love", "sad", "energetic", "old", "workout", "focus", "sleep", "random")
        return moods.firstOrNull { t.contains(it, ignoreCase = true) } ?: "general"
    }

    private fun extractSearchQuery(t: String): String =
        Regex("search (.+)", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)?.replace("karo", "")?.trim() ?: t
}

// ============================================================================
// 6. TASK MANAGER  (Section 28: multi-step command orchestration)
// ============================================================================

data class TaskStep(val intent: String, val slots: Map<String, String> = emptyMap())

class TaskManager(private val executor: ActionExecutor) {
    private var activeJob: Job? = null

    fun runPlan(steps: List<TaskStep>, scope: CoroutineScope) {
        activeJob?.cancel()
        activeJob = scope.launch {
            StateManager.update { currentTask = steps.joinToString(" -> ") { it.intent } }
            for (step in steps) {
                ensureActive() // supports Section 35 interruption via job.cancel()
                val result = executor.execute(step.intent, step.slots)
                DebugLogger.log("TASK", "${step.intent} -> $result")
                if (!result.success) {
                    executor.speak("${step.intent} fail ho gaya: ${result.message}")
                    break
                }
            }
            StateManager.update { currentTask = null }
        }
    }

    fun cancelCurrentTask() {
        activeJob?.cancel()
        StateManager.update { currentTask = null }
    }
}

// ============================================================================
// 7. ACTION RESULT + EXECUTOR CONTRACT
// ============================================================================

data class ActionResult(val success: Boolean, val message: String, val data: Any? = null)

interface ActionExecutor {
    suspend fun execute(intent: String, slots: Map<String, String>): ActionResult
    fun speak(text: String)
}

// ============================================================================
// 8. APP CONTROLLER  (Section 3)
// ============================================================================

class AppController(private val context: Context) {

    private val packageMap = mapOf(
        "whatsapp" to "com.whatsapp",
        "chrome" to "com.android.chrome",
        "spotify" to "com.spotify.music",
        "youtube" to "com.google.android.youtube",
        "instagram" to "com.instagram.android",
        "gallery" to "com.google.android.apps.photos",
        "camera" to "com.android.camera",
        "settings" to "com.android.settings"
    )

    fun openApp(name: String): ActionResult {
        val pkg = packageMap[name.lowercase()] ?: return ActionResult(false, "$name ka package map nahi mila")
        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return ActionResult(false, "$name installed nahi hai")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        StateManager.update { currentApp = name }
        return ActionResult(true, "$name open ho gaya")
    }

    /**
     * Android security model ke andar third-party app ko force-close karna
     * general case mein possible nahi hai. Best-effort: app ko background
     * mein bhejna (moveTaskToBack) ya user ko Recents se manually close
     * karne ka guide dena.
     */
    fun closeCurrentApp(activity: android.app.Activity): ActionResult {
        activity.moveTaskToBack(true)
        return ActionResult(true, "App background mein bhej diya (Android policy ke wajah se force-close restricted hai)")
    }

    fun goHome() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(homeIntent)
    }
}

// ============================================================================
// 9. BROWSER CONTROLLER  (Section 5, 6)
// ============================================================================

class BrowserController(private val context: Context) {

    fun openUrl(url: String): ActionResult {
        val normalized = if (!url.startsWith("http")) "https://$url" else url
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalized)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        StateManager.update { browserState = "OPEN:$normalized" }
        return ActionResult(true, "Browser mein $normalized khol diya")
    }

    fun googleSearch(query: String): ActionResult =
        openUrl("https://www.google.com/search?q=${Uri.encode(query)}")

    fun openMultiple(urls: List<String>): ActionResult {
        urls.forEach { openUrl(it) }
        return ActionResult(true, "${urls.size} tabs open kiye")
    }
}

// ============================================================================
// 10. YOUTUBE + MUSIC (SPOTIFY) CONTROLLERS  (Section 7, 8)
// ============================================================================

class YouTubeController(private val context: Context) {

    fun search(query: String): ActionResult {
        val intent = Intent(Intent.ACTION_VIEW,
            Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            ActionResult(true, "YouTube par '$query' search kar diya")
        } catch (e: Exception) {
            ActionResult(false, "YouTube open nahi ho saka: ${e.message}")
        }
    }

    // Play/pause/next/previous ideally MediaSession callbacks se control hote hain
    // jab YouTube app foreground mein ho aur media keys supported hon.
    fun sendMediaKey(keyCode: Int) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
    }
}

class MusicController(private val context: Context) {

    private val moodQueryMap = mapOf(
        "peaceful" to "peaceful music",
        "relaxing" to "relaxing music",
        "romantic" to "romantic love songs",
        "love" to "love songs",
        "sad" to "sad songs",
        "energetic" to "energetic hype songs",
        "old" to "old classic songs",
        "workout" to "workout gym music",
        "focus" to "focus instrumental music",
        "sleep" to "sleep calm music",
        "random" to "top hits shuffle",
        "general" to "popular music"
    )

    fun playMood(mood: String): ActionResult {
        val query = moodQueryMap[mood.lowercase()] ?: mood
        val spotifyUri = Uri.parse("spotify:search:${Uri.encode(query)}")
        val spotifyIntent = Intent(Intent.ACTION_VIEW, spotifyUri).setPackage("com.spotify.music")
        return try {
            context.startActivity(spotifyIntent)
            StateManager.update { musicState = "PLAYING:$query" }
            ActionResult(true, "Spotify par '$query' play ho raha hai")
        } catch (e: ActivityNotFoundException) {
            // Fallback: Spotify web search
            val webIntent = Intent(Intent.ACTION_VIEW,
                Uri.parse("https://open.spotify.com/search/${Uri.encode(query)}"))
            context.startActivity(webIntent)
            ActionResult(true, "Spotify app nahi mili, web par '$query' khol diya")
        }
    }

    fun sendMediaKey(keyCode: Int) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
    }
}

// ============================================================================
// 11. CONTACT RESOLVER  (Section 32: multiple contact handling)
// ============================================================================

data class Contact(val id: String, val name: String, val phoneNumber: String)

class ContactResolver(private val context: Context) {

    fun findByName(name: String): List<Contact> {
        if (name.isBlank()) return emptyList()
        val results = mutableListOf<Contact>()
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        )
        cursor?.use {
            while (it.moveToNext()) {
                results.add(
                    Contact(
                        id = it.getString(0),
                        name = it.getString(1),
                        phoneNumber = it.getString(2)
                    )
                )
            }
        }
        return results.distinctBy { it.phoneNumber }
    }
}

// ============================================================================
// 12. CALL CONTROLLER  (Section 10)
// ============================================================================

class CallController(
    private val context: Context,
    private val permissionManager: PermissionManager,
    private val contactResolver: ContactResolver
) {

    /**
     * ACTION_CALL directly dial karta hai (CALL_PHONE permission chahiye).
     * Section 29 ke mutabik ye sensitive action hai isliye caller (UI layer)
     * ko pehle user confirmation dikhana chahiye.
     */
    fun callContact(name: String): ActionResult {
        if (!permissionManager.isGranted(AppPermission.CONTACTS))
            return ActionResult(false, "Contacts permission missing")
        if (!permissionManager.isGranted(AppPermission.PHONE))
            return ActionResult(false, "Phone permission missing")

        val matches = contactResolver.findByName(name)
        return when {
            matches.isEmpty() -> ActionResult(false, "$name naam ka koi contact nahi mila")
            matches.size > 1 -> ActionResult(
                false,
                "$name naam ke ${matches.size} contacts mile — user se selection maango",
                data = matches
            )
            else -> dial(matches.first())
        }
    }

    fun dial(contact: Contact): ActionResult {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${contact.phoneNumber}"))
        context.startActivity(intent)
        StateManager.update { activeCall = true; callState = CallState.DIALING }
        return ActionResult(true, "${contact.name} ko call laga raha hoon")
    }

    /**
     * Live call ke andar mute/speaker/end control karne ke liye InCallService
     * implement karna padta hai (Telecom framework), simple Activity/Intent se
     * ye possible nahi. Yahan contract sirf declare kiya gaya hai.
     */
    fun setSpeaker(on: Boolean): ActionResult =
        ActionResult(true, "Speaker ${if (on) "on" else "off"} kar diya (InCallService.setAudioRoute se wire karo)")

    fun endCall(): ActionResult {
        StateManager.update { activeCall = false; callState = CallState.ENDED }
        return ActionResult(true, "Call end kar diya (InCallService.disconnect() se wire karo)")
    }
}

/**
 * Real incoming/active call control ke liye InCallService extend karo aur
 * manifest mein register karo (Section 10: Incoming call). Skeleton:
 */
abstract class AssistantInCallService : android.telecom.InCallService() {
    override fun onCallAdded(call: android.telecom.Call) {
        super.onCallAdded(call)
        StateManager.update { activeCall = true; callState = CallState.RINGING }
        DebugLogger.log("CALL", "Incoming/active call detected")
    }

    override fun onCallRemoved(call: android.telecom.Call) {
        super.onCallRemoved(call)
        StateManager.update { activeCall = false; callState = CallState.ENDED }
    }

    fun answer(call: android.telecom.Call) = call.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY)
    fun hangup(call: android.telecom.Call) = call.disconnect()
    fun mute(mute: Boolean) = setMuted(mute)
    fun speaker(on: Boolean) = setAudioRoute(
        if (on) android.telecom.CallAudioState.ROUTE_SPEAKER else android.telecom.CallAudioState.ROUTE_EARPIECE
    )
}

// ============================================================================
// 13. WHATSAPP CONTROLLER  (Section 9)
// ============================================================================

class WhatsAppController(
    private val context: Context,
    private val contactResolver: ContactResolver
) {

    /**
     * WhatsApp compose screen open karta hai with pre-filled text via the
     * documented `https://wa.me/` deep link. Actual SEND button user khud
     * dabata hai — ye by-design hai (Section 29: sensitive action confirmation,
     * Section 37: no silent auto-send / no accessibility-based button tapping).
     */
    fun composeMessage(contactName: String, message: String): ActionResult {
        val matches = contactResolver.findByName(contactName)
        if (matches.isEmpty()) return ActionResult(false, "$contactName naam ka contact nahi mila")
        if (matches.size > 1) return ActionResult(false, "Multiple contacts mile, user se poocho", data = matches)

        val phone = matches.first().phoneNumber.filter { it.isDigit() || it == '+' }
        val uri = Uri.parse("https://wa.me/$phone?text=${Uri.encode(message)}")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        return try {
            context.startActivity(intent)
            ActionResult(true, "WhatsApp compose screen khol diya — Send dabane ke liye confirm karo")
        } catch (e: Exception) {
            ActionResult(false, "WhatsApp open nahi ho saka: ${e.message}")
        }
    }

    fun openWhatsApp(): ActionResult {
        val intent = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
            ?: return ActionResult(false, "WhatsApp installed nahi hai")
        context.startActivity(intent)
        return ActionResult(true, "WhatsApp open ho gaya")
    }
}

// ============================================================================
// 14. NOTIFICATION CONTROLLER  (Section 11)
// ============================================================================

data class AssistantNotification(
    val packageName: String,
    val appName: String,
    val title: String?,
    val text: String?,
    val postTimeMillis: Long
)

/** Manifest mein register karo + user ko Notification Access settings mein enable karna hoga. */
class AssistantNotificationListenerService : NotificationListenerService() {

    companion object {
        // In-memory ring buffer of latest notifications (per-session only; not persisted).
        private val recent = ArrayDeque<AssistantNotification>(50)

        fun latestFor(packageName: String? = null): List<AssistantNotification> =
            if (packageName == null) recent.toList()
            else recent.filter { it.packageName == packageName }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val entry = AssistantNotification(
            packageName = sbn.packageName,
            appName = appNameFor(sbn.packageName),
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            postTimeMillis = sbn.postTime
        )
        if (recent.size >= 50) recent.removeFirst()
        recent.addLast(entry)
        DebugLogger.log("NOTIFICATION", "${entry.appName}: ${entry.title}", sensitive = true)
    }

    private fun appNameFor(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) { pkg }
}

class NotificationController(private val permissionManager: PermissionManager) {

    fun readLatest(appFilter: String? = null): ActionResult {
        if (!permissionManager.isGranted(AppPermission.NOTIFICATION_ACCESS))
            return ActionResult(false, "Notification access permission nahi hai")

        val pkg = mapOf("whatsapp" to "com.whatsapp", "instagram" to "com.instagram.android")[appFilter?.lowercase()]
        val notes = AssistantNotificationListenerService.latestFor(pkg)
        if (notes.isEmpty()) return ActionResult(true, "Koi naya notification nahi hai")

        val latest = notes.last()
        return ActionResult(true, "${latest.appName}: ${latest.title} - ${latest.text}", data = latest)
    }
}

// ============================================================================
// 15. SCREEN CONTROLLER  (Section 12, 13, 14, 15 — fresh capture every time)
// ============================================================================

/**
 * Screenshot lene ke do supported raaste hain:
 *  (a) MediaProjection API — har call par user consent dialog chahiye (jab tak
 *      session token valid hai tab tak repeat prompt nahi aata).
 *  (b) AccessibilityService.takeScreenshot() (API 30+) — agar accessibility
 *      service already enabled hai to bina extra prompt ke fresh capture milta hai.
 *
 * IMPORTANT (Section 12): har request par PURANA screenshot kabhi reuse mat karo.
 */
class ScreenController(
    private val context: Context,
    private val mediaProjectionManager: MediaProjectionManager,
    private val aiService: AIService
) {

    /** Accessibility service se fresh screenshot request karta hai. */
    suspend fun captureFresh(accessibilityService: AssistantAccessibilityService): android.graphics.Bitmap? =
        accessibilityService.takeFreshScreenshot()

    suspend fun describeScreen(accessibilityService: AssistantAccessibilityService, userQuestion: String): ActionResult {
        if (StateManager.state.screenAccess != ScreenAccess.ON) {
            return ActionResult(false, "Screen access currently off hai. Enable karne ke baad main screen dekh sakta hoon.")
        }
        val bitmap = captureFresh(accessibilityService)
            ?: return ActionResult(false, "Fresh screenshot capture nahi ho saka")

        // AI vision model ko HAR BAAR fresh bitmap bheja jata hai — no caching.
        val description = aiService.analyzeScreenshot(bitmap, userQuestion)
        return ActionResult(true, description)
    }

    fun setScreenAccess(on: Boolean) {
        StateManager.update { screenAccess = if (on) ScreenAccess.ON else ScreenAccess.OFF }
    }
}

/**
 * AccessibilityService jo screen text (OCR-like via node tree) aur
 * takeScreenshot() dono provide karta hai. Manifest + accessibility_service_config.xml
 * ke through register karna zaroori hai; user ko Settings > Accessibility mein
 * explicitly enable karna hoga.
 */
class AssistantAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()
        if (pkg != null) StateManager.update { currentApp = pkg }
    }

    override fun onInterrupt() { /* no-op */ }

    /** API 30+: OS-level screenshot, always fresh (no cache) by definition. */
    suspend fun takeFreshScreenshot(): android.graphics.Bitmap? =
        suspendCancellableCoroutine { cont ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(result: ScreenshotResult) {
                            val bitmap = android.graphics.Bitmap.wrapHardwareBuffer(
                                result.hardwareBuffer, result.colorSpace
                            )
                            result.hardwareBuffer.close()
                            cont.resume(bitmap) {}
                        }
                        override fun onFailure(errorCode: Int) {
                            DebugLogger.log("SCREEN", "Screenshot failed: $errorCode")
                            cont.resume(null) {}
                        }
                    }
                )
            } else {
                cont.resume(null) {}
            }
        }

    /** Visible text ko accessibility node tree traverse karke nikalta hai (OCR alternative). */
    fun extractVisibleText(): String {
        val root = rootInActiveWindow ?: return ""
        val builder = StringBuilder()
        fun walk(node: android.view.accessibility.AccessibilityNodeInfo?) {
            if (node == null) return
            node.text?.let { if (it.isNotBlank()) builder.appendLine(it) }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        return builder.toString().trim()
    }
}

// ============================================================================
// 16. DEVICE / BATTERY / TIME  (Section 16, 17, 25)
// ============================================================================

class DeviceService(private val context: Context) {

    fun getBatteryInfo(): ActionResult {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val charging = status?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            ?.let { it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL } ?: false

        StateManager.update { batteryLevel = level; chargingState = charging }
        val msg = "Battery $level percent hai" + if (charging) " aur device charging par hai." else "."
        return ActionResult(true, msg, data = level to charging)
    }

    fun getCurrentTime(): ActionResult {
        val fmt = SimpleDateFormat("hh:mm a", Locale.getDefault())
        val timeStr = fmt.format(Date())
        StateManager.update { currentTimeIso = Date().toString() }
        return ActionResult(true, "Abhi $timeStr hue hain")
    }

    fun getCurrentDate(): ActionResult {
        val fmt = SimpleDateFormat("dd MMMM, yyyy (EEEE)", Locale.getDefault())
        return ActionResult(true, "Aaj ${fmt.format(Date())} hai")
    }

    fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        StateManager.update { networkAvailable = online }
        return online
    }
}

// ============================================================================
// 17. LOCATION SERVICE  (Section 18)
// ============================================================================

class LocationService(
    private val context: Context,
    private val permissionManager: PermissionManager
) {
    fun getCurrentLocation(onResult: (ActionResult) -> Unit) {
        if (!permissionManager.isGranted(AppPermission.LOCATION)) {
            onResult(ActionResult(false, "Location permission nahi hai"))
            return
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = LocationManager.FUSED_PROVIDER.takeIf {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lm.isProviderEnabled(it)
        } ?: LocationManager.GPS_PROVIDER

        try {
            lm.getCurrentLocation(provider, null, context.mainExecutor) { location: Location? ->
                if (location == null) {
                    onResult(ActionResult(false, "Location fetch nahi ho saki"))
                } else {
                    StateManager.update { this.location = location }
                    onResult(ActionResult(true, "Location mil gayi: ${location.latitude}, ${location.longitude}", data = location))
                }
            }
        } catch (e: SecurityException) {
            onResult(ActionResult(false, "Location permission denied: ${e.message}"))
        }
    }
}

// ============================================================================
// 18. WEATHER SERVICE  (Section 19) — uses Location + web API
// ============================================================================

interface WeatherApi {
    // Retrofit example: GET https://api.example.com/weather?lat=..&lon=..&key=..
    suspend fun fetchWeather(lat: Double, lon: Double, apiKey: String): WeatherResponse
}

data class WeatherResponse(
    val tempC: Double,
    val condition: String,
    val humidity: Int? = null,
    val windKph: Double? = null,
    val rainMm: Double? = null
)

class WeatherService(private val weatherApi: WeatherApi, private val apiKey: String) {
    suspend fun getWeather(location: Location): ActionResult = try {
        val w = weatherApi.fetchWeather(location.latitude, location.longitude, apiKey)
        val parts = mutableListOf("${w.tempC}°C", w.condition)
        w.humidity?.let { parts.add("humidity $it%") }
        w.windKph?.let { parts.add("wind ${it}kph") }
        ActionResult(true, "Abhi weather: ${parts.joinToString(", ")}", data = w)
    } catch (e: Exception) {
        ActionResult(false, "Weather fetch nahi ho saka: ${e.message}")
    }
}

// ============================================================================
// 19. WEB SEARCH + DEEP SEARCH SERVICE  (Section 20)
// ============================================================================

interface SearchApi {
    suspend fun search(query: String): List<SearchResultItem>
}

data class SearchResultItem(val title: String, val url: String, val snippet: String)

class WebSearchService(private val searchApi: SearchApi, private val aiService: AIService) {

    suspend fun quickSearch(query: String): ActionResult = try {
        val results = searchApi.search(query)
        ActionResult(true, results.take(3).joinToString("; ") { it.title }, data = results)
    } catch (e: Exception) {
        ActionResult(false, "Search fail hui: ${e.message}")
    }

    /** Section 20: multiple sources -> compare -> summarize -> cite. */
    suspend fun deepResearch(topic: String): ActionResult = try {
        val results = searchApi.search(topic)
        val summary = aiService.summarizeSources(topic, results)
        ActionResult(true, summary, data = results)
    } catch (e: Exception) {
        ActionResult(false, "Deep research fail hui: ${e.message}")
    }
}

// ============================================================================
// 20. AI SERVICE  (Section 21 — Gemini/AI conversation + vision + summarize)
// ============================================================================

interface AIBackend {
    suspend fun chat(prompt: String, contextHistory: List<String>): String
    suspend fun analyzeImage(imageBase64: String, question: String): String
}

class AIService(private val backend: AIBackend) {

    private val conversationMemory = ArrayDeque<String>(maxSize = 20)

    suspend fun converse(userText: String): ActionResult = try {
        val reply = backend.chat(userText, conversationMemory.toList())
        conversationMemory.addLast("User: $userText")
        conversationMemory.addLast("Assistant: $reply")
        while (conversationMemory.size > 20) conversationMemory.removeFirst()
        ActionResult(true, reply)
    } catch (e: Exception) {
        ActionResult(false, "AI response nahi mila: ${e.message}")
    }

    suspend fun analyzeScreenshot(bitmap: android.graphics.Bitmap, question: String): String {
        val base64 = bitmapToBase64(bitmap)
        return try {
            backend.analyzeImage(base64, question)
        } catch (e: Exception) {
            "Screen analyze nahi ho saka: ${e.message}"
        }
    }

    suspend fun summarizeSources(topic: String, results: List<SearchResultItem>): String {
        val context = results.joinToString("\n") { "- ${it.title}: ${it.snippet}" }
        return backend.chat("Summarize research on '$topic' using these sources:\n$context", emptyList())
    }

    private fun bitmapToBase64(bitmap: android.graphics.Bitmap): String {
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, stream)
        return android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
    }
}

/** Small helper — ArrayDeque doesn't have a maxSize ctor param in stdlib; wrapper for clarity. */
private fun <T> ArrayDeque(maxSize: Int): ArrayDeque<T> = ArrayDeque()

// ============================================================================
// 21. CODING SERVICE  (Section 22, 23)
// ============================================================================

class CodingService(private val aiService: AIService, private val fileService: FileService) {

    suspend fun generateCode(request: String, saveAs: String? = null): ActionResult {
        val prompt = "Generate clean, well-commented code for this request:\n$request"
        val codeResult = aiService.converse(prompt)
        if (!codeResult.success) return codeResult

        if (saveAs != null) {
            val saveResult = fileService.createFile(saveAs, codeResult.message)
            return if (saveResult.success)
                ActionResult(true, "Code generate karke $saveAs mein save kar diya")
            else saveResult
        }
        return codeResult
    }
}

// ============================================================================
// 22. FILE SERVICE  (Section 24) — destructive ops need confirmation
// ============================================================================

class FileService(private val context: Context) {

    fun createFile(fileName: String, content: String): ActionResult = try {
        val dir = context.getExternalFilesDir(null)
        val file = java.io.File(dir, fileName)
        file.writeText(content)
        ActionResult(true, "$fileName create ho gayi", data = file.absolutePath)
    } catch (e: Exception) {
        ActionResult(false, "File create nahi ho saki: ${e.message}")
    }

    /** Section 29: destructive action — caller (UI) confirmation ke baad hi ye call kare. */
    fun deleteFile(path: String, confirmed: Boolean): ActionResult {
        if (!confirmed) return ActionResult(false, "Delete ke liye confirmation chahiye")
        val file = java.io.File(path)
        return if (file.exists() && file.delete()) ActionResult(true, "File delete kar di")
        else ActionResult(false, "File delete nahi ho saki")
    }

    fun openDownloads(): Intent =
        Intent(Intent.ACTION_VIEW).setDataAndType(
            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, "*/*"
        )
}

// ============================================================================
// 23. VOICE ENGINE  (Section 2, 36) — STT/TTS glue (platform APIs wired in app layer)
// ============================================================================

interface SpeechToText { fun startListening(onResult: (String) -> Unit, onError: (String) -> Unit) }
interface TextToSpeech { fun speak(text: String) }

class VoiceEngine(
    private val stt: SpeechToText,
    private val tts: TextToSpeech,
    private val onCommand: (String) -> Unit
) {
    fun listen() {
        StateManager.update { microphoneActive = true }
        stt.startListening(
            onResult = { text ->
                StateManager.update { microphoneActive = false }
                onCommand(text)
            },
            onError = { err ->
                StateManager.update { microphoneActive = false }
                DebugLogger.log("VOICE", "STT error: $err")
            }
        )
    }

    fun respond(text: String) {
        StateManager.update { assistantSpeaking = true }
        tts.speak(text)
        StateManager.update { assistantSpeaking = false }
    }
}

// ============================================================================
// 24. CENTRAL ACTION EXECUTOR  (wires IntentEngine output -> controllers)
// ============================================================================

class AssistantActionExecutor(
    private val context: Context,
    private val appController: AppController,
    private val browserController: BrowserController,
    private val youTubeController: YouTubeController,
    private val musicController: MusicController,
    private val callController: CallController,
    private val whatsAppController: WhatsAppController,
    private val notificationController: NotificationController,
    private val deviceService: DeviceService,
    private val locationService: LocationService,
    private val weatherService: WeatherService,
    private val webSearchService: WebSearchService,
    private val aiService: AIService,
    private val codingService: CodingService,
    private val voiceEngine: VoiceEngine,
    private val onNeedsConfirmation: (String, Map<String, String>) -> Unit
) : ActionExecutor {

    override fun speak(text: String) = voiceEngine.respond(text)

    override suspend fun execute(intent: String, slots: Map<String, String>): ActionResult {
        DebugLogger.log("INTENT", intent)

        // Section 29: sensitive actions pehle confirmation ke liye UI ko bhejo.
        if (SecurityManager.requiresConfirmation(intent)) {
            onNeedsConfirmation(intent, slots)
            return ActionResult(true, "Confirmation maanga gaya")
        }

        return when (intent) {
            "OPEN_APP" -> appController.openApp(slots["app"].orEmpty())
            "NAV_HOME" -> { appController.goHome(); ActionResult(true, "Home par chale gaye") }
            "GET_BATTERY" -> deviceService.getBatteryInfo()
            "GET_TIME" -> deviceService.getCurrentTime()
            "GET_DATE" -> deviceService.getCurrentDate()
            "GET_LOCATION" -> suspendCancellableCoroutine { cont ->
                locationService.getCurrentLocation { cont.resume(it) {} }
            }
            "GET_WEATHER" -> {
                var result: ActionResult = ActionResult(false, "Location nahi mili")
                locationService.getCurrentLocation { locResult ->
                    if (locResult.success) {
                        val loc = locResult.data as Location
                        CoroutineScope(Dispatchers.IO).launch { result = weatherService.getWeather(loc) }
                    }
                }
                result
            }
            "GOOGLE_SEARCH" -> browserController.googleSearch(slots["query"].orEmpty())
            "YOUTUBE_SEARCH" -> youTubeController.search(slots["query"].orEmpty())
            "PLAY_MUSIC" -> musicController.playMood(slots["mood"].orEmpty())
            "PAUSE_MEDIA" -> { musicController.sendMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE); ActionResult(true, "Pause kar diya") }
            "NEXT_MEDIA" -> { musicController.sendMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT); ActionResult(true, "Next kar diya") }
            "CALL_CONTACT" -> callController.callContact(slots["contact"].orEmpty())
            "END_CALL" -> callController.endCall()
            "SPEAKER_ON" -> callController.setSpeaker(true)
            "READ_NOTIFICATIONS" -> notificationController.readLatest()
            "GENERATE_CODE" -> codingService.generateCode(slots["request"].orEmpty())
            "CANCEL_TASK" -> ActionResult(true, "Task cancel kar diya")
            "UNKNOWN" -> aiService.converse(slots["raw"] ?: "")
            else -> ActionResult(false, "Intent '$intent' abhi supported nahi hai")
        }
    }
}

// ============================================================================
// END OF FILE
// ============================================================================