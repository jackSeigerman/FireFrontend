package dev.firefrontend

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * A loading skeleton of FireFrontend's home screen, shown over Amazon's home while Fire OS holds the
 * switch back. Same layout as the real screen (buttons, clock, date, weather card, app rows), with a
 * soft highlight sweeping across. Drawn rather than inflated so it appears in a single frame.
 */
class HomeSkeletonView(context: Context) : View(context) {

    private val background = Paint()
    private val bone = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14FFFFFF }
    private val shimmer = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shimmerMatrix = Matrix()
    private val rect = RectF()
    private var sweep = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = SWEEP_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            sweep = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        background.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF1A1D26.toInt(), 0xFF0F1115.toInt(), Shader.TileMode.CLAMP)
        // A narrow diagonal band of light; moved across the screen with the shader's matrix.
        val band = w * 0.25f
        shimmer.shader = LinearGradient(
            0f, 0f, band, band * 0.35f,
            intArrayOf(0x00FFFFFF, 0x1AFFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP,
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)
        val band = width * 0.25f
        shimmerMatrix.setTranslate(-band + sweep * (width + band * 2), 0f)
        shimmer.shader?.setLocalMatrix(shimmerMatrix)
        for (b in BONES) {
            // Laid out on a 1920×1080 design grid, like the real home screen.
            rect.set(b[0] * width / 1920f, b[1] * height / 1080f, b[2] * width / 1920f, b[3] * height / 1080f)
            val r = b[4] * width / 1920f
            canvas.drawRoundRect(rect, r, r, bone)
            canvas.drawRoundRect(rect, r, r, shimmer)
        }
    }

    private companion object {
        const val SWEEP_MS = 1_400L

        /** left, top, right, bottom, corner radius — on a 1920×1080 grid. */
        val BONES: List<FloatArray> = buildList {
            add(floatArrayOf(1368f, 48f, 1550f, 120f, 36f)) // Settings
            add(floatArrayOf(1575f, 48f, 1823f, 120f, 36f)) // Manage apps
            add(floatArrayOf(100f, 165f, 440f, 300f, 28f)) // Clock
            add(floatArrayOf(104f, 352f, 454f, 392f, 20f)) // Date
            add(floatArrayOf(96f, 432f, 898f, 628f, 48f)) // Weather card
            for (row in 0..1) {
                val top = 692f + row * 266f
                for (col in 0..4) {
                    val left = 108f + col * 348f
                    add(floatArrayOf(left, top, left + 308f, top + 174f, 16f)) // Tile
                    add(floatArrayOf(left + 94f, top + 196f, left + 214f, top + 216f, 10f)) // Label
                }
            }
        }
    }
}
