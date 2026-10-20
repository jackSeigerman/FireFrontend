package dev.firefrontend

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * Full-screen backdrop behind the home screen. Shows either an ambient gradient (time of day +
 * weather) or the focused app's blurred banner tinted by its accent color, and crossfades between them.
 */
class HeroBackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private class Layer(val bitmap: Bitmap?, val top: Int, val bottom: Int, val tint: Int) {
        var shader: Shader? = null
        var shaderHeight = -1
    }

    private var current: Layer? = null
    private var previous: Layer? = null
    private var fade = 1f

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint()
    private val scrimPaint = Paint()
    private val sidePaint = Paint()
    private val matrix = Matrix()
    private val baseColor = context.getColor(R.color.bg)

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 450
        addUpdateListener {
            fade = it.animatedValue as Float
            invalidate()
        }
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (fade >= 1f) previous = null
            }
        })
    }

    fun showGradient(top: Int, bottom: Int) {
        val c = current
        if (c != null && c.bitmap == null && c.top == top && c.bottom == bottom) return
        swap(Layer(null, top, bottom, 0))
    }

    fun showImage(bitmap: Bitmap, tint: Int) {
        if (current?.bitmap === bitmap) return
        swap(Layer(bitmap, 0, 0, tint))
    }

    private fun swap(layer: Layer) {
        previous = current
        current = layer
        animator.cancel()
        if (previous == null) {
            fade = 1f
            invalidate()
        } else {
            animator.start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Fade into the plain background where the app grid sits, and darken the left for hero text.
        scrimPaint.shader = LinearGradient(
            0f, h * 0.35f, 0f, h * 0.78f, baseColor and 0x00FFFFFF, baseColor, Shader.TileMode.CLAMP,
        )
        sidePaint.shader = LinearGradient(0f, 0f, w * 0.6f, 0f, 0x8C000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(baseColor)
        previous?.let { drawLayer(canvas, it, 255) }
        current?.let { drawLayer(canvas, it, (255 * fade).toInt()) }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), sidePaint)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
    }

    private fun drawLayer(canvas: Canvas, layer: Layer, alpha: Int) {
        val bmp = layer.bitmap
        if (bmp != null) {
            val scale = max(width / bmp.width.toFloat(), height / bmp.height.toFloat())
            matrix.setScale(scale, scale)
            matrix.postTranslate((width - bmp.width * scale) / 2f, (height - bmp.height * scale) / 2f)
            bitmapPaint.alpha = alpha
            canvas.drawBitmap(bmp, matrix, bitmapPaint)
            // Tint toward the accent and darken a little so white text stays readable.
            fillPaint.shader = null
            fillPaint.color = layer.tint
            fillPaint.alpha = alpha * 150 / 255
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
            fillPaint.color = 0xFF000000.toInt()
            fillPaint.alpha = alpha * 45 / 255
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
        } else {
            if (layer.shaderHeight != height) {
                layer.shader = LinearGradient(0f, 0f, 0f, height * 0.75f, layer.top, layer.bottom, Shader.TileMode.CLAMP)
                layer.shaderHeight = height
            }
            fillPaint.shader = layer.shader
            fillPaint.alpha = alpha
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
            fillPaint.shader = null
        }
    }
}
