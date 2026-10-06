package com.jarvis.assistant.network

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.jarvis.assistant.data.model.*
import com.jarvis.assistant.data.model.contract.ToolResponse
import com.jarvis.assistant.util.LunaLogger
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GeminiLiveWebSocket(
    private val apiKey: String,
    private val model: String,
    private val voiceName: String,
    private val systemPrompt: String,
    private val listener: Listener
) {
    interface Listener {
        fun onConnectionStateChanged(status: String)
        fun onAudioDataReceived(pcmData: ByteArray)
        fun onAssistantTextReceived(textChunk: String)
        fun onUserTextReceived(textChunk: String)
        fun onToolCallReceived(callId: String, functionName: String, args: Map<String, String>)
        fun onInterrupted()
        fun onTurnCompleted()
        fun onError(message: String)
    }

    companion object {
        private const val TAG = "GeminiLiveWebSocket"
    }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .pingInterval(GeminiConstants.KEEPALIVE_INTERVAL_SEC, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val isSetupComplete = AtomicBoolean(false)
    private val isManuallyStopped = AtomicBoolean(false)
    private var coroutineScope: CoroutineScope? = null

    private var sessionRenewalJob: Job? = null
    private var reconnectJob: Job? = null

    fun connect(scope: CoroutineScope) {
        coroutineScope = scope
        isManuallyStopped.set(false)
        initiateConnection()
    }

    private fun initiateConnection() {
        if (apiKey.isBlank()) {
            listener.onError("Gemini API Key is missing. Please configure it in Settings.")
            return
        }

        listener.onConnectionStateChanged("CONNECTING...")

        try {
            val url = "${GeminiConstants.WS_BASE_URL}?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket connected to Gemini Live")
                    // Send setup message FIRST before enabling audio streaming
                    sendSetupMessage(webSocket)
                    isConnected.set(true)
                    isSetupComplete.set(true)
                    listener.onConnectionStateChanged("LIVE")

                    // Start 9-minute session renewal
                    startSessionRenewal()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleIncomingMessage(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleIncomingMessage(bytes.utf8())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closing: $code / $reason")
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closed: $code / $reason")
                    isConnected.set(false)
                    isSetupComplete.set(false)
                    stopTimers()
                    if (code == 1008) {
                        listener.onError("Gemini setup was rejected: $reason")
                        listener.onConnectionStateChanged("OFFLINE")
                    } else if (!isManuallyStopped.get()) {
                        listener.onConnectionStateChanged("RECONNECTING...")
                        scheduleReconnect()
                    } else {
                        listener.onConnectionStateChanged("OFFLINE")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val errorMsg = t.message ?: "WebSocket connection failure"
                    Log.e(TAG, "WebSocket failure: $errorMsg", t)
                    isConnected.set(false)
                    isSetupComplete.set(false)
                    stopTimers()
                    if (!isManuallyStopped.get()) {
                        listener.onError("Connection lost: $errorMsg. Retrying...")
                        scheduleReconnect()
                    } else {
                        listener.onConnectionStateChanged("OFFLINE")
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating WebSocket connection: ${e.message}", e)
            listener.onError("Connection failed: ${e.message}")
            listener.onConnectionStateChanged("OFFLINE")
        }
    }

    private fun buildDeviceTools(): List<ToolPayload> {
        val declarations = listOf(
            FunctionDeclarationPayload(
                name = "open_app",
                description = "Opens an installed application on the user's phone by name, such as WhatsApp, YouTube, Chrome, Camera, Settings, Spotify, Instagram, etc. If running in background, brings it to foreground.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "app_name" to PropertySchemaPayload("STRING", "The name of the app to launch (e.g. 'whatsapp', 'youtube', 'chrome')")
                    ),
                    required = listOf("app_name")
                )
            ),
            FunctionDeclarationPayload(
                name = "switch_app",
                description = "Switches between applications on the device (e.g., from WhatsApp to YouTube, or previous app).",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "to" to PropertySchemaPayload("STRING", "The target app to switch to"),
                        "from" to PropertySchemaPayload("STRING", "Optional current or source app name")
                    ),
                    required = listOf("to")
                )
            ),
            FunctionDeclarationPayload(
                name = "close_app",
                description = "Closes or minimizes an application on the user's phone using Android-permitted mechanisms.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "app_name" to PropertySchemaPayload("STRING", "The name of the app to close, or 'current'")
                    ),
                    required = listOf("app_name")
                )
            ),
            FunctionDeclarationPayload(
                name = "minimize_app",
                description = "Sends the app Home while keeping it alive per normal Android lifecycle — distinct from force-stop.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "app_name" to PropertySchemaPayload("STRING", "Optional app name to minimize")
                    )
                )
            ),
            FunctionDeclarationPayload(
                name = "close_all_apps",
                description = "Closes all running background applications and returns to the home screen.",
                parameters = FunctionParametersPayload(type = "OBJECT")
            ),
            FunctionDeclarationPayload(
                name = "open_all_apps",
                description = "Opens the all apps overview or displays all installed applications.",
                parameters = FunctionParametersPayload(type = "OBJECT")
            ),
            FunctionDeclarationPayload(
                name = "list_running_apps",
                description = "Lists all applications currently active or running in the background.",
                parameters = FunctionParametersPayload(type = "OBJECT")
            ),
            FunctionDeclarationPayload(
                name = "go_to_home",
                description = "Navigates to the phone's home screen.",
                parameters = FunctionParametersPayload(type = "OBJECT")
            ),
            FunctionDeclarationPayload(
                name = "go_back",
                description = "Performs Android system Back button navigation.",
                parameters = FunctionParametersPayload(type = "OBJECT")
            ),
            FunctionDeclarationPayload(
                name = "open_recents",
                description = "Opens Android system Recent applications overview.",
                parameters = FunctionParametersPayload(type = "OBJECT")
            ),
            FunctionDeclarationPayload(
                name = "split_screen",
                description = "Toggles Android split-screen / multi-window mode.",
                parameters = FunctionParametersPayload(type = "OBJECT")
            ),
            FunctionDeclarationPayload(
                name = "search_youtube",
                description = "Searches for a query, song, video, or topic on YouTube and opens the results in the YouTube app.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "query" to PropertySchemaPayload("STRING", "The search query to search on YouTube")
                    ),
                    required = listOf("query")
                )
            ),
            FunctionDeclarationPayload(
                name = "play_youtube",
                description = "Plays a song or video title on YouTube.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "query" to PropertySchemaPayload("STRING", "The video or song title to play")
                    ),
                    required = listOf("query")
                )
            ),
            FunctionDeclarationPayload(
                name = "play_nth_video",
                description = "Taps the N-th video in YouTube search results (0 = first video, 1 = second video, etc.).",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "index" to PropertySchemaPayload("STRING", "0 for first video, 1 for second, etc.")
                    ),
                    required = listOf("index")
                )
            ),
            FunctionDeclarationPayload(
                name = "media_control",
                description = "Universal media playback controls: play, pause, resume, stop, next, previous.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "action" to PropertySchemaPayload("STRING", "play, pause, resume, stop, next, or previous")
                    ),
                    required = listOf("action")
                )
            ),
            FunctionDeclarationPayload(
                name = "media_seek",
                description = "Relative time jump forward or back in playing video/audio (e.g. skip 5 minutes / 300 seconds).",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "seconds" to PropertySchemaPayload("STRING", "Number of seconds to skip (e.g. '300' for 5 mins)"),
                        "forward" to PropertySchemaPayload("STRING", "'true' to skip forward, 'false' to rewind/go back")
                    ),
                    required = listOf("seconds", "forward")
                )
            ),
            FunctionDeclarationPayload(
                name = "media_speed",
                description = "Controls playback speed on YouTube / active player UI.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "speed" to PropertySchemaPayload("STRING", "Playback speed like '0.5x', '1.25x', '1.5x', '2.0x', 'slower', 'faster', 'normal'")
                    ),
                    required = listOf("speed")
                )
            ),
            FunctionDeclarationPayload(
                name = "media_quality",
                description = "Sets video playback quality when player UI exposes it.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "quality" to PropertySchemaPayload("STRING", "Quality level like '720p', '1080p', 'auto', 'high'")
                    ),
                    required = listOf("quality")
                )
            ),
            FunctionDeclarationPayload(
                name = "spotify_search",
                description = "Searches for and plays a track, playlist, or artist on Spotify. Set action='play' to start playback immediately or 'search' to only view results.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "query" to PropertySchemaPayload("STRING", "Track, artist, or genre to play/search on Spotify"),
                        "action" to PropertySchemaPayload("STRING", "'play' to automatically start playback immediately, or 'search' to view results")
                    ),
                    required = listOf("query")
                )
            ),
            FunctionDeclarationPayload(
                name = "play_random_music",
                description = "Picks and plays music from a randomized mood/category pool (peaceful, lofi, old songs, love songs, instrumental, focus, relaxing, workout, sleep).",
                parameters = FunctionParametersPayload(type = "OBJECT")
            ),
            FunctionDeclarationPayload(
                name = "send_whatsapp_message",
                description = "Sends a WhatsApp message to a recipient contact name or phone number. Locates and presses Send button and verifies.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "target" to PropertySchemaPayload("STRING", "Contact name or phone number of recipient"),
                        "message" to PropertySchemaPayload("STRING", "The message content to send")
                    ),
                    required = listOf("target", "message")
                )
            ),
            FunctionDeclarationPayload(
                name = "open_whatsapp_chat",
                description = "Opens a WhatsApp conversation with a recipient contact name or phone number.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "target" to PropertySchemaPayload("STRING", "Contact name or phone number")
                    ),
                    required = listOf("target")
                )
            ),
            FunctionDeclarationPayload(
                name = "whatsapp_call",
                description = "Initiates a WhatsApp voice or video call with a contact.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "target" to PropertySchemaPayload("STRING", "Contact name or phone number"),
                        "is_video" to PropertySchemaPayload("STRING", "'true' for video call, 'false' for voice call")
                    ),
                    required = listOf("target")
                )
            ),
            FunctionDeclarationPayload(
                name = "whatsapp_media_control",
                description = "Controls media (plays/pauses voice note or video) visible in WhatsApp chat.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "action" to PropertySchemaPayload("STRING", "play or pause")
                    )
                )
            ),
            FunctionDeclarationPayload(
                name = "make_phone_call",
                description = "Places a phone call to a contact name or phone number with disambiguation.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "target" to PropertySchemaPayload("STRING", "Contact name or phone number")
                    ),
                    required = listOf("target")
                )
            ),
            FunctionDeclarationPayload(
                name = "phone_call_control",
                description = "Controls active or incoming phone calls: answer, end, speaker_on, speaker_off, mute, unmute.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "action" to PropertySchemaPayload("STRING", "answer, end, speaker_on, speaker_off, mute, or unmute")
                    ),
                    required = listOf("action")
                )
            ),
            FunctionDeclarationPayload(
                name = "web_search",
                description = "Performs Google search in Chrome browser. CRITICAL: ONLY call this tool when the user EXPLICITLY commands to search the web or Google. NEVER call this tool for normal conversation, factual questions, or cricket/IPL questions (answer directly from internal intelligence).",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "query" to PropertySchemaPayload("STRING", "Search query"),
                        "open_first" to PropertySchemaPayload("STRING", "'true' to auto-open first result, 'false' to show results")
                    ),
                    required = listOf("query")
                )
            ),
            FunctionDeclarationPayload(
                name = "open_websites",
                description = "Opens multiple websites (e.g. 'google, youtube, github, instagram') each in its own tab, following the <=8 tab threshold rule.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "sites" to PropertySchemaPayload("STRING", "Comma-separated list of websites or URLs")
                    ),
                    required = listOf("sites")
                )
            ),
            FunctionDeclarationPayload(
                name = "browser_action",
                description = "Controls browser: new_tab, close_tab, refresh, scroll_down, scroll_up, click_first_result, read_content.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "action" to PropertySchemaPayload("STRING", "new_tab, close_tab, refresh, scroll_down, scroll_up, or click_first_result")
                    ),
                    required = listOf("action")
                )
            ),
            FunctionDeclarationPayload(
                name = "analyze_screen",
                description = "Captures a FRESH screenshot and analyzes on-screen content (OCR, scene, product specs, buttons) with orange border visualization.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "prompt" to PropertySchemaPayload("STRING", "What to look for on screen (e.g. describe screen, read text, product analysis)")
                    )
                )
            ),
            FunctionDeclarationPayload(
                name = "take_camera_photo",
                description = "Takes a photo by pressing the camera shutter button.",
                parameters = FunctionParametersPayload(type = "OBJECT")
            ),
            FunctionDeclarationPayload(
                name = "record_camera_video",
                description = "Records a video with the camera for specified number of seconds (e.g. 30 seconds).",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "seconds" to PropertySchemaPayload("STRING", "Duration in seconds to record (default: '30')")
                    )
                )
            ),
            FunctionDeclarationPayload(
                name = "switch_camera_lens",
                description = "Switches/flips the camera between front selfie camera and back/rear camera.",
                parameters = FunctionParametersPayload(type = "OBJECT")
            ),
            FunctionDeclarationPayload(
                name = "stop_assistant",
                description = "Stops the AI assistant session and closes the assistant.",
                parameters = FunctionParametersPayload(type = "OBJECT")
            ),
            FunctionDeclarationPayload(
                name = "set_screen_visualization",
                description = "Turns the persistent thin orange border screen visualization indicator on or off.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "enable" to PropertySchemaPayload("STRING", "'true' to turn on, 'false' to turn off")
                    ),
                    required = listOf("enable")
                )
            ),
            FunctionDeclarationPayload(
                name = "read_notifications",
                description = "Reads recent notifications the user granted access to, optionally filtered by app.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "app" to PropertySchemaPayload("STRING", "Optional app filter such as whatsapp, instagram, telegram, gmail")
                    )
                )
            ),
            FunctionDeclarationPayload(
                name = "get_device_status",
                description = "Gets real-time device battery percentage, charging state, current time, date, GPS location, or live weather.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "query_type" to PropertySchemaPayload("STRING", "battery, time, date, location, or weather")
                    ),
                    required = listOf("query_type")
                )
            ),
            FunctionDeclarationPayload(
                name = "set_torch",
                description = "Turns device flashlight/torch ON or OFF.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "on" to PropertySchemaPayload("STRING", "'true' for on, 'false' for off")
                    ),
                    required = listOf("on")
                )
            ),
            FunctionDeclarationPayload(
                name = "set_volume",
                description = "Sets media volume to an absolute percentage (0-100) or relative increase/decrease/mute.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "percent" to PropertySchemaPayload("STRING", "Volume percentage 0 to 100"),
                        "adjust" to PropertySchemaPayload("STRING", "'up', 'down', or 'mute'")
                    )
                )
            ),
            FunctionDeclarationPayload(
                name = "set_brightness",
                description = "Sets screen brightness to an absolute percentage (0-100) or relative increase/decrease.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "percent" to PropertySchemaPayload("STRING", "Brightness percentage 0 to 100"),
                        "adjust" to PropertySchemaPayload("STRING", "'up' or 'down'")
                    )
                )
            ),
            FunctionDeclarationPayload(
                name = "open_settings_panel",
                description = "Opens system settings panel for Wi-Fi, Bluetooth, Mobile Data, DND, Alarms, Battery, Sound, Display, or Accessibility.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "panel" to PropertySchemaPayload("STRING", "wifi, bluetooth, data, dnd, alarms, battery, sound, display, or accessibility")
                    ),
                    required = listOf("panel")
                )
            ),
            FunctionDeclarationPayload(
                name = "set_alarm",
                description = "Sets an alarm on the device clock.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "hour" to PropertySchemaPayload("STRING", "Hour 0-23"),
                        "minute" to PropertySchemaPayload("STRING", "Minute 0-59"),
                        "tomorrow" to PropertySchemaPayload("STRING", "'true' if alarm is for tomorrow"),
                        "recurring" to PropertySchemaPayload("STRING", "'true' for repeating alarm"),
                        "message" to PropertySchemaPayload("STRING", "Label for the alarm")
                    ),
                    required = listOf("hour", "minute")
                )
            ),
            FunctionDeclarationPayload(
                name = "set_reminder",
                description = "Sets a reminder or timer with message and time.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "message" to PropertySchemaPayload("STRING", "Reminder text"),
                        "in_minutes" to PropertySchemaPayload("STRING", "Number of minutes from now (e.g. '10')"),
                        "hour" to PropertySchemaPayload("STRING", "Specific target hour (optional)"),
                        "minute" to PropertySchemaPayload("STRING", "Specific target minute (optional)")
                    ),
                    required = listOf("message")
                )
            ),
            FunctionDeclarationPayload(
                name = "install_app",
                description = "Opens the official Google Play Store search/install flow for an app.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "app_name" to PropertySchemaPayload("STRING", "Name of the app to install")
                    ),
                    required = listOf("app_name")
                )
            ),
            FunctionDeclarationPayload(
                name = "uninstall_app",
                description = "Opens standard Android uninstall confirmation flow for an app.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "app_name" to PropertySchemaPayload("STRING", "Name of the app to uninstall or 'current'")
                    ),
                    required = listOf("app_name")
                )
            ),
            FunctionDeclarationPayload(
                name = "manage_photos",
                description = "Manages photos: open gallery, show latest photo, or delete photo with confirmation.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "action" to PropertySchemaPayload("STRING", "open_gallery, show_latest, or delete_latest")
                    ),
                    required = listOf("action")
                )
            ),
            FunctionDeclarationPayload(
                name = "code_automation",
                description = "Generates code across languages and saves it to a file, opening in an editor.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "prompt" to PropertySchemaPayload("STRING", "Description of code to generate"),
                        "editor" to PropertySchemaPayload("STRING", "Optional editor name")
                    ),
                    required = listOf("prompt")
                )
            ),
            FunctionDeclarationPayload(
                name = "execute_device_command",
                description = "Runs any multi-step Hindi/Hinglish/English device command exactly as spoken: open/close apps, search, media, torch, battery, screen, WhatsApp send, etc.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "spoken_text" to PropertySchemaPayload("STRING", "The full user command in original language")
                    ),
                    required = listOf("spoken_text")
                )
            ),
            FunctionDeclarationPayload(
                name = "cancel_task",
                description = "Cancels the current automation sequence or task.",
                parameters = FunctionParametersPayload(type = "OBJECT")
            ),
            FunctionDeclarationPayload(
                name = "remember_fact",
                description = "Saves any useful fact, personal detail (name, email, routine, preferences), or favorite item (food, music, team) to long-term memory. Call this proactively whenever user mentions preferences or when asked. Never save passwords or card pins.",
                parameters = FunctionParametersPayload(
                    properties = mapOf("fact" to PropertySchemaPayload("STRING", "The concise fact or preference to remember")),
                    required = listOf("fact")
                )
            ),
            FunctionDeclarationPayload(
                name = "recall_memories",
                description = "Search the user's saved memories when they ask what Luna remembers or when a saved preference is relevant.",
                parameters = FunctionParametersPayload(
                    properties = mapOf("query" to PropertySchemaPayload("STRING", "Topic or keywords to search for")),
                    required = listOf("query")
                )
            ),
            FunctionDeclarationPayload(
                name = "forget_memory",
                description = "Delete one saved memory by its ID, only when the user asks to forget that specific item.",
                parameters = FunctionParametersPayload(
                    properties = mapOf("memory_id" to PropertySchemaPayload("STRING", "ID returned by recall_memories")),
                    required = listOf("memory_id")
                )
            ),
            FunctionDeclarationPayload(
                name = "clear_memories",
                description = "Permanently delete all saved memories, only when the user explicitly asks to clear all memory.",
                parameters = FunctionParametersPayload(type = "OBJECT")
            ),
            FunctionDeclarationPayload(
                name = "navigate_to",
                description = "Starts Google Maps navigation from device GPS location to the specified destination.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "destination" to PropertySchemaPayload("STRING", "Destination name or address (e.g. 'Mumbai Airport', 'home', 'office')")
                    ),
                    required = listOf("destination")
                )
            ),
            FunctionDeclarationPayload(
                name = "deep_research",
                description = "Performs multi-source Tavily Web Search and deep research when user asks for latest news, today's sports scores/matches, current prices, or real-time web facts.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "query" to PropertySchemaPayload("STRING", "Search query for Tavily Web Search")
                    ),
                    required = listOf("query")
                )
            ),
            FunctionDeclarationPayload(
                name = "set_auto_response",
                description = "Enables or disables automatic rule-based replies to incoming messages when user is sleeping or busy.",
                parameters = FunctionParametersPayload(
                    type = "OBJECT",
                    properties = mapOf(
                        "enabled" to PropertySchemaPayload("STRING", "'true' to enable, 'false' to disable"),
                        "message" to PropertySchemaPayload("STRING", "Custom reply text, e.g. 'Sir is currently sleeping.'")
                    ),
                    required = listOf("enabled")
                )
            )
        )
        return listOf(ToolPayload(declarations))
    }

    private fun sendSetupMessage(ws: WebSocket) {
        val setupPayload = SetupPayload(
            model = model,
            generationConfig = GenerationConfigPayload(
                responseModalities = listOf("AUDIO"),
                speechConfig = SpeechConfigPayload(
                    voiceConfig = VoiceConfigPayload(
                        prebuiltVoiceConfig = PrebuiltVoiceConfigPayload(voiceName = voiceName)
                    )
                )
            ),
            tools = buildDeviceTools(),
            systemInstruction = ContentPayload(
                parts = listOf(PartTextPayload(text = systemPrompt))
            )
        )

        val bidiSetup = GeminiBidiSetup(setup = setupPayload)
        val json = gson.toJson(bidiSetup)
        Log.d(TAG, "Sending BidiSetup payload with device tools to Gemini Live")
        ws.send(json)
    }

    fun sendToolResponse(callId: String, functionName: String, output: String) {
        if (!isConnected.get() || webSocket == null) return
        try {
            val responsePayload = GeminiToolResponse(
                toolResponse = ToolResponsePayload(
                    functionResponses = listOf(
                        FunctionResponsePayload(
                            id = callId,
                            name = functionName,
                            response = mapOf("output" to output)
                        )
                    )
                )
            )
            val json = gson.toJson(responsePayload)
            LunaLogger.d(TAG, "Sending tool response for $functionName ($callId): $output")
            webSocket?.send(json)
        } catch (e: Exception) {
            LunaLogger.e(TAG, "Error sending tool response: ${e.message}")
        }
    }

    fun sendToolResponse(callId: String, functionName: String, response: ToolResponse) {
        sendToolResponse(callId, functionName, response.toJson())
    }

    fun sendScreenFrame(jpeg: ByteArray, prompt: String) {
        if (!isConnected.get() || webSocket == null) return
        try {
            val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
            val payload = mapOf(
                "clientContent" to mapOf(
                    "turns" to listOf(
                        mapOf(
                            "role" to "user",
                            "parts" to listOf(
                                mapOf("inlineData" to mapOf("mimeType" to "image/jpeg", "data" to b64)),
                                mapOf("text" to prompt)
                            )
                        )
                    ),
                    "turnComplete" to true
                )
            )
            webSocket?.send(gson.toJson(payload))
        } catch (e: Exception) {
            Log.e(TAG, "Error sending screen frame: ${e.message}")
        }
    }

    private fun handleIncomingMessage(jsonText: String) {
        try {
            val jsonObject = JsonParser.parseString(jsonText).asJsonObject

            // 1. Tool Calls (Function execution from model)
            val toolCallObj = when {
                jsonObject.has("toolCall") -> jsonObject.getAsJsonObject("toolCall")
                jsonObject.has("serverContent") && jsonObject.getAsJsonObject("serverContent").has("toolCall") ->
                    jsonObject.getAsJsonObject("serverContent").getAsJsonObject("toolCall")
                else -> null
            }
            if (toolCallObj != null && toolCallObj.has("functionCalls")) {
                val functionCalls = toolCallObj.getAsJsonArray("functionCalls")
                for (i in 0 until functionCalls.size()) {
                    val fc = functionCalls.get(i).asJsonObject
                    val callId = if (fc.has("id")) fc.get("id").asString else "call_$i"
                    val fnName = if (fc.has("name")) fc.get("name").asString else ""
                    val argsMap = mutableMapOf<String, String>()
                    if (fc.has("args")) {
                        val argsObj = fc.getAsJsonObject("args")
                        for ((key, value) in argsObj.entrySet()) {
                            if (value.isJsonPrimitive) {
                                argsMap[key] = value.asString
                            }
                        }
                    }
                    Log.i(TAG, "Received toolCall from Gemini Live: $fnName ($callId) args=$argsMap")
                    listener.onToolCallReceived(callId, fnName, argsMap)
                }
            }

            // 2. Server Content (Transcriptions and Audio)
            if (jsonObject.has("serverContent")) {
                val serverContent = jsonObject.getAsJsonObject("serverContent")

                if (serverContent.has("inputTranscription")) {
                    serverContent.getAsJsonObject("inputTranscription")
                        ?.get("text")?.asString?.takeIf { it.isNotBlank() }
                        ?.let(listener::onUserTextReceived)
                }
                if (serverContent.has("interimInputTranscription")) {
                    serverContent.getAsJsonObject("interimInputTranscription")
                        ?.get("text")?.asString?.takeIf { it.isNotBlank() }
                        ?.let(listener::onUserTextReceived)
                }
                if (serverContent.has("outputTranscription")) {
                    serverContent.getAsJsonObject("outputTranscription")
                        ?.get("text")?.asString?.takeIf { it.isNotBlank() }
                        ?.let(listener::onAssistantTextReceived)
                }

                // Interrupted (Barge-in)
                if (serverContent.has("interrupted") && serverContent.get("interrupted").asBoolean) {
                    Log.d(TAG, "Server signaled turn interrupted")
                    listener.onInterrupted()
                }

                // Model turn parts (Audio + Text)
                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getAsJsonObject("modelTurn")
                    if (modelTurn.has("parts")) {
                        val parts = modelTurn.getAsJsonArray("parts")
                        for (i in 0 until parts.size()) {
                            val part = parts.get(i).asJsonObject

                            // Text transcript
                            if (part.has("text")) {
                                val text = part.get("text").asString
                                if (!text.isNullOrBlank()) {
                                    listener.onAssistantTextReceived(text)
                                }
                            }

                            // Inline audio (24kHz Mono PCM Base64)
                            if (part.has("inlineData")) {
                                val inlineData = part.getAsJsonObject("inlineData")
                                if (inlineData.has("data")) {
                                    val base64Data = inlineData.get("data").asString
                                    val pcmBytes = Base64.decode(base64Data, Base64.DEFAULT)
                                    listener.onAudioDataReceived(pcmBytes)
                                }
                            }
                        }
                    }
                }

                // Turn completed
                if (serverContent.has("turnComplete") && serverContent.get("turnComplete").asBoolean) {
                    Log.d(TAG, "Server signaled turnComplete")
                    listener.onTurnCompleted()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing incoming server JSON: ${e.message}", e)
        }
    }

    fun sendAudioChunk(pcm16k: ByteArray) {
        if (!isConnected.get() || !isSetupComplete.get() || pcm16k.isEmpty()) return

        try {
            val base64Audio = Base64.encodeToString(pcm16k, Base64.NO_WRAP)
            val realtimeInput = GeminiRealtimeInput(
                realtimeInput = RealtimeAudioInput(
                    audio = RealtimeAudioPayload(
                        mimeType = "audio/pcm;rate=16000",
                        data = base64Audio
                    )
                )
            )
            val json = gson.toJson(realtimeInput)
            webSocket?.send(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio chunk: ${e.message}")
        }
    }

    /**
     * Hybrid VAD: the microphone stays open, but this tells Live API that the
     * current utterance ended.  It removes most of the wait after a question.
     */
    fun sendAudioStreamEnd() {
        if (!isConnected.get() || !isSetupComplete.get()) return
        try {
            val json = gson.toJson(
                GeminiRealtimeInput(
                    realtimeInput = RealtimeAudioInput(audioStreamEnd = true)
                )
            )
            webSocket?.send(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio stream end: ${e.message}")
        }
    }

    private fun startSessionRenewal() {
        sessionRenewalJob?.cancel()
        sessionRenewalJob = coroutineScope?.launch(Dispatchers.IO) {
            delay(GeminiConstants.SESSION_RENEWAL_MS)
            if (isActive && !isManuallyStopped.get()) {
                Log.d(TAG, "9-minute session threshold reached. Seamlessly renewing session...")
                webSocket?.close(1000, "Session renewal")
                initiateConnection()
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = coroutineScope?.launch(Dispatchers.IO) {
            delay(3000)
            if (!isManuallyStopped.get()) {
                Log.d(TAG, "Attempting auto-reconnect...")
                initiateConnection()
            }
        }
    }

    private fun stopTimers() {
        sessionRenewalJob?.cancel()
        sessionRenewalJob = null
    }

    fun disconnect() {
        isManuallyStopped.set(true)
        stopTimers()
        reconnectJob?.cancel()
        reconnectJob = null
        isConnected.set(false)

        try {
            webSocket?.close(1000, "Client stopped session")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing webSocket: ${e.message}")
        } finally {
            webSocket = null
        }
        listener.onConnectionStateChanged("OFFLINE")
    }
}
