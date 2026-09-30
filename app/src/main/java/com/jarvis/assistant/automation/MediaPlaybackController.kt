package com.jarvis.assistant.automation

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.view.KeyEvent
import com.jarvis.assistant.util.CommandResult

object MediaPlaybackController {

    fun activeController(context: Context): MediaController? {
        if (!PermissionCenter.isNotificationListenerEnabled(context)) return null
        return try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val cn = ComponentName(context, NotificationReaderService::class.java)
            msm.getActiveSessions(cn).firstOrNull { it.playbackState != null }
        } catch (_: Exception) {
            null
        }
    }

    fun isPlaying(context: Context): Boolean {
        val state = activeController(context)?.playbackState?.state
        return state == PlaybackState.STATE_PLAYING
    }

    fun playPause(context: Context): CommandResult {
        val c = activeController(context)
        if (c != null) {
            val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
            if (playing) c.transportControls.pause() else c.transportControls.play()
            return CommandResult(true, if (playing) "Paused." else "Playing.")
        }
        // YouTube on-screen fallback
        val current = ForegroundVerifier.currentPackage(context)
        if (current == "com.google.android.youtube") {
            val a11y = LunaAccessibilityService.instance
            if (a11y != null) {
                val ok = a11y.doubleTap(540f, 600f) || a11y.tap(540, 600)
                if (ok) return CommandResult(true, "YouTube video toggled.")
            }
        }
        return if (dispatch(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
            CommandResult(true, "Media toggled.")
        } else {
            CommandResult(false, "No active media playing right now.")
        }
    }

    fun play(context: Context): CommandResult {
        val c = activeController(context)
        if (c != null) {
            c.transportControls.play()
            return CommandResult(true, "Playing.")
        }
        dispatch(context, KeyEvent.KEYCODE_MEDIA_PLAY)
        return CommandResult(true, "Play command bhej diya.")
    }

    fun pause(context: Context): CommandResult {
        val c = activeController(context)
        if (c != null) {
            c.transportControls.pause()
            return CommandResult(true, "Paused.")
        }
        dispatch(context, KeyEvent.KEYCODE_MEDIA_PAUSE)
        return CommandResult(true, "Media pause kar diya.")
    }

    fun next(context: Context): CommandResult {
        val c = activeController(context)
        if (c != null) {
            c.transportControls.skipToNext()
            return CommandResult(true, "Next track play ho raha hai.")
        }
        dispatch(context, KeyEvent.KEYCODE_MEDIA_NEXT)
        return CommandResult(true, "Next command bhej diya.")
    }

    fun previous(context: Context): CommandResult {
        val c = activeController(context)
        if (c != null) {
            c.transportControls.skipToPrevious()
            return CommandResult(true, "Previous track play ho raha hai.")
        }
        dispatch(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        return CommandResult(true, "Previous command bhej diya.")
    }

    fun stop(context: Context): CommandResult {
        val c = activeController(context)
        if (c != null) {
            c.transportControls.stop()
            return CommandResult(true, "Media stopped.")
        }
        dispatch(context, KeyEvent.KEYCODE_MEDIA_STOP)
        return CommandResult(true, "Playback stop kar diya.")
    }

    fun seek(context: Context, deltaMs: Long): CommandResult {
        val sec = kotlin.math.abs(deltaMs) / 1000
        val dir = if (deltaMs >= 0) "aage" else "peeche"

        // 1. Try MediaSession seekTo
        val c = activeController(context)
        if (c != null) {
            val pos = c.playbackState?.position ?: 0L
            val target = (pos + deltaMs).coerceAtLeast(0)
            c.transportControls.seekTo(target)
            return CommandResult(true, "$sec seconds $dir skip kar diya.")
        }

        // 2. YouTube on-screen gesture fallback
        val current = ForegroundVerifier.currentPackage(context)
        if (current == "com.google.android.youtube") {
            val a11y = LunaAccessibilityService.instance
            if (a11y != null) {
                val taps = ((kotlin.math.abs(deltaMs) / 10_000L).toInt()).coerceIn(1, 30)
                a11y.seekYouTubeGesture(forward = deltaMs >= 0, doubleTapCount = taps)
                return CommandResult(true, "YouTube video $sec seconds $dir skip kar diya.")
            }
        }

        // 3. Fallback: Repeated Media Key Fast-Forward / Rewind
        val steps = ((kotlin.math.abs(deltaMs) / 10_000L).toInt()).coerceIn(1, 30)
        val key = if (deltaMs >= 0) KeyEvent.KEYCODE_MEDIA_FAST_FORWARD else KeyEvent.KEYCODE_MEDIA_REWIND
        repeat(steps) { dispatch(context, key) }
        return CommandResult(true, "$sec seconds $dir seek command bhej diya.")
    }

    private fun dispatch(context: Context, keyCode: Int): Boolean {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            true
        } catch (_: Exception) {
            false
        }
    }
}
