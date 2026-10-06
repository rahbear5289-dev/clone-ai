package com.jarvis.assistant.ui.research

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

class CyberProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#102030")
        style = Paint.Style.FILL
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var offsetFraction = 0f
    private var animator: ValueAnimator? = null

    init {
        startAnimation()
    }

    private fun startAnimation() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                offsetFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animator?.isStarted != true) {
            startAnimation()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val radius = h / 2f
        // Draw track
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, bgPaint)

        // Draw animated sweeping cyan/blue laser gradient
        val barWidth = w * 0.45f
        val startX = (w + barWidth) * offsetFraction - barWidth
        val endX = startX + barWidth

        glowPaint.shader = LinearGradient(
            startX, 0f, endX, 0f,
            intArrayOf(
                Color.TRANSPARENT,
                Color.parseColor("#00E5FF"),
                Color.parseColor("#18FFFF"),
                Color.parseColor("#00B0FF"),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.3f, 0.5f, 0.8f, 1f),
            Shader.TileMode.CLAMP
        )

        canvas.drawRoundRect(
            startX.coerceAtLeast(0f),
            0f,
            endX.coerceAtMost(w),
            h,
            radius,
            radius,
            glowPaint
        )
    }
}
