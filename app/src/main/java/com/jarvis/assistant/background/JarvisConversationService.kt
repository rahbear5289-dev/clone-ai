package com.jarvis.assistant.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.util.Log
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import com.jarvis.assistant.data.model.contract.LunaEventTypes
import com.jarvis.assistant.data.model.contract.LunaEvent
import com.jarvis.assistant.runtime.BackgroundTaskManager
import com.jarvis.assistant.runtime.LunaEventBus
import com.jarvis.assistant.runtime.RuntimeStateStore
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.automation.SiriGlowOverlay
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.memory.LunaMemoryDatabase
import com.jarvis.assistant.data.memory.MemoryRepository
import com.jarvis.assistant.audio.VoskModelManager
import org.json.JSONObject
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener as VoskRecognitionListener
import org.vosk.android.SpeechService
import com.jarvis.assistant.audio.AudioPlayer
import com.jarvis.assistant.audio.AudioRecorder
import com.jarvis.assistant.data.model.ConversationState
import com.jarvis.assistant.data.model.contract.ToolResponse
import com.jarvis.assistant.network.GeminiLiveWebSocket
import com.jarvis.assistant.ui.home.MainActivity
import com.jarvis.assistant.util.PromptGenerator
import com.jarvis.assistant.util.DeviceAutomationManager
import com.jarvis.assistant.util.AppIndex
import com.jarvis.assistant.util.VoiceCommandParser
import com.jarvis.assistant.util.DeviceCommand
import com.jarvis.assistant.util.LunaLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Owns the *entire* Jarvis conversation lifecycle: the microphone, the audio
 
 * player, the Gemini Live WebSocket, the 120-second inactivity timer, and the
 * wake-word standby listener.
 *
 * This intentionally does NOT live inside the Activity/ViewModel. When the
 * user removes the app from Recents (or the screen turns off), Android
 * destroys the Activity and its ViewModel - if the live session lived there,
 * the conversation would die with it. Because everything here is owned by a
 * foreground service instead, the mic stays on and Jarvis keeps talking
 * normally even with the app closed. The Activity/ViewModel only *observes*
 * this service's state through the companion StateFlows below; they never
 * own the audio pipeline.
 */
class JarvisConversationService : Service() {

    companion object {
        const val ACTION_START_CONVERSATION = "com.jarvis.assistant.START_CONVERSATION"
        const val ACTION_STOP_ALL = "com.jarvis.assistant.STOP_ALL"
        const val ACTION_ENTER_STANDBY = "com.jarvis.assistant.ENTER_STANDBY"

        private const val CHANNEL_ID = "jarvis_conversation"
        private const val NOTIFICATION_ID = 41
        private const val INACTIVITY_TIMEOUT_MS = 120_000L
        private const val BACKGROUND_ANNOUNCEMENT =
            "Sir, if you want me, call my wake-up words."

        @Volatile private var activeInstance: JarvisConversationService? = null
        val instance: JarvisConversationService? get() = activeInstance

        private val _isSessionOn = MutableStateFlow(false)
        val isSessionOn: StateFlow<Boolean> = _isSessionOn.asStateFlow()

        private val _isStandby = MutableStateFlow(false)
        val isStandby: StateFlow<Boolean> = _isStandby.asStateFlow()

        private val _conversationState = MutableStateFlow(ConversationState.IDLE)
        val conversationState: StateFlow<ConversationState> = _conversationState.asStateFlow()

        private val _connectionStatus = MutableStateFlow("READY")
        val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

        private val _audioLevel = MutableStateFlow(0f)
        val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

        private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val events: SharedFlow<String> = _events.asSharedFlow()

        /** True once a wake word has just brought the conversation back, for a one-off UI toast. */
        private val _wakeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        val wakeEvents: SharedFlow<Unit> = _wakeEvents.asSharedFlow()

        fun setMicMuted(muted: Boolean) {
            activeInstance?.audioRecorder?.setMuted(muted)
        }

        /** True while a foreground instance of this service exists in any mode. */
        fun isServiceRunning(): Boolean = activeInstance != null
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // --- Active-conversation components ---
    private var audioRecorder: AudioRecorder? = null
    private var audioPlayer: AudioPlayer? = null
    private var liveWebSocket: GeminiLiveWebSocket? = null
    private var inactivityJob: Job? = null
    private val currentTurnAssistantText = StringBuilder()
    private val currentTurnUserText = StringBuilder()
    private var lastUserSpeechDetectedTime = 0L
    private var hasSpeechInCurrentUtterance = false
    private var hasSignalledUtteranceEnd = false

    // --- Standby / wake-word components (Pure Vosk) ---
    private var voskSpeechService: SpeechService? = null
    private var tts: TextToSpeech? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val siriGlowOverlay: SiriGlowOverlay by lazy { SiriGlowOverlay(applicationContext) }
    private var audioPlaybackCallback: AudioManager.AudioPlaybackCallback? = null

    // --- Device Automation ---
    private val deviceManager: DeviceAutomationManager by lazy {
        DeviceAutomationManager(applicationContext)
    }
    private var pendingDeviceCommandJob: Job? = null

    /** True while TTS device-feedback is playing; mic is suppressed so Luna never hears herself. */
    @Volatile private var feedbackSpeaking = false
    private var feedbackTts: TextToSpeech? = null

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        createChannel()
        // Warm the app index early so "open <app>" resolves instantly.
        AppIndex.warmUpAsync(applicationContext, DeviceAutomationManager.COMMON_PACKAGES)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateForegroundServiceTypes(includeMediaProjection = false)
        acquireWakeLock()

        when (intent?.action) {
            ACTION_START_CONVERSATION -> beginActiveConversation()
            ACTION_ENTER_STANDBY -> enterStandby(announce = true)
            ACTION_STOP_ALL, "com.jarvis.assistant.ACTION_STOP_SESSION" -> {
                fullStop()
                return START_NOT_STICKY
            }
            else -> {
                // System-restarted the service after it was killed (e.g. low
                // memory) with no explicit action. Nothing meaningful to
                // resume automatically; just keep the process quiet until the
                // user (or Recents removal) triggers a real action again.
                if (!_isSessionOn.value && !_isStandby.value) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    // ---------------------------------------------------------------------
    // Active conversation
    // ---------------------------------------------------------------------

    private fun beginActiveConversation() {
        if (_isSessionOn.value) return
        exitStandbyListening()

        val app = application as JarvisApp
        val preferences = app.preferences
        val apiKey = preferences.apiKey.trim()
        if (apiKey.isBlank()) {
            serviceScope.launch { _events.emit("Please configure your Gemini API Key in Settings first.") }
            fullStop()
            return
        }

        _isSessionOn.value = true
        _isStandby.value = false
        lastUserSpeechDetectedTime = System.currentTimeMillis()
        startInactivityTimer()
        _conversationState.value = ConversationState.SPEAKING
        currentTurnAssistantText.clear()
        RuntimeStateStore.setVoiceState("ACTIVE")
        LunaEventBus.publish(LunaEvent(type = LunaEventTypes.VOICE_STARTED, payload = mapOf("source" to "Gemini Live")))
        updateNotification()

        audioPlayer = AudioPlayer(
            onPlaybackStateChanged = { isPlaying ->
                if (isPlaying) {
                    _conversationState.value = ConversationState.SPEAKING
                } else {
                    finalizeTurn()
                    if (_isSessionOn.value && !feedbackSpeaking) {
                        _conversationState.value = ConversationState.IDLE
                    }
                }
            },
            onPlaybackAmplitude = { amp ->
                if (_conversationState.value == ConversationState.SPEAKING && !feedbackSpeaking) {
                    _audioLevel.value = amp
                }
            }
        ).apply { start() }

        // Immediate zero-latency startup: No robotic TTS delay.
        // Direct neural Gemini Live connection established in under 200ms.
        _conversationState.value = ConversationState.LISTENING
        feedbackSpeaking = false

        val systemPrompt = PromptGenerator.generateSystemPrompt(
            personality = preferences.personality,
            userName = preferences.userName
        )

        liveWebSocket = GeminiLiveWebSocket(
            apiKey = apiKey,
            model = preferences.aiModel,
            voiceName = preferences.voice,
            systemPrompt = systemPrompt,
            listener = object : GeminiLiveWebSocket.Listener {
                override fun onConnectionStateChanged(status: String) {
                    _connectionStatus.value = status
                    if (status == "LIVE" && !feedbackSpeaking && _conversationState.value == ConversationState.IDLE) {
                        _conversationState.value = ConversationState.LISTENING
                    }
                }

                override fun onAudioDataReceived(pcmData: ByteArray) {
                    _conversationState.value = ConversationState.SPEAKING
                    audioPlayer?.enqueueAudio(pcmData)
                }

                override fun onAssistantTextReceived(textChunk: String) {
                    currentTurnAssistantText.append(textChunk)
                    val assistantText = currentTurnAssistantText.toString().lowercase(Locale.ROOT)
                    if (isAssistantBackgroundAnnouncement(assistantText)) {
                        LunaLogger.i("JarvisService", "Assistant acknowledged background transition: '$assistantText'")
                        enterStandby(announce = true)
                    }
                    // Free-text regex execution removed in accordance with FR-JSON-3.
                    // Gemini Live function calling via onToolCallReceived is the authoritative execution path.
                }

                override fun onUserTextReceived(textChunk: String) {
                    lastUserSpeechDetectedTime = System.currentTimeMillis()
                    currentTurnUserText.append(" ").append(textChunk)
                    val userText = currentTurnUserText.toString().lowercase(Locale.ROOT)
                    if (isBackgroundCommand(userText)) {
                        LunaLogger.i("JarvisService", "Detected background command in accumulated user speech: '$userText'")
                        pendingDeviceCommandJob?.cancel()
                        enterStandby(announce = true)
                        return
                    }
                    // Execute device commands from user speech (instant, no AI
                    // round-trip needed). Debounced: streaming transcription
                    // arrives in chunks, so we wait for the sentence to settle
                    // and run it exactly once.
                    scheduleDeviceCommand(userText)
                }

                override fun onToolCallReceived(callId: String, functionName: String, args: Map<String, String>) {
                    handleModelToolCall(callId, functionName, args)
                }

                override fun onInterrupted() {
                    audioPlayer?.flush()
                    currentTurnAssistantText.clear()
                    currentTurnUserText.clear()
                    _conversationState.value = ConversationState.LISTENING
                }

                override fun onTurnCompleted() {
                    // Handled when playback drains in AudioPlayer.
                }

                override fun onError(message: String) {
                    serviceScope.launch { _events.emit(message) }
                }
            }
        ).apply { connect(serviceScope) }

        audioRecorder = AudioRecorder { chunk, amplitude ->
            if (_isSessionOn.value) {
                // Strict self-speech suppression (Active bug fix):
                // While assistant is speaking or outputting audio (plus echo tail grace period),
                // or while a TTS device-confirmation is playing, ignore microphone input
                // stream to prevent recursive listening loops and self-echo bugs.
                val isAssistantSpeaking = audioPlayer?.isSelfSpeechActive(650L) == true ||
                        _conversationState.value == ConversationState.SPEAKING ||
                        feedbackSpeaking

                if (isAssistantSpeaking) {
                    // Suppress microphone chunk from feeding back into AI
                    return@AudioRecorder
                }

                liveWebSocket?.sendAudioChunk(chunk)

                if (amplitude > 0.15f) {
                    lastUserSpeechDetectedTime = System.currentTimeMillis()
                    if (!hasSpeechInCurrentUtterance) {
                        // New utterance: drop any half-parsed device command.
                        pendingDeviceCommandJob?.cancel()
                        currentTurnUserText.clear()
                    }
                    hasSpeechInCurrentUtterance = true
                    hasSignalledUtteranceEnd = false
                    if (_conversationState.value != ConversationState.SPEAKING) {
                        _conversationState.value = ConversationState.LISTENING
                        _audioLevel.value = amplitude
                    }
                } else if (_conversationState.value == ConversationState.LISTENING) {
                    _audioLevel.value = amplitude
                    val silentDuration = System.currentTimeMillis() - lastUserSpeechDetectedTime
                    if (hasSpeechInCurrentUtterance && !hasSignalledUtteranceEnd && silentDuration > 600) {
                        hasSignalledUtteranceEnd = true
                        liveWebSocket?.sendAudioStreamEnd()
                    }
                    if (silentDuration > 900 && lastUserSpeechDetectedTime > 0) {
                        _conversationState.value = ConversationState.THINKING
                    }
                }
            }
        }.apply {
            setMuted(preferences.isMicMuted)
            start(serviceScope)
        }
    }

    private fun finalizeTurn() {
        val reply = currentTurnAssistantText.toString().trim()
        if (reply.isNotEmpty()) {
            (application as JarvisApp).chatRepository.addTurn(
                userText = currentTurnUserText.toString().trim().ifEmpty { "Spoken user query" },
                jarvisText = reply
            )
            currentTurnAssistantText.clear()
            currentTurnUserText.clear()
        }
    }



    private fun stopActiveConversation() {
        inactivityJob?.cancel()
        inactivityJob = null
        pendingDeviceCommandJob?.cancel()
        pendingDeviceCommandJob = null
        startupPulseJob?.cancel()
        startupPulseJob = null
        startupTts?.shutdown()
        startupTts = null
        releaseFeedbackTts()
        _isSessionOn.value = false
        finalizeTurn()

        audioRecorder?.stop()
        audioRecorder = null

        audioPlayer?.flush()
        audioPlayer?.release()
        audioPlayer = null

        liveWebSocket?.disconnect()
        liveWebSocket = null

        // Always hide the Siri glow border when conversation stops
        siriGlowOverlay.hide()

        _conversationState.value = ConversationState.IDLE
        _connectionStatus.value = "READY"
        _audioLevel.value = 0f
        RuntimeStateStore.setVoiceState("IDLE")
        LunaEventBus.publish(LunaEvent(type = LunaEventTypes.VOICE_STOPPED, payload = mapOf("reason" to "session_stopped")))
    }

    private fun startInactivityTimer() {
        inactivityJob?.cancel()
        inactivityJob = serviceScope.launch {
            while (isActive && _isSessionOn.value) {
                delay(15_000)
                if (System.currentTimeMillis() - lastUserSpeechDetectedTime >= INACTIVITY_TIMEOUT_MS) {
                    enterStandby(announce = true)
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Standby / wake-word listening (Pure Vosk - Continuous Streaming)
    // ---------------------------------------------------------------------

    private fun enterStandby(announce: Boolean) {
        if (_isStandby.value) return
        stopActiveConversation()
        _isStandby.value = true
        updateNotification()
        if (announce) {
            announceThenListen()
        } else {
            startWakeListening()
        }
    }

    private fun exitStandbyListening() {
        _isStandby.value = false
        unregisterAudioPlaybackMonitor()
        try {
            voskSpeechService?.stop()
        } catch (e: Exception) {
            LunaLogger.e("JarvisService", "Error stopping Vosk SpeechService: ${e.message}", e)
        }
        voskSpeechService = null

        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun announceThenListen() {
        // Directly enter wake listening without robot TTS to prevent double voice conflict with Gemini AI
        tts?.shutdown()
        tts = null
        if (_isStandby.value) startWakeListening()
    }

    private fun startWakeListening() {
        if (!_isStandby.value) return
        if (voskSpeechService != null) return

        val model = VoskModelManager.model
        if (model == null) {
            LunaLogger.i("JarvisService", "Vosk model still loading; triggering init and will start once ready")
            VoskModelManager.init(applicationContext, serviceScope)
            serviceScope.launch {
                VoskModelManager.isModelReady.collect { ready ->
                    if (ready && _isStandby.value && voskSpeechService == null) {
                        startWakeListening()
                    }
                }
            }
            return
        }

        serviceScope.launch(Dispatchers.Main) {
            // Give the audio hardware 350ms to release the microphone from Gemini Live
            delay(350)
            if (!_isStandby.value || voskSpeechService != null) return@launch

            try {
                // Focus grammar on Luna and legacy wake words
                val grammar = "[\"hello luna\", \"hi luna\", \"wait luna\", \"hey luna\", \"ok luna\", \"luna\", \"hey jarvis\", \"hi jarvis\", \"hello jarvis\", \"jarvis\", \"[unk]\"]"
                val rec = Recognizer(model, 16000.0f, grammar)
                val service = SpeechService(rec, 16000.0f)
                service.startListening(voskListener)
                voskSpeechService = service

                RuntimeStateStore.setVoiceState("STANDBY")
                registerAudioPlaybackMonitor()
                if (isSpeakerMediaOrCallActive()) {
                    service.setPause(true)
                    LunaLogger.i("JarvisService", "Vosk standby listening active but initially paused (speaker media/call active)")
                } else {
                    LunaLogger.i("JarvisService", "Luna wake-word listening active (continuous mic, noise-filtered)")
                }
            } catch (e: Exception) {
                LunaLogger.e("JarvisService", "Failed to start Vosk SpeechService: ${e.message}", e)
                try {
                    voskSpeechService?.stop()
                } catch (_: Exception) {}
                voskSpeechService = null
                _events.emit("Mic error m: ${e.message}")
            }
        }
    }

    private val voskListener = object : VoskRecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            // Deliberately ignore partial speculative results.
        }

        override fun onResult(hypothesis: String?) {
            checkVoskHypothesis(hypothesis)
        }

        override fun onFinalResult(hypothesis: String?) {
            checkVoskHypothesis(hypothesis)
        }

        override fun onError(exception: Exception?) {
            LunaLogger.e("JarvisService", "Vosk error: ${exception?.message}", exception)
            if (_isStandby.value) {
                serviceScope.launch(Dispatchers.Main) {
                    delay(2000)
                    if (_isStandby.value && voskSpeechService == null) {
                        startWakeListening()
                    }
                }
            }
        }

        override fun onTimeout() {
            if (_isStandby.value && voskSpeechService == null) {
                startWakeListening()
            }
        }
    }

    private fun checkVoskHypothesis(hypothesisJson: String?) {
        if (hypothesisJson.isNullOrBlank() || !_isStandby.value) return

        if (isSpeakerMediaOrCallActive()) {
            LunaLogger.d("JarvisService", "Ignored Vosk hypothesis during speaker media/call playback")
            return
        }

        try {
            val json = JSONObject(hypothesisJson)
            if (!json.has("text")) return
            val text = json.getString("text").trim()
            if (text.isEmpty()) return

            LunaLogger.d("JarvisService", "Vosk standby recognized phrase: '$text'")
            if (containsWakeWord(text)) {
                LunaLogger.i("JarvisService", "Confirmed Luna wake word in phrase: '$text'")
                onWakeWordDetected()
            }
        } catch (e: Exception) {
            LunaLogger.e("JarvisService", "Error in checkVoskHypothesis: ${e.message}", e)
        }
    }

    /** Recognizes: "Hello Luna", "Hi Luna", "Wait Luna", "Hey Luna", "Luna" */
    private fun containsWakeWord(phrase: String): Boolean {
        val words = phrase.lowercase(Locale.ROOT)
        return Regex("\\b(hello luna|hi luna|wait luna|hey luna|ok luna|luna|hey jarvis|hi jarvis|hello jarvis|ok jarvis|jarvis)\\b").containsMatchIn(words)
    }

    private fun containsWakeWordLegacy(phrase: String): Boolean {
        val words = phrase.lowercase(Locale.ROOT)
        val legacyKeywords = listOf(
            "hi luna", "hey luna", "hello luna", "ok luna", "luna",
            "hi luna", "hey luna", "hello luna", "ok luna", "luna",
            "turn on the light",
            "wake up",
        )
        return legacyKeywords.any { keyword -> words.contains(keyword) }
    }

    private fun call_number(number: String) {
        val callIntent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$number")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(callIntent)
    }

    private fun send_message(number: String, message: String) {
        val sendIntent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:$number")
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(sendIntent)
    }
    
    private fun onWakeWordDetected() {
        if (!_isStandby.value) return
        exitStandbyListening()
        serviceScope.launch { _wakeEvents.emit(Unit) }
        LunaEventBus.publish(LunaEvent(type = LunaEventTypes.WAKE_WORD_DETECTED, payload = mapOf("engine" to "Vosk")))
        acknowledgeThenActivate()
    }

    private fun acknowledgeThenActivate() {
        // Directly begin conversation without robot TTS to maintain single AI voice
        tts?.shutdown()
        tts = null
        beginActiveConversation()
    }

    // ---------------------------------------------------------------------
    // Lifecycle plumbing
    // ---------------------------------------------------------------------

    private fun fullStop() {
        stopActiveConversation()
        exitStandbyListening()
        _isStandby.value = false
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:JarvisConversation")
            .also { it.acquire(6 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        stopActiveConversation()
        exitStandbyListening()
        unregisterAudioPlaybackMonitor()
        try {
            voskSpeechService?.stop()
        } catch (_: Exception) {}
        voskSpeechService = null
        siriGlowOverlay.release()
        releaseWakeLock()
        serviceScope.cancel()
        if (activeInstance === this) activeInstance = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // Media Playback & Call Guard Helpers
    // ---------------------------------------------------------------------

    private fun isBackgroundCommand(text: String): Boolean {
        val backgroundPattern = Regex(
            "\\b(jarvis|luna)[,;.!?\\s]*)?(go|stay|move|enter|switch)\\s+(to|into|in\\s+to|in)?\\s*(the\\s+)?(background|standby)\\b|" +
            "\\b(jarvis|luna)[,;.!?\\s]+(standby|sleep|background|go\\s+to\\s+sleep)\\b"
        )
        return backgroundPattern.containsMatchIn(text)
    }

    private fun isAssistantBackgroundAnnouncement(text: String): Boolean {
        return text.contains("going in the background") ||
               text.contains("going into the background") ||
               text.contains("going to the background") ||
               text.contains("going to background") ||
               text.contains("call my wake-up words") ||
               text.contains("call my wake up words")
    }

    /**
     * Schedules a device-command run once the streaming transcription settles.
     * Every new transcription chunk reschedules the run, so a sentence is
     * parsed and executed exactly once, on its complete text - never on a
     * partial chunk (which caused double-executions like "open whatsapp and
     * send..." launching the app mid-sentence).
     */
    private fun scheduleDeviceCommand(text: String) {
        pendingDeviceCommandJob?.cancel()
        pendingDeviceCommandJob = serviceScope.launch {
            delay(350)
            executeDeviceCommand(text)
        }
    }

    private fun executeDeviceCommand(text: String): Boolean {
        val steps = VoiceCommandParser.parseSequence(text)
        if (steps.isEmpty() && VoiceCommandParser.parse(text) == null) return false
        pendingDeviceCommandJob = serviceScope.launch {
            _conversationState.value = ConversationState.EXECUTING
            val result = try {
                deviceManager.executeSequence(text)
            } catch (e: Exception) {
                LunaLogger.e("JarvisService", "Device command failed: ${e.message}", e)
                com.jarvis.assistant.util.CommandResult(false, "Sorry, I couldn't complete that command.")
            }
            if (result.spoken.isBlank()) return@launch
            LunaLogger.i("JarvisService", "Device command ('$text') -> ${result.spoken}")
            _events.emit(result.spoken)
            speakFeedback(result.spoken)
        }
        return true
    }

    /**
     * Speaks a short confirmation/fallback out loud. The mic is suppressed for
     * the duration so the TTS output is never transcribed back as user speech
     * (the audio-to-text mismatch bug).
     */
    private fun speakFeedback(text: String) {
        // Robotic TTS is suppressed when Gemini Live is active to ensure ONLY the personal AI voice speaks
        if (liveWebSocket != null || _conversationState.value == ConversationState.SPEAKING) return
        feedbackSpeaking = true
        feedbackTts?.shutdown()
        feedbackTts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                feedbackTts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) = releaseFeedbackTts()
                    override fun onError(utteranceId: String?) = releaseFeedbackTts()
                })
                feedbackTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "device-feedback")
            } else {
                releaseFeedbackTts()
            }
        }
    }

    private fun releaseFeedbackTts() {
        feedbackSpeaking = false
        feedbackTts?.shutdown()
        feedbackTts = null
    }

    private fun handleModelToolCall(callId: String, functionName: String, args: Map<String, String>) {
        BackgroundTaskManager.submit(name = "tool:$functionName", timeoutMs = 30_000L) { _ ->
        @Suppress("UNUSED_EXPRESSION")
        serviceScope.launch {
            val result = try {
                when (functionName) {
                    "remember_fact", "recall_memories", "forget_memory", "clear_memories" -> {
                        val app = application as JarvisApp
                        if (!app.preferences.isMemoryEnabled) {
                            com.jarvis.assistant.util.CommandResult(false, "Memory is turned off. You can enable it in Settings.")
                        } else {
                            val memory = MemoryRepository(LunaMemoryDatabase.get(applicationContext).memoryDao())
                            when (functionName) {
                                "remember_fact" -> memory.remember(args["fact"] ?: "").fold(
                                    onSuccess = { id -> com.jarvis.assistant.util.CommandResult(true, "I'll remember that. Memory ID $id.") },
                                    onFailure = { error -> com.jarvis.assistant.util.CommandResult(false, error.message ?: "I couldn't save that memory.") }
                                )
                                "recall_memories" -> {
                                    val found = memory.recall(args["query"] ?: "")
                                    val spoken = if (found.isEmpty()) "I don't have any saved memories about that yet."
                                    else "I found ${found.size} saved ${if (found.size == 1) "memory" else "memories"}: " +
                                        found.joinToString(". ") { "ID ${it.id}: ${it.content}" }
                                    com.jarvis.assistant.util.CommandResult(true, spoken)
                                }
                                "forget_memory" -> {
                                    val id = args["memory_id"]?.toLongOrNull()
                                    if (id == null) com.jarvis.assistant.util.CommandResult(false, "That memory ID isn't valid.")
                                    else if (memory.forget(id)) com.jarvis.assistant.util.CommandResult(true, "I forgot that memory.")
                                    else com.jarvis.assistant.util.CommandResult(false, "I couldn't find that memory.")
                                }
                                else -> {
                                    memory.clear()
                                    com.jarvis.assistant.util.CommandResult(true, "All saved memories have been cleared.")
                                }
                            }
                        }
                    }
                    "execute_device_command" -> deviceManager.executeSequence(args["spoken_text"] ?: "")
                    "open_app" -> deviceManager.executeAsync(DeviceCommand.OpenApp(args["app_name"] ?: ""))
                    "switch_app" -> deviceManager.executeAsync(DeviceCommand.SwitchApp(args["from"], args["to"] ?: ""))
                    "close_app" -> deviceManager.closeAppByVoice(args["app_name"] ?: "")
                    "minimize_app" -> deviceManager.executeAsync(DeviceCommand.MinimizeApp(args["app_name"]))
                    "close_all_apps" -> deviceManager.executeAsync(DeviceCommand.CloseAllApps)
                    "open_all_apps" -> deviceManager.executeAsync(DeviceCommand.OpenAllApps)
                    "list_running_apps" -> deviceManager.executeAsync(DeviceCommand.ListRunningApps)
                    "go_to_home" -> deviceManager.executeAsync(DeviceCommand.OpenHome)
                    "go_back" -> deviceManager.executeAsync(DeviceCommand.GoBack)
                    "open_recents" -> deviceManager.executeAsync(DeviceCommand.OpenRecents)
                    "split_screen" -> deviceManager.executeAsync(DeviceCommand.SplitScreen)
                    "search_youtube" -> deviceManager.executeAsync(DeviceCommand.YouTubeSearch(args["query"] ?: ""))
                    "play_youtube" -> deviceManager.executeAsync(DeviceCommand.YouTubePlay(args["query"] ?: ""))
                    "play_nth_video" -> deviceManager.executeAsync(
                        DeviceCommand.PlayNthVideo(args["index"]?.toIntOrNull() ?: 0)
                    )
                    "media_control" -> deviceManager.executeAsync(
                        when (args["action"]?.lowercase(Locale.ROOT)) {
                            "play" -> DeviceCommand.MediaPlay
                            "pause" -> DeviceCommand.MediaPause
                            "resume" -> DeviceCommand.MediaPlay
                            "stop" -> DeviceCommand.MediaStop
                            "next" -> DeviceCommand.MediaNext
                            "previous" -> DeviceCommand.MediaPrevious
                            else -> DeviceCommand.MediaPlayPause
                        }
                    )
                    "media_seek" -> deviceManager.executeAsync(
                        DeviceCommand.MediaSeek(
                            args["seconds"]?.toIntOrNull() ?: 10,
                            args["forward"] != "false"
                        )
                    )
                    "media_speed" -> deviceManager.executeAsync(
                        DeviceCommand.MediaSpeed(args["speed"] ?: "1.0x")
                    )
                    "media_quality" -> deviceManager.executeAsync(
                        DeviceCommand.MediaQuality(args["quality"] ?: "auto")
                    )
                    "spotify_search" -> deviceManager.executeAsync(
                        DeviceCommand.SpotifySearch(
                            query = args["query"] ?: "",
                            autoPlay = args["action"]?.lowercase(Locale.ROOT) != "search"
                        )
                    )
                    "play_random_music" -> deviceManager.executeAsync(DeviceCommand.RandomMusic)
                    "send_whatsapp_message" -> deviceManager.executeAsync(
                        DeviceCommand.WhatsAppMessage(args["target"] ?: "", args["message"] ?: "")
                    )
                    "open_whatsapp_chat" -> deviceManager.executeAsync(
                        DeviceCommand.WhatsAppOpenChat(args["target"] ?: "")
                    )
                    "whatsapp_call" -> deviceManager.executeAsync(
                        DeviceCommand.WhatsAppCall(args["target"] ?: "", args["is_video"] == "true")
                    )
                    "whatsapp_media_control" -> deviceManager.executeAsync(
                        DeviceCommand.WhatsAppMediaControl(args["action"] ?: "play")
                    )
                    "make_phone_call" -> deviceManager.executeAsync(
                        DeviceCommand.PhoneCall(args["target"] ?: "")
                    )
                    "phone_call_control" -> deviceManager.executeAsync(
                        DeviceCommand.PhoneCallControl(args["action"] ?: "end")
                    )
                    "web_search" -> deviceManager.executeAsync(
                        DeviceCommand.WebSearch(args["query"] ?: "", args["open_first"] == "true")
                    )
                    "open_websites" -> {
                        val sitesList = (args["sites"] ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        deviceManager.executeAsync(DeviceCommand.OpenWebsites(sitesList))
                    }
                    "browser_action" -> deviceManager.executeAsync(
                        DeviceCommand.BrowserAction(args["action"] ?: "new_tab")
                    )
                    "analyze_screen" -> deviceManager.analyzeScreen(
                        args["prompt"] ?: "Describe what is currently visible on the phone screen."
                    )
                    "take_camera_photo" -> deviceManager.executeAsync(DeviceCommand.CameraTakePhoto)
                    "record_camera_video" -> deviceManager.executeAsync(
                        DeviceCommand.CameraRecordVideo(args["seconds"]?.toIntOrNull() ?: 30)
                    )
                    "switch_camera_lens" -> deviceManager.executeAsync(DeviceCommand.CameraSwitchLens)
                    "stop_assistant" -> deviceManager.executeAsync(DeviceCommand.StopAssistantSession)
                    "set_screen_visualization" -> deviceManager.executeAsync(
                        DeviceCommand.SetScreenVisualization(args["enable"] != "false")
                    )
                    "read_notifications" -> deviceManager.executeAsync(
                        DeviceCommand.ReadNotifications(args["app"])
                    )
                    "get_device_status" -> deviceManager.executeAsync(
                        DeviceCommand.GetDeviceStatus(args["query_type"] ?: "battery")
                    )
                    "set_torch" -> deviceManager.executeAsync(
                        DeviceCommand.Torch(args["on"] == "true")
                    )
                    "set_volume" -> {
                        val pct = args["percent"]?.toIntOrNull()
                        val adj = args["adjust"]?.lowercase(Locale.ROOT)
                        when {
                            adj == "mute" -> deviceManager.executeAsync(DeviceCommand.VolumeMute)
                            adj == "up" -> deviceManager.executeAsync(DeviceCommand.VolumeAdjust(true))
                            adj == "down" -> deviceManager.executeAsync(DeviceCommand.VolumeAdjust(false))
                            else -> deviceManager.executeAsync(DeviceCommand.VolumeSet(pct))
                        }
                    }
                    "set_brightness" -> {
                        val pct = args["percent"]?.toIntOrNull()
                        val adj = args["adjust"]?.lowercase(Locale.ROOT)
                        when {
                            adj == "up" -> deviceManager.executeAsync(DeviceCommand.BrightnessAdjust(true))
                            adj == "down" -> deviceManager.executeAsync(DeviceCommand.BrightnessAdjust(false))
                            else -> deviceManager.executeAsync(DeviceCommand.BrightnessSet(pct))
                        }
                    }
                    "open_settings_panel" -> deviceManager.executeAsync(
                        DeviceCommand.OpenSettingsPanel(args["panel"] ?: "settings")
                    )
                    "set_alarm" -> deviceManager.executeAsync(
                        DeviceCommand.SetAlarm(
                            args["hour"]?.toIntOrNull() ?: 7,
                            args["minute"]?.toIntOrNull() ?: 0,
                            args["tomorrow"] == "true",
                            args["recurring"] == "true",
                            args["message"]
                        )
                    )
                    "set_reminder" -> deviceManager.executeAsync(
                        DeviceCommand.SetReminder(
                            args["message"] ?: "Reminder",
                            args["hour"]?.toIntOrNull(),
                            args["minute"]?.toIntOrNull(),
                            args["in_minutes"]?.toIntOrNull()
                        )
                    )
                    "install_app" -> deviceManager.executeAsync(
                        DeviceCommand.InstallApp(args["app_name"] ?: "")
                    )
                    "uninstall_app" -> deviceManager.executeAsync(
                        DeviceCommand.UninstallApp(args["app_name"] ?: "")
                    )
                    "manage_photos" -> deviceManager.executeAsync(
                        DeviceCommand.ManagePhotos(args["action"] ?: "open_gallery")
                    )
                    "code_automation" -> deviceManager.executeAsync(
                        DeviceCommand.CodeAutomation(args["prompt"] ?: "", args["editor"])
                    )
                    "cancel_task" -> deviceManager.executeAsync(DeviceCommand.CancelTask)
                    else -> deviceManager.executeSequence(functionName.replace('_', ' ') + " " + args.values.joinToString(" "))
                }
            } catch (e: Exception) {
                LunaLogger.e("JarvisService", "Tool $functionName failed: ${e.message}", e)
                com.jarvis.assistant.util.CommandResult(false, "Action fail ho gayi.")
            }
            LunaLogger.i("JarvisService", "Model tool call executed: $functionName -> ${result.spoken}")
            LunaEventBus.publish(
                LunaEvent(
                    type = LunaEventTypes.TOOL_COMPLETED,
                    payload = mapOf(
                        "function" to functionName,
                        "callId" to callId,
                        "success" to result.success.toString()
                    )
                )
            )
            _events.emit(result.spoken)
            liveWebSocket?.sendToolResponse(callId, functionName, result.toToolResponse(functionName))
            // Single Voice Policy (FR-VOX-3, Bug #11): Local TTS is not invoked here because
            // Gemini Live naturally speaks the result via streaming PCM audio over WebSocket.
        }
        }
    }

    private fun registerAudioPlaybackMonitor() {
        if (audioPlaybackCallback != null) return
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val callback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
                if (!_isStandby.value) return
                val isSpeakerActive = isSpeakerMediaOrCallActive()
                try {
                    voskSpeechService?.setPause(isSpeakerActive)
                    if (isSpeakerActive) {
                        LunaLogger.d("JarvisService", "Vosk standby paused: media playing on phone speaker")
                    } else {
                        LunaLogger.d("JarvisService", "Vosk standby resumed: speaker audio stopped")
                    }
                } catch (e: Exception) {
                    LunaLogger.e("JarvisService", "Error toggling Vosk pause: ${e.message}", e)
                }
            }
        }
        try {
            audioManager.registerAudioPlaybackCallback(callback, Handler(Looper.getMainLooper()))
            audioPlaybackCallback = callback
        } catch (e: Exception) {
            LunaLogger.e("JarvisService", "Failed to register AudioPlaybackCallback: ${e.message}", e)
        }
    }

    private fun unregisterAudioPlaybackMonitor() {
        val callback = audioPlaybackCallback ?: return
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        try {
            audioManager?.unregisterAudioPlaybackCallback(callback)
        } catch (e: Exception) {
            LunaLogger.e("JarvisService", "Failed to unregister AudioPlaybackCallback: ${e.message}", e)
        }
        audioPlaybackCallback = null
    }

    /**
     * Returns true if audio from media (reels, music, YouTube) is actively coming
     * out of the phone's physical loudspeaker, or if a phone/VoIP call is in progress.
     */
    private fun isSpeakerMediaOrCallActive(): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false

        // 1. Phone call or VoIP call in progress
        if (audioManager.mode == AudioManager.MODE_IN_CALL ||
            audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
            return true
        }

        // 2. Active media playback (Instagram reels, YouTube, Spotify, etc.)
        if (audioManager.isMusicActive) {
            val hasHeadset = isHeadsetConnected(audioManager)
            // If NOT connected to headphones/earbuds, sound is blasting from the device speaker into the mic
            if (!hasHeadset) {
                return true
            }
        }

        return false
    }

    private fun isHeadsetConnected(audioManager: AudioManager): Boolean {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val hasHeadsetDevice = devices.any { device ->
            device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            device.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        @Suppress("DEPRECATION")
        val legacyCheck = audioManager.isWiredHeadsetOn ||
                          audioManager.isBluetoothA2dpOn ||
                          audioManager.isBluetoothScoOn
        return hasHeadsetDevice || legacyCheck
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The app was swiped away from Recents. Do NOT stop anything here -
        // that's exactly the case this service exists to survive. The mic,
        // the live session (or standby listener), and the notification all
        // keep running untouched.
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Luna-X", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun updateNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    fun updateForegroundServiceTypes(includeMediaProjection: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                var type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (includeMediaProjection && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    type = type or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
                startForeground(NOTIFICATION_ID, buildNotification(), type)
            } catch (e: Exception) {
                Log.w("JarvisService", "Failed to update foreground service type: ${e.message}")
            }
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): android.app.Notification {
        val (title, text) = when {
            _isSessionOn.value -> "Luna-X is listening" to "Conversation is active"
            _isStandby.value -> "Luna-X is in the background" to "Say \"Hey Luna\" to wake me up"
            else -> "Luna-X" to "Starting up\u2026"
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Stop", PendingIntent.getService(
                this, 0, Intent(this, JarvisConversationService::class.java).setAction(ACTION_STOP_ALL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
            .build()
    }
}
