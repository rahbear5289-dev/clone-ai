package com.jarvis.assistant.automation

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator

/**
 * Renders an Apple Siri / iOS 18 style animated neon orange perimeter border
 * around the entire display bezel with:
 *  - Smooth sweeping gradient animation (light chases around the edge)
 *  - Multi-layer glow for vibrant neon look
 *  - Pulsing brightness synchronized with AI speaking state
 */
class SiriGlowBorderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Orange palette ────────────────────────────────────────────────
    private val colorBright    = Color.parseColor("#FF9E00")  // bright amber-orange
    private val colorBase      = Color.parseColor("#FF6A00")  // vivid orange
    private val colorDeep      = Color.parseColor("#E64A00")  // deep orange-red
    private val colorWhiteHot  = Color.parseColor("#FFEECC")  // hot white highlight
    private val colorTransp    = Color.TRANSPARENT

    // ── Paints ────────────────────────────────────────────────────────
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val rectF = RectF()
    private val density = resources.displayMetrics.density
    private val cornerRadius = 52f * density

    // ── Animation state ───────────────────────────────────────────────
    /** 0f → 360f sweep angle for the chasing highlight */
    private var sweepAngle = 0f
    /** 0.85f → 1.15f pulse multiplier for overall brightness */
    private var pulseFactor = 1.0f

    private var sweepAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null

    // Sweep gradient matrix (rotated each frame)
    private val gradMatrix = Matrix()

    init {
        startAnimations()
    }

    private fun startAnimations() {
        // 1. Chasing sweep: 0°→360° in 2 seconds, infinite loop
        sweepAnimator?.cancel()
        sweepAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                sweepAngle = va.animatedValue as Float
                invalidate()
            }
            start()
        }

        // 2. Pulse glow: 0.80f → 1.20f, 1.4 s cycle
        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(0.80f, 1.20f).apply {
            duration = 1400
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { va ->
                pulseFactor = va.animatedValue as Float
                // invalidate already done by sweep animator
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        sweepAnimator?.cancel()
        pulseAnimator?.cancel()
        sweepAnimator = null
        pulseAnimator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val cx = w / 2f
        val cy = h / 2f

        // ── Layers: outermost (widest/most diffuse) → innermost (sharpest) ──
        // Each layer: (strokeWidthDp, alphaFraction, colorHex)
        val layers = listOf(
            Triple(22f * density * pulseFactor, 0.12f, colorDeep),
            Triple(16f * density * pulseFactor, 0.22f, colorDeep),
            Triple(10f * density * pulseFactor, 0.45f, colorBase),
            Triple( 6f * density * pulseFactor, 0.70f, colorBase),
            Triple( 3f * density,               0.90f, colorBright),
            Triple( 1.5f * density,             1.00f, colorWhiteHot)
        )

        // Draw static base glow layers (solid-color, pulsing)
        for ((strokeW, alphaRatio, color) in layers) {
            val inset = strokeW / 2f + density
            rectF.set(inset, inset, w - inset, h - inset)
            val rad = (cornerRadius - inset).coerceAtLeast(8f * density)
            glowPaint.shader = null
            glowPaint.color = color
            glowPaint.strokeWidth = strokeW
            glowPaint.alpha = (255 * alphaRatio * (0.75f + 0.25f * pulseFactor))
                .toInt().coerceIn(0, 255)
            canvas.drawRoundRect(rectF, rad, rad, glowPaint)
        }

        // ── Sweeping highlight (SweepGradient rotated by sweepAngle) ──
        // Creates the "chasing light" Siri-style effect
        val highlightStroke = 6f * density * pulseFactor
        val inset = highlightStroke / 2f + density
        rectF.set(inset, inset, w - inset, h - inset)
        val rad = (cornerRadius - inset).coerceAtLeast(8f * density)

        // SweepGradient: bright spot → orange → transparent → transparent → back
        val sweepGrad = SweepGradient(
            cx, cy,
            intArrayOf(
                colorTransp,
                colorTransp,
                Color.argb(180, 0xFF, 0xEE, 0xCC),  // white-hot centre of sweep
                colorBright,
                colorBase,
                colorTransp,
                colorTransp
            ),
            floatArrayOf(0f, 0.30f, 0.45f, 0.52f, 0.60f, 0.75f, 1.0f)
        )

        gradMatrix.reset()
        gradMatrix.postRotate(sweepAngle, cx, cy)
        sweepGrad.setLocalMatrix(gradMatrix)

        glowPaint.shader = sweepGrad
        glowPaint.strokeWidth = highlightStroke
        glowPaint.alpha = (255 * (0.80f + 0.20f * pulseFactor)).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(rectF, rad, rad, glowPaint)

        glowPaint.shader = null
    }
}
