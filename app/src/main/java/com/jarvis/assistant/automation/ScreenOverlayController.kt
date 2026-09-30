package com.jarvis.assistant.automation

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import com.jarvis.assistant.R

/**
 * Manages the animated center popup overlay ("SCREEN VISUALIZATION MODE")
 * and the 6dp orange border indicator around the screen during live screen analysis.
 */
class ScreenOverlayController(private val context: Context) {

    private val wm = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var borderView: View? = null
    private var popupView: View? = null
    @Volatile var showing: Boolean = false
        private set

    fun showVisualization() {
        if (!Settings.canDrawOverlays(context)) return
        handler.post {
            if (showing) {
                updateStatus(true)
                return@post
            }
            showing = true
            addBorder()
            addPopupAndAnimate()
            DeviceStateStore.update { it.copy(screenAccess = true, screenVisualization = true) }
        }
    }

    fun hideVisualization() {
        handler.post {
            showing = false
            updateStatus(false)
            popupView?.let { safelyRemove(it) }
            popupView = null
            val bv = borderView
            if (bv != null) {
                ObjectAnimator.ofFloat(bv, View.ALPHA, bv.alpha, 0f).apply {
                    duration = 400
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            safelyRemove(bv)
                            if (borderView === bv) borderView = null
                        }
                    })
                    start()
                }
            }
            DeviceStateStore.update { it.copy(screenAccess = false, screenVisualization = false) }
        }
    }

    private fun addBorder() {
        if (borderView != null) return
        val view = LayoutInflater.from(context).inflate(R.layout.overlay_screen_border, null)
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
            borderView = view
            view.alpha = 0f
            ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f).setDuration(280).start()
            updateStatus(true)
        } catch (_: Exception) {
            showing = false
        }
    }

    private fun addPopupAndAnimate() {
        val view = LayoutInflater.from(context).inflate(R.layout.overlay_screen_popup, null)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.CENTER
        try {
            wm.addView(view, params)
            popupView = view
            view.scaleX = 0.4f
            view.scaleY = 0.4f
            view.alpha = 0f
            val sx = ObjectAnimator.ofFloat(view, View.SCALE_X, 0.4f, 1f)
            val sy = ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.4f, 1f)
            val a = ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f)
            AnimatorSet().apply {
                playTogether(sx, sy, a)
                duration = 320
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
            handler.postDelayed({
                val ty = ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f, -420f)
                val fade = ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0f)
                AnimatorSet().apply {
                    playTogether(ty, fade)
                    duration = 420
                    interpolator = AccelerateDecelerateInterpolator()
                    start()
                }
                handler.postDelayed({
                    popupView?.let { safelyRemove(it) }
                    popupView = null
                }, 450)
            }, 850)
        } catch (_: Exception) {
        }
    }

    private fun updateStatus(on: Boolean) {
        val tv = borderView?.findViewById<TextView>(R.id.tvScreenVizStatus) ?: return
        tv.text = if (on) "SCREEN VISUALIZATION MODE ON" else "SCREEN VISUALIZATION MODE OFF"
        tv.setBackgroundResource(if (on) R.drawable.bg_pill_badge_green else R.drawable.bg_pill_badge_red)
    }

    private fun safelyRemove(view: View) {
        try { wm.removeView(view) } catch (_: Exception) {}
    }
}
