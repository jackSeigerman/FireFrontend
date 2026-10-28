package dev.firefrontend

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.GridView

/**
 * A GridView whose rows fade out completely at its top edge once they're scrolled past it, so they
 * slide away under the hero instead of being cut off. Drawn as an alpha mask rather than the stock
 * fading edge, which only fades partway when a row has barely scrolled.
 */
class FadingGridView(context: Context, attrs: AttributeSet?) : GridView(context, attrs) {

    private val fadeLength = FADE_DP * resources.displayMetrics.density
    private val mask = Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, fadeLength, 0x00000000, 0xFF000000.toInt(), Shader.TileMode.CLAMP)
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val scrolled = childCount > 0 && (firstVisiblePosition > 0 || getChildAt(0).top < listPaddingTop)
        if (!scrolled) {
            super.dispatchDraw(canvas)
            return
        }
        // Tiles may grow past the sides and bottom, so the layer covers that overflow too.
        val overflow = fadeLength
        @Suppress("DEPRECATION")
        val layer = canvas.saveLayer(-overflow, 0f, width + overflow, height + overflow, null, Canvas.ALL_SAVE_FLAG)
        super.dispatchDraw(canvas)
        canvas.drawRect(-overflow, 0f, width + overflow, fadeLength, mask)
        canvas.restoreToCount(layer)
    }

    private companion object {
        const val FADE_DP = 72f
    }
}
