package com.jarvis.assistant.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

sealed class UiTask {
    data class ClickSend(val packages: Set<String>, val result: CompletableDeferred<Boolean>) : UiTask()
    data class ClickFirstMatch(val packages: Set<String>, val result: CompletableDeferred<Boolean>) : UiTask()
    data class ClickNthVideo(val index: Int, val packages: Set<String>, val result: CompletableDeferred<Boolean>) : UiTask()
    data class ClickText(val text: String, val result: CompletableDeferred<Boolean>) : UiTask()
    data class SetText(val viewIds: List<String>, val text: String, val result: CompletableDeferred<Boolean>) : UiTask()
}

/**
 * System Automation Accessibility Service.
 * Provides supported UI actions: WhatsApp send-button tap, YouTube playback & video clicks,
 * Chrome search results, scroll, home, back, recents, and split-screen.
 */
class LunaAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "LunaA11y"
        @Volatile var instance: LunaAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null

        val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
        val YOUTUBE_PACKAGES = setOf("com.google.android.youtube", "com.google.android.apps.youtube.music")
        val CHROME_PACKAGES = setOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev")

        val SEND_IDS = listOf(
            "com.whatsapp:id/send",
            "com.whatsapp.w4b:id/send",
            "com.whatsapp:id/conversation_entry_action_button",
            "com.whatsapp.w4b:id/conversation_entry_action_button",
            "com.whatsapp:id/send_btn",
            "com.whatsapp.w4b:id/send_btn",
            "send",
            "send_btn"
        )

        val ENTRY_IDS = listOf(
            "com.whatsapp:id/entry",
            "com.whatsapp.w4b:id/entry",
            "entry"
        )

        val SEND_DESCRIPTIONS = listOf(
            "send", "भेजें", "bhejo", "bhejein", "enviar", "envoyer", "invia", "trimite", "kirim"
        )
    }

    private val pending = CopyOnWriteArrayList<UiTask>()
    private val lastPackage = AtomicReference<String?>(null)

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()
        if (!pkg.isNullOrBlank() && pkg != packageName) {
            lastPackage.set(pkg)
            DeviceStateStore.noteForeground(pkg)
        }
        drainPending()
    }

    fun foregroundPackage(): String? {
        rootInActiveWindow?.packageName?.toString()?.let { return it }
        return lastPackage.get()
    }

    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun splitScreen(): Boolean {
        return if (Build.VERSION.SDK_INT >= 24) {
            performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
        } else false
    }

    suspend fun dismissCurrentAppFromRecents(): Boolean {
        openRecents()
        delay(600)
        val (w, h) = getScreenMetrics()
        // In Android recents, swipe up from center to dismiss current app card
        val swiped = swipe(w / 2f, h * 0.65f, w / 2f, h * 0.15f, 220)
        delay(400)
        goHome()
        return swiped
    }

    // ==========================================
    // 1. WHATSAPP SEND BUTTON AUTOMATION (FIX)
    // ==========================================

    /**
     * Actively polls the window node tree for up to [timeoutMs] to locate
     * WhatsApp's send button, simulate a physical tap gesture, and verify.
     */
    suspend fun clickWhatsAppSend(timeoutMs: Long = 7_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = rootInActiveWindow
            if (root != null) {
                val clicked = tryClickSend(root, WHATSAPP_PACKAGES)
                if (clicked) {
                    // Small delay to allow WhatsApp to update entry field state
                    delay(350)
                    val verifyRoot = rootInActiveWindow
                    if (verifyRoot != null) {
                        val isSent = isWhatsAppTextSent(verifyRoot)
                        if (isSent) {
                            Log.i(TAG, "WhatsApp send verified: text field cleared")
                            return true
                        }
                    }
                    return true
                }
            }
            delay(200)
        }
        return false
    }

    private fun tryClickSend(root: AccessibilityNodeInfo, packages: Set<String>): Boolean {
        val pkg = root.packageName?.toString() ?: return false
        if (packages.isNotEmpty() && pkg !in packages) return false

        // 1. Find by known View IDs
        for (id in SEND_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (r.width() > 10 && r.height() > 10) {
                    tap(r.centerX(), r.centerY())
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
            }
        }

        // 2. Find by Content Descriptions (English 'Send', Hindi 'भेजें', Hinglish 'bhejo')
        val byDesc = findByContentDesc(root, SEND_DESCRIPTIONS)
        if (byDesc != null) {
            val r = Rect()
            byDesc.getBoundsInScreen(r)
            if (r.width() > 10 && r.height() > 10) {
                tap(r.centerX(), r.centerY())
                byDesc.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }

        // 3. Fallback Heuristic: Bottom-Right Action Button in WhatsApp
        val bounds = getScreenMetrics()
        val screenW = bounds.first
        val screenH = bounds.second
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectClickable(root, candidates)
        val sendCandidate = candidates.firstOrNull { node ->
            val r = Rect()
            node.getBoundsInScreen(r)
            // Bottom 25% of screen, Right 25% of screen, reasonable button size (30-100dp)
            r.bottom > screenH * 0.75 && r.right > screenW * 0.70 &&
                r.width() in 50..260 && r.height() in 50..260
        }
        if (sendCandidate != null) {
            val r = Rect()
            sendCandidate.getBoundsInScreen(r)
            tap(r.centerX(), r.centerY())
            sendCandidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }

        // 4. Fallback: Trigger IME action on focused text entry
        return clickImeSend(root)
    }

    private fun isWhatsAppTextSent(root: AccessibilityNodeInfo): Boolean {
        for (id in ENTRY_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                val text = node.text?.toString().orEmpty()
                if (text.isBlank()) return true
            }
        }
        return false
    }

    private fun clickImeSend(root: AccessibilityNodeInfo): Boolean {
        val focused = findFocusedEditable(root) ?: return false
        return if (Build.VERSION.SDK_INT >= 30) {
            focused.performAction(0x00200000) // ACTION_IME_ENTER
        } else {
            focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    // ==========================================
    // 2. YOUTUBE AUTOMATION
    // ==========================================

    /**
     * Taps the N-th video in the YouTube search results.
     * index 0 = first video ("pehla video"), index 1 = second video ("second video").
     */
    suspend fun clickNthVideo(index: Int = 0, timeoutMs: Long = 6_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = rootInActiveWindow
            if (root != null) {
                val pkg = root.packageName?.toString() ?: ""
                if (pkg in YOUTUBE_PACKAGES || pkg.contains("youtube")) {
                    val clicked = tryClickNthVideoNode(root, index)
                    if (clicked) return true
                }
            }
            delay(250)
        }
        return false
    }

    private fun tryClickNthVideoNode(root: AccessibilityNodeInfo, index: Int): Boolean {
        val videoIds = listOf(
            "com.google.android.youtube:id/thumbnail",
            "com.google.android.youtube:id/results",
            "com.google.android.youtube:id/video_item_container",
            "com.google.android.youtube:id/title"
        )
        for (id in videoIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            val filtered = nodes.filter { node ->
                val r = Rect()
                node.getBoundsInScreen(r)
                r.height() > 80 && r.width() > 150 && r.top > 160
            }.sortedBy { node ->
                val r = Rect()
                node.getBoundsInScreen(r)
                r.top
            }
            if (filtered.size > index) {
                val target = filtered[index]
                val r = Rect()
                target.getBoundsInScreen(r)
                return tap(r.centerX(), r.centerY()) || clickNode(target)
            }
        }

        // Generic result item matching
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectClickable(root, candidates)
        val visible = candidates.filter { node ->
            val r = Rect()
            node.getBoundsInScreen(r)
            r.height() > 100 && r.width() > 200 && r.top > 180
        }.sortedBy { node ->
            val r = Rect()
            node.getBoundsInScreen(r)
            r.top
        }
        if (visible.size > index) {
            val target = visible[index]
            val r = Rect()
            target.getBoundsInScreen(r)
            return tap(r.centerX(), r.centerY()) || clickNode(target)
        }
        return false
    }

    /** Simulates double tap on right half (forward 10s) or left half (rewind 10s) on YouTube. */
    fun seekYouTubeGesture(forward: Boolean, doubleTapCount: Int = 1): Boolean {
        val (screenW, screenH) = getScreenMetrics()
        val y = screenH * 0.30f
        val x = if (forward) screenW * 0.78f else screenW * 0.22f
        var success = true
        for (i in 0 until doubleTapCount) {
            success = success && doubleTap(x, y)
            Thread.sleep(120)
        }
        return success
    }

    suspend fun seekYouTubeTime(seconds: Int, forward: Boolean): Boolean {
        val tapCount = (seconds / 10).coerceIn(1, 35)
        val (screenW, screenH) = getScreenMetrics()
        val y = screenH * 0.30f
        val x = if (forward) screenW * 0.78f else screenW * 0.22f
        for (i in 0 until tapCount) {
            doubleTap(x, y)
            delay(90)
        }
        return true
    }

    suspend fun configureYouTubePlayerSetting(settingType: String, targetOption: String): Boolean {
        val (sw, sh) = getScreenMetrics()
        // 1. Tap video player center to reveal controls
        tap(sw * 0.5f, sh * 0.28f)
        delay(400)

        val root = rootInActiveWindow ?: return false
        var openedSettings = false
        val settingsIds = listOf(
            "com.google.android.youtube:id/player_settings_button",
            "player_settings_button",
            "overflow_menu"
        )
        for (id in settingsIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            val first = nodes.firstOrNull()
            if (first != null) {
                val r = Rect()
                first.getBoundsInScreen(r)
                openedSettings = tap(r.centerX(), r.centerY()) || clickNode(first)
                if (openedSettings) break
            }
        }
        if (!openedSettings) {
            val candidates = mutableListOf<AccessibilityNodeInfo>()
            collectClickable(root, candidates)
            val settingsBtn = candidates.firstOrNull {
                val desc = it.contentDescription?.toString()?.lowercase() ?: ""
                desc.contains("settings") || desc.contains("setting") || desc.contains("more options")
            }
            if (settingsBtn != null) {
                val r = Rect()
                settingsBtn.getBoundsInScreen(r)
                openedSettings = tap(r.centerX(), r.centerY()) || clickNode(settingsBtn)
            }
        }
        if (!openedSettings) {
            // Tap top right gear area
            tap(sw * 0.92f, sh * 0.12f)
            openedSettings = true
        }

        delay(500)
        val menuRoot = rootInActiveWindow ?: return false
        val isSpeed = settingType.contains("speed", ignoreCase = true)
        val targetMenuWord = if (isSpeed) "speed" else "quality"
        val menuCandidates = mutableListOf<AccessibilityNodeInfo>()
        collectClickable(menuRoot, menuCandidates)
        val menuNode = menuCandidates.firstOrNull {
            val text = (it.text?.toString() ?: "") + " " + (it.contentDescription?.toString() ?: "")
            text.contains(targetMenuWord, ignoreCase = true) ||
                (isSpeed && text.contains("स्पीड", ignoreCase = true)) ||
                (!isSpeed && text.contains("क्वालिटी", ignoreCase = true))
        }

        if (menuNode != null) {
            val r = Rect()
            menuNode.getBoundsInScreen(r)
            tap(r.centerX(), r.centerY()) || clickNode(menuNode)
            delay(500)
        }

        val optionRoot = rootInActiveWindow ?: return false
        val optionCandidates = mutableListOf<AccessibilityNodeInfo>()
        collectClickable(optionRoot, optionCandidates)
        val optionNode = optionCandidates.firstOrNull {
            val text = (it.text?.toString() ?: "") + " " + (it.contentDescription?.toString() ?: "")
            text.contains(targetOption, ignoreCase = true)
        }
        if (optionNode != null) {
            val r = Rect()
            optionNode.getBoundsInScreen(r)
            return tap(r.centerX(), r.centerY()) || clickNode(optionNode)
        }
        return true
    }

    suspend fun clickCameraShutter(timeoutMs: Long = 4_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val shutterIds = listOf(
            "com.android.camera:id/shutter_button",
            "com.google.android.GoogleCamera:id/shutter_button",
            "com.sec.android.app.camera:id/shutter_button",
            "com.oppo.camera:id/shutter_button",
            "shutter_button",
            "shutter",
            "take_picture_button",
            "camera_shutter"
        )
        val shutterKeywords = listOf("shutter", "take picture", "take photo", "capture", "click photo", "फोटो", "फ़ोटो लें")

        while (System.currentTimeMillis() < deadline) {
            val root = rootInActiveWindow
            if (root != null) {
                for (id in shutterIds) {
                    val nodes = root.findAccessibilityNodeInfosByViewId(id)
                    for (n in nodes) {
                        val r = Rect()
                        n.getBoundsInScreen(r)
                        if (r.width() > 20 && r.height() > 20) {
                            return tap(r.centerX(), r.centerY()) || clickNode(n)
                        }
                    }
                }
                for (kw in shutterKeywords) {
                    val nodes = root.findAccessibilityNodeInfosByText(kw)
                    for (n in nodes) {
                        val r = Rect()
                        n.getBoundsInScreen(r)
                        if (r.width() > 20 && r.height() > 20) {
                            return tap(r.centerX(), r.centerY()) || clickNode(n)
                        }
                    }
                }
            }
            delay(200)
        }
        // Fallback: tap bottom-center shutter button area
        val (sw, sh) = getScreenMetrics()
        return tap(sw * 0.5f, sh * 0.88f)
    }

    suspend fun switchCameraLens(timeoutMs: Long = 4_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val switchKeywords = listOf("switch camera", "flip camera", "front camera", "rear camera", "कैमरा बदलें", "switch to front", "switch to back")
        while (System.currentTimeMillis() < deadline) {
            val root = rootInActiveWindow
            if (root != null) {
                for (kw in switchKeywords) {
                    val nodes = root.findAccessibilityNodeInfosByText(kw)
                    for (n in nodes) {
                        val r = Rect()
                        n.getBoundsInScreen(r)
                        if (r.width() > 10 && r.height() > 10) {
                            return tap(r.centerX(), r.centerY()) || clickNode(n)
                        }
                    }
                }
            }
            delay(200)
        }
        val (sw, sh) = getScreenMetrics()
        return tap(sw * 0.82f, sh * 0.88f) || tap(sw * 0.18f, sh * 0.88f)
    }

    suspend fun recordCameraVideoDuration(seconds: Int): Boolean {
        val started = clickCameraShutter()
        if (!started) return false
        val duration = seconds.coerceIn(1, 300)
        delay(duration * 1000L)
        return clickCameraShutter()
    }

    fun doubleTap(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val path1 = Path().apply { moveTo(x, y) }
        val stroke1 = GestureDescription.StrokeDescription(path1, 0, 50)
        val stroke2 = GestureDescription.StrokeDescription(path1, 100, 50)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke1)
            .addStroke(stroke2)
            .build()
        return dispatchGesture(gesture, null, null)
    }

    // ==========================================
    // 3. CHROME & GOOGLE SEARCH AUTOMATION
    // ==========================================

    suspend fun clickFirstResult(packages: Set<String>, timeoutMs: Long = 6_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = rootInActiveWindow
            if (root != null) {
                if (tryClickFirstResult(root, packages)) return true
            }
            delay(250)
        }
        return false
    }

    private fun tryClickFirstResult(root: AccessibilityNodeInfo, packages: Set<String>): Boolean {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectClickable(root, candidates)
        val visible = candidates.filter { node ->
            val r = Rect()
            node.getBoundsInScreen(r)
            r.height() > 60 && r.width() > 180 && r.top > 200
        }.sortedBy { node ->
            val r = Rect()
            node.getBoundsInScreen(r)
            r.top
        }
        val first = visible.firstOrNull() ?: return false
        val r = Rect()
        first.getBoundsInScreen(r)
        return tap(r.centerX(), r.centerY()) || clickNode(first)
    }

    suspend fun clickVisibleText(text: String, timeoutMs: Long = 4_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = rootInActiveWindow
            if (root != null && clickByText(root, text)) return true
            delay(200)
        }
        return false
    }

    fun scroll(down: Boolean): Boolean = scrollDirection(if (down) "down" else "up")

    fun scrollDirection(direction: String): Boolean {
        val dir = direction.lowercase().trim()
        val root = rootInActiveWindow
        val (w, h) = getScreenMetrics()

        when (dir) {
            "down", "neeche" -> {
                val scrollable = root?.let { findFirstScrollable(it) }
                if (scrollable != null && scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
                return swipe(w * 0.5f, h * 0.70f, w * 0.5f, h * 0.25f, 250)
            }
            "up", "upar" -> {
                val scrollable = root?.let { findFirstScrollable(it) }
                if (scrollable != null && scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true
                return swipe(w * 0.5f, h * 0.30f, w * 0.5f, h * 0.75f, 250)
            }
            "left", "bayein" -> {
                return swipe(w * 0.80f, h * 0.5f, w * 0.15f, h * 0.5f, 250)
            }
            "right", "dayein" -> {
                return swipe(w * 0.20f, h * 0.5f, w * 0.85f, h * 0.5f, 250)
            }
            else -> return swipe(w * 0.5f, h * 0.70f, w * 0.5f, h * 0.25f, 250)
        }
    }

    suspend fun answerCallViaAccessibility(): Boolean {
        val root = rootInActiveWindow ?: return false
        val answerLabels = listOf("answer", "accept", "receive", "call", "उत्तर दें", "उठाएं", "incoming", "swipe up to answer")
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectClickable(root, candidates)
        val match = candidates.firstOrNull { node ->
            val text = (node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")
            answerLabels.any { text.contains(it, ignoreCase = true) }
        }
        if (match != null && clickNode(match)) return true

        // Fallback: swipe up from bottom-center for full-screen incoming call sliders
        val (w, h) = getScreenMetrics()
        return swipe(w * 0.5f, h * 0.85f, w * 0.5f, h * 0.40f, 300)
    }

    suspend fun endCallViaAccessibility(): Boolean {
        val root = rootInActiveWindow ?: return false
        val endLabels = listOf("end", "disconnect", "hang up", "decline", "reject", "dismiss", "कॉल काटें", "समाप्त")
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectClickable(root, candidates)
        val match = candidates.firstOrNull { node ->
            val text = (node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")
            endLabels.any { text.contains(it, ignoreCase = true) }
        }
        if (match != null && clickNode(match)) return true

        // Fallback: swipe down or tap end call area (center bottom red button)
        val (w, h) = getScreenMetrics()
        return tap(w * 0.5f, h * 0.80f)
    }

    suspend fun triggerWhatsAppCall(video: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val callLabels = if (video) listOf("video call", "वीडियो कॉल") else listOf("voice call", "audio call", "कॉल करें")
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectClickable(root, candidates)
        val match = candidates.firstOrNull { node ->
            val desc = node.contentDescription?.toString() ?: ""
            callLabels.any { desc.contains(it, ignoreCase = true) }
        }
        if (match != null && clickNode(match)) {
            // Confirm popup if WhatsApp displays "Start voice call?" dialog
            delay(500)
            val confirmRoot = rootInActiveWindow
            if (confirmRoot != null) {
                clickByText(confirmRoot, "call")
            }
            return true
        }
        return false
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    private fun findFirstScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(node)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (n.isScrollable) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    fun visibleText(maxChars: Int = 3000): String {
        val sb = StringBuilder()
        val allWindows = try { windows } catch (_: Exception) { null }
        if (!allWindows.isNullOrEmpty()) {
            for (window in allWindows) {
                val root = window.root ?: continue
                collectText(root, sb, maxChars)
                if (sb.length >= maxChars) break
            }
        }
        if (sb.isEmpty()) {
            val root = rootInActiveWindow
            if (root != null) {
                collectText(root, sb, maxChars)
            }
        }
        return sb.toString().trim()
    }

    // ==========================================
    // 4. LOW-LEVEL GESTURES & HELPERS
    // ==========================================

    fun tap(x: Int, y: Int): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun tap(x: Float, y: Float): Boolean = tap(x.toInt(), y.toInt())

    private fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < 6) {
            val r = Rect()
            current.getBoundsInScreen(r)
            if (current.isClickable) {
                if (r.width() > 4 && r.height() > 4) {
                    tap(r.centerX(), r.centerY())
                }
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK) || true
            }
            current = current.parent
            hops++
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.width() > 4 && r.height() > 4) {
            return tap(r.centerX(), r.centerY())
        }
        return false
    }

    private fun clickByText(root: AccessibilityNodeInfo, text: String): Boolean {
        val q = text.trim().lowercase()
        if (q.isEmpty()) return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (n in nodes) if (clickNode(n)) return true
        val all = mutableListOf<AccessibilityNodeInfo>()
        collectClickable(root, all)
        val match = all.firstOrNull { node ->
            val t = (node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")
            t.lowercase().contains(q)
        } ?: return false
        return clickNode(match)
    }

    private fun findByContentDesc(root: AccessibilityNodeInfo, needles: List<String>): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            val desc = n.contentDescription?.toString()?.lowercase().orEmpty()
            if (needles.any { desc.contains(it) }) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun findFocusedEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (n.isFocused && n.isEditable) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun collectClickable(root: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (n.isClickable || n.isLongClickable) out.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
    }

    private fun collectText(node: AccessibilityNodeInfo, sb: StringBuilder, max: Int) {
        if (sb.length >= max) return
        val t = node.text?.toString()?.trim()
        if (!t.isNullOrEmpty()) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(t)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, sb, max)
        }
    }

    private fun getScreenMetrics(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm?.defaultDisplay?.getRealMetrics(metrics)
        val w = if (metrics.widthPixels > 0) metrics.widthPixels else 1080
        val h = if (metrics.heightPixels > 0) metrics.heightPixels else 2400
        return w to h
    }

    private fun drainPending() {
        if (pending.isEmpty()) return
        val root = rootInActiveWindow ?: return
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val task = iterator.next()
            val done = when (task) {
                is UiTask.ClickSend -> tryClickSend(root, task.packages).also { if (it) task.result.complete(true) }
                is UiTask.ClickFirstMatch -> tryClickFirstResult(root, task.packages).also { if (it) task.result.complete(true) }
                is UiTask.ClickNthVideo -> tryClickNthVideoNode(root, task.index).also { if (it) task.result.complete(true) }
                is UiTask.ClickText -> clickByText(root, task.text).also { if (it) task.result.complete(true) }
                is UiTask.SetText -> false
            }
            if (done) pending.remove(task)
        }
    }
}
