package dev.firefrontend

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/**
 * Hand-drawn "liquid glass" weather icon: translucent clouds with a specular rim, a glowing sun or
 * moon peeking from behind, lightning behind the cloud, and gently animated rain, snow or fog.
 *
 * Everything is drawn in a 100×100 unit space and scaled to the view, so it stays sharp at any size.
 */
class WeatherIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var visual = WeatherVisual("", Sky.CLEAR)
    private var isDay = true
    private var phase = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    private val sunGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sunBody = Paint(Paint.ANTI_ALIAS_FLAG)
    private val moonBody = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cloudFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backCloudFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cloudRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.1f }
    private val cloudSheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bolt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boltGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val drop = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
    private val dropShine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE; color = 0xCCFFFFFF.toInt()
    }
    private val flake = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flakeRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 0.6f; color = 0x99BFD9F5.toInt()
    }
    private val fogBar = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }

    private val cloudPath = Path()
    private val backCloudPath = Path()
    private val moonPath = Path()
    private val boltPath = Path()

    init {
        // Hardware-accelerated shadow layers on shapes need API 28 (Fire OS 7); older devices just skip them.
        setLayerType(LAYER_TYPE_HARDWARE, null)
        buildPaths()
        buildShaders()
    }

    fun setWeather(visual: WeatherVisual, isDay: Boolean) {
        this.visual = visual
        this.isDay = isDay
        buildShaders()
        updateAnimation()
        invalidate()
    }

    private val animated get() = visual.precip != Precip.NONE || visual.thunder || visual.fog

    private fun updateAnimation() {
        if (animated && isAttachedToWindow && visibility == VISIBLE) {
            if (!animator.isStarted) animator.start()
        } else {
            animator.cancel()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) updateAnimation() else animator.cancel()
    }

    private fun buildPaths() {
        cloudPath.reset()
        val parts = listOf(
            Path().apply { addCircle(36f, 58f, 14f, Path.Direction.CW) },
            Path().apply { addCircle(54f, 47f, 20f, Path.Direction.CW) },
            Path().apply { addCircle(71f, 59f, 13f, Path.Direction.CW) },
            Path().apply { addRoundRect(22f, 56f, 84f, 72f, 8f, 8f, Path.Direction.CW) },
        )
        parts.forEach { cloudPath.op(it, Path.Op.UNION) }

        // A smaller, darker cloud behind for overcast skies.
        backCloudPath.reset()
        val back = listOf(
            Path().apply { addCircle(60f, 40f, 14f, Path.Direction.CW) },
            Path().apply { addCircle(76f, 46f, 11f, Path.Direction.CW) },
            Path().apply { addRoundRect(52f, 42f, 88f, 56f, 7f, 7f, Path.Direction.CW) },
        )
        back.forEach { backCloudPath.op(it, Path.Op.UNION) }

        boltPath.reset()
        boltPath.moveTo(58f, 58f)
        boltPath.lineTo(45f, 80f)
        boltPath.lineTo(54f, 80f)
        boltPath.lineTo(47f, 98f)
        boltPath.lineTo(67f, 73f)
        boltPath.lineTo(57f, 73f)
        boltPath.lineTo(64f, 58f)
        boltPath.close()
    }

    private fun buildShaders() {
        val dark = visual.isWet || visual.thunder || visual.sky == Sky.OVERCAST
        val (top, bottom) = when {
            visual.thunder -> 0xF0B9C2D3.toInt() to 0xC0707C94.toInt()
            dark -> 0xF0E3E9F2.toInt() to 0xC09AA8BE.toInt()
            else -> 0xF5FFFFFF.toInt() to 0xC8D5E0EE.toInt()
        }
        cloudFill.shader = LinearGradient(0f, 27f, 0f, 72f, top, bottom, Shader.TileMode.CLAMP)
        cloudFill.setShadowLayer(4f, 0f, 2.5f, 0x40000000)
        backCloudFill.shader = LinearGradient(0f, 26f, 0f, 56f, 0xB0C5CFDD.toInt(), 0x807D8AA0.toInt(), Shader.TileMode.CLAMP)
        cloudRim.shader = LinearGradient(0f, 27f, 0f, 72f, 0xFFFFFFFF.toInt(), 0x33FFFFFF, Shader.TileMode.CLAMP)
        cloudSheen.shader = RadialGradient(47f, 37f, 13f, 0xB3FFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)

        val (sx, sy, sr) = sunGeometry()
        sunBody.shader = RadialGradient(
            sx - sr * 0.35f, sy - sr * 0.35f, sr * 1.3f,
            intArrayOf(0xFFFFF8D0.toInt(), 0xFFFFC93C.toInt(), 0xFFFF8F1F.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        sunGlow.shader = RadialGradient(sx, sy, sr * 1.9f, 0x66FFB300, 0x00FFB300, Shader.TileMode.CLAMP)

        moonPath.reset()
        val disc = Path().apply { addCircle(sx, sy, sr, Path.Direction.CW) }
        val bite = Path().apply { addCircle(sx + sr * 0.55f, sy - sr * 0.35f, sr * 0.85f, Path.Direction.CW) }
        moonPath.op(disc, bite, Path.Op.DIFFERENCE)
        moonBody.shader = LinearGradient(sx - sr, sy - sr, sx + sr, sy + sr, 0xFFFDFBF2.toInt(), 0xFFB9BCD6.toInt(), Shader.TileMode.CLAMP)
        moonBody.setShadowLayer(6f, 0f, 0f, 0x66E8E6FF)

        bolt.shader = LinearGradient(0f, 58f, 0f, 98f, 0xFFFFF7B0.toInt(), 0xFFFFB300.toInt(), Shader.TileMode.CLAMP)
        boltGlow.shader = RadialGradient(56f, 80f, 26f, 0x88FFE066.toInt(), 0x00FFE066, Shader.TileMode.CLAMP)

        drop.color = 0xE68FCBFF.toInt()
        flake.shader = RadialGradient(0f, 0f, 3f, 0xFFFFFFFF.toInt(), 0xFFDCEBFA.toInt(), Shader.TileMode.CLAMP)
    }

    /** Sun/moon position: centered when alone, tucked behind the cloud's upper left otherwise. */
    private fun sunGeometry(): Triple<Float, Float, Float> =
        if (visual.sky == Sky.CLEAR && visual.precip == Precip.NONE && !visual.fog) Triple(50f, 50f, 24f)
        else Triple(37f, 36f, 17f)

    override fun onDraw(canvas: Canvas) {
        val scale = minOf(width, height) / 100f
        canvas.save()
        canvas.translate((width - 100f * scale) / 2f, (height - 100f * scale) / 2f)
        canvas.scale(scale, scale)

        val showCloud = visual.sky != Sky.CLEAR || visual.precip != Precip.NONE || visual.thunder || visual.fog
        val showSun = visual.sky == Sky.CLEAR || visual.sky == Sky.PARTLY

        if (showSun) drawSunOrMoon(canvas)
        if (visual.thunder) drawBolt(canvas)
        if (showCloud) {
            if (visual.sky == Sky.OVERCAST || visual.thunder) canvas.drawPath(backCloudPath, backCloudFill)
            canvas.drawPath(cloudPath, cloudFill)
            canvas.save()
            canvas.clipPath(cloudPath)
            canvas.drawCircle(47f, 37f, 13f, cloudSheen)
            canvas.restore()
            canvas.drawPath(cloudPath, cloudRim)
        }
        drawPrecip(canvas)
        if (visual.fog) drawFog(canvas)
        canvas.restore()
    }

    private fun drawSunOrMoon(canvas: Canvas) {
        val (sx, sy, sr) = sunGeometry()
        if (isDay) {
            canvas.drawCircle(sx, sy, sr * 1.9f, sunGlow)
            canvas.drawCircle(sx, sy, sr, sunBody)
        } else {
            canvas.drawPath(moonPath, moonBody)
        }
    }

    private fun drawBolt(canvas: Canvas) {
        // Occasional double flash, otherwise a steady dim glow behind the cloud.
        val flash = when {
            phase < 0.05f -> 1f
            phase < 0.09f -> 0.45f
            phase < 0.13f -> 1f
            else -> 0.7f
        }
        boltGlow.alpha = (255 * flash).toInt()
        bolt.alpha = (255 * (0.55f + 0.45f * flash)).toInt()
        canvas.drawCircle(56f, 80f, 26f, boltGlow)
        canvas.drawPath(boltPath, bolt)
    }

    private fun drawPrecip(canvas: Canvas) {
        when (visual.precip) {
            Precip.NONE -> {}
            Precip.DRIZZLE -> drawRain(canvas, floatArrayOf(38f, 54f, 70f), length = 4f, width = 2.2f, slant = 0f, speed = 1f)
            Precip.LIGHT_RAIN -> drawRain(canvas, floatArrayOf(36f, 50f, 64f, 76f), length = 6f, width = 2.6f, slant = 1f, speed = 1.2f)
            Precip.RAIN -> drawRain(canvas, floatArrayOf(32f, 43f, 54f, 65f, 76f), length = 8f, width = 2.8f, slant = 2f, speed = 1.6f)
            Precip.HEAVY_RAIN -> drawRain(
                canvas, floatArrayOf(30f, 38f, 46f, 54f, 62f, 70f, 78f), length = 11f, width = 3f, slant = 3.5f, speed = 2.2f,
            )
            Precip.LIGHT_SNOW -> drawSnow(canvas, floatArrayOf(38f, 56f, 72f), size = 2.6f)
            Precip.SNOW -> drawSnow(canvas, floatArrayOf(32f, 45f, 58f, 71f), size = 3f)
            Precip.HEAVY_SNOW -> drawSnow(canvas, floatArrayOf(30f, 40f, 50f, 60f, 70f, 80f), size = 3.2f)
            Precip.SLEET -> {
                drawRain(canvas, floatArrayOf(36f, 62f), length = 6f, width = 2.6f, slant = 1f, speed = 1.4f)
                drawSnow(canvas, floatArrayOf(49f, 75f), size = 2.6f)
            }
            Precip.HAIL -> drawHail(canvas, floatArrayOf(34f, 50f, 66f, 78f))
        }
    }

    /** Raindrops fall from under the cloud and fade out; each column has its own offset so they don't move in lockstep. */
    private fun drawRain(canvas: Canvas, xs: FloatArray, length: Float, width: Float, slant: Float, speed: Float) {
        drop.strokeWidth = width
        dropShine.strokeWidth = width * 0.35f
        xs.forEachIndexed { i, x ->
            val t = (phase * speed + i * 0.37f) % 1f
            val y = 74f + t * 22f
            val alpha = ((1f - t) * 255).toInt().coerceIn(0, 255)
            drop.alpha = alpha
            dropShine.alpha = (alpha * 0.8f).toInt()
            val dx = -slant * (length / 10f)
            canvas.drawLine(x, y, x + dx, y + length, drop)
            canvas.drawLine(x - width * 0.2f, y + 0.6f, x - width * 0.2f + dx * 0.4f, y + length * 0.4f, dropShine)
        }
    }

    private fun drawSnow(canvas: Canvas, xs: FloatArray, size: Float) {
        xs.forEachIndexed { i, x ->
            val t = (phase * 0.6f + i * 0.29f) % 1f
            val y = 76f + t * 20f
            val wobble = sin((t * 2 * Math.PI + i).toFloat()) * 2.2f
            val alpha = ((1f - t) * 255).toInt().coerceIn(0, 255)
            flake.alpha = alpha
            flakeRim.alpha = (alpha * 0.6f).toInt()
            canvas.save()
            canvas.translate(x + wobble, y)
            canvas.drawCircle(0f, 0f, size, flake)
            canvas.drawCircle(0f, 0f, size, flakeRim)
            canvas.restore()
        }
    }

    private fun drawHail(canvas: Canvas, xs: FloatArray) {
        xs.forEachIndexed { i, x ->
            val t = (phase * 1.8f + i * 0.41f) % 1f
            val y = 76f + t * 20f
            val alpha = ((1f - t) * 255).toInt().coerceIn(0, 255)
            flake.alpha = alpha
            flakeRim.alpha = alpha
            canvas.save()
            canvas.translate(x, y)
            canvas.drawCircle(0f, 0f, 3.2f, flake)
            canvas.drawCircle(0f, 0f, 3.2f, flakeRim)
            canvas.restore()
        }
    }

    private fun drawFog(canvas: Canvas) {
        fogBar.strokeWidth = 4f
        val bars = arrayOf(floatArrayOf(24f, 70f, 80f), floatArrayOf(32f, 82f, 88f), floatArrayOf(20f, 62f, 96f))
        bars.forEachIndexed { i, (x0, x1, y) ->
            val drift = sin((phase * 2 * Math.PI + i * 1.3).toFloat()) * 3f
            fogBar.shader = LinearGradient(x0, 0f, x1, 0f, 0x33FFFFFF, 0xE6FFFFFF.toInt(), Shader.TileMode.MIRROR)
            canvas.drawLine(x0 + drift, y - 12f, x1 + drift, y - 12f, fogBar)
        }
    }
}
