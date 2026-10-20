package dev.firefrontend

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * A "liquid glass" panel: translucent frosted fill, a bright specular rim that fades toward the
 * bottom, and a soft sheen across the top half. Whatever is drawn behind it tints through.
 *
 * Real background blur needs API 31; on Fire OS the backdrop behind is already blurred, so a
 * translucent layer over it reads as frosted glass for a fraction of the cost.
 */
class GlassDrawable(private val density: Float, private val radiusDp: Float = 24f) : Drawable() {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val rect = RectF()
    private val clip = Path()

    override fun onBoundsChange(bounds: Rect) {
        val inset = rim.strokeWidth / 2
        rect.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset)
        val top = rect.top
        val bottom = rect.bottom
        fill.shader = LinearGradient(0f, top, 0f, bottom, 0x40FFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP)
        sheen.shader = LinearGradient(
            0f, top, 0f, top + rect.height() * 0.5f, 0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP,
        )
        rim.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(0xD9FFFFFF.toInt(), 0x26FFFFFF, 0x59FFFFFF),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP,
        )
        clip.reset()
        val r = radiusDp * density
        clip.addRoundRect(rect, r, r, Path.Direction.CW)
    }

    override fun draw(canvas: Canvas) {
        val r = radiusDp * density
        canvas.drawRoundRect(rect, r, r, fill)
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawRect(rect.left, rect.top, rect.right, rect.top + rect.height() * 0.5f, sheen)
        canvas.restore()
        canvas.drawRoundRect(rect, r, r, rim)
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        sheen.alpha = alpha
        rim.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
