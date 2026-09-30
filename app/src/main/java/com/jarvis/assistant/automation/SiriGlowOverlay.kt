package com.jarvis.assistant.automation

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager

/**
 * Manages the Siri-style animated orange glow border overlay that appears
 * automatically around the phone screen whenever Luna (AI) is speaking.
 *
 * This is separate from [ScreenOverlayController] which manages screen visualization mode.
 * This overlay is always-on-top (TYPE_APPLICATION_OVERLAY) and purely decorative
 * (FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE).
 */
class SiriGlowOverlay(private val context: Context) {

    private val wm = context.applicationContext
        .getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var glowView: SiriGlowBorderView? = null

    @Volatile
    var isShowing: Boolean = false
        private set

    fun show() {
        if (!Settings.canDrawOverlays(context)) return
        handler.post {
            if (isShowing) return@post
            isShowing = true
            addGlowView()
        }
    }

    fun hide() {
        handler.post {
            if (!isShowing) return@post
            isShowing = false
            val v = glowView ?: return@post
            glowView = null
            try { wm.removeView(v) } catch (_: Exception) {}
        }
    }

    private fun addGlowView() {
        if (glowView != null) return
        val view = SiriGlowBorderView(context)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        try {
            wm.addView(view, params)
            glowView = view
        } catch (e: Exception) {
            isShowing = false
        }
    }

    fun release() {
        hide()
    }
}
