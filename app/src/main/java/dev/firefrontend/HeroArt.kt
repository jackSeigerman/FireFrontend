package dev.firefrontend

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import kotlin.math.max

/**
 * Builds the tiny blurred backdrop and accent color for an app from its banner.
 *
 * The backdrop is only 48×27 px: drawn scaled up with bilinear filtering it looks like a heavy blur,
 * and 30 apps cost ~150 KB in total — no RenderScript or API 31 blur needed.
 */
object HeroArt {

    private const val W = 48
    private const val H = 27

    fun backdrop(source: Drawable): Bitmap {
        // Work on a copy: the original is shared with the grid tile, and setBounds would move it there.
        val d = source.constantState?.newDrawable()?.mutate() ?: source
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: W
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: H
        val scale = max(W / iw.toFloat(), H / ih.toFloat())
        val dw = (iw * scale).toInt()
        val dh = (ih * scale).toInt()
        d.setBounds((W - dw) / 2, (H - dh) / 2, (W + dw) / 2, (H + dh) / 2)
        d.draw(canvas)

        val px = IntArray(W * H)
        bmp.getPixels(px, 0, W, 0, 0, W, H)
        repeat(3) { boxBlur(px, W, H, 2) }
        bmp.setPixels(px, 0, W, 0, 0, W, H)
        return bmp
    }

    /** The banner's most vivid color, normalized so it works as a dark-UI tint. */
    fun accent(backdrop: Bitmap): Int {
        val px = IntArray(W * H)
        backdrop.getPixels(px, 0, W, 0, 0, W, H)
        val hsv = FloatArray(3)
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var total = 0.0
        for (p in px) {
            Color.colorToHSV(p, hsv)
            val weight = (hsv[1] * hsv[2]).toDouble().let { it * it } + 0.001
            r += Color.red(p) * weight
            g += Color.green(p) * weight
            b += Color.blue(p) * weight
            total += weight
        }
        val avg = Color.rgb((r / total).toInt(), (g / total).toInt(), (b / total).toInt())
        Color.colorToHSV(avg, hsv)
        hsv[1] = hsv[1].coerceIn(0f, 0.85f)
        hsv[2] = hsv[2].coerceIn(0.35f, 0.75f)
        return Color.HSVToColor(hsv)
    }

    private fun boxBlur(px: IntArray, w: Int, h: Int, radius: Int) {
        val tmp = IntArray(px.size)
        pass(px, tmp, w, h, radius, horizontal = true)
        pass(tmp, px, w, h, radius, horizontal = false)
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        for (line in 0 until lines) {
            for (i in 0 until len) {
                var r = 0
                var g = 0
                var b = 0
                var n = 0
                for (k in -radius..radius) {
                    val j = (i + k).coerceIn(0, len - 1)
                    val p = if (horizontal) src[line * w + j] else src[j * w + line]
                    r += (p shr 16) and 0xFF
                    g += (p shr 8) and 0xFF
                    b += p and 0xFF
                    n++
                }
                val out = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
                if (horizontal) dst[line * w + i] = out else dst[i * w + line] = out
            }
        }
    }
}
