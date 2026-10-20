package dev.firefrontend

import android.graphics.Color

/** Background gradient for the ambient hero: follows the time of day, then shifts with the weather. */
object AmbientPalette {

    fun colors(hour: Int, visual: WeatherVisual?, feelsLikeF: Double?, isDay: Boolean? = null): Pair<Int, Int> {
        // Trust the weather service's day/night (it knows sunrise/sunset); the hour is only a fallback.
        val effectiveHour = when {
            isDay == false && hour in 8..16 -> 23
            isDay == true && (hour < 5 || hour > 20) -> 12
            else -> hour
        }
        var (top, bottom) = when (effectiveHour) {
            in 5..7 -> 0xFF2E3466.toInt() to 0xFFD9876B.toInt() // dawn
            in 8..16 -> 0xFF1D5AA6.toInt() to 0xFF76AEDF.toInt() // day
            in 17..20 -> 0xFF35275A.toInt() to 0xFFD0694A.toInt() // dusk
            else -> 0xFF0A0F26.toInt() to 0xFF1C2754.toInt() // night
        }
        if (visual != null) {
            when {
                visual.thunder -> {
                    top = blend(top, 0xFF12141F.toInt(), 0.65f)
                    bottom = blend(bottom, 0xFF3A3F57.toInt(), 0.6f)
                }
                visual.isSnowy -> {
                    top = blend(top, 0xFF8C9DB3.toInt(), 0.45f)
                    bottom = blend(bottom, 0xFFDCE6F0.toInt(), 0.45f)
                }
                visual.isWet || visual.sky == Sky.OVERCAST -> {
                    top = blend(top, 0xFF3C4656.toInt(), 0.55f)
                    bottom = blend(bottom, 0xFF7D8899.toInt(), 0.5f)
                }
                visual.fog -> {
                    top = blend(top, 0xFF8A93A0.toInt(), 0.45f)
                    bottom = blend(bottom, 0xFFB9C0C9.toInt(), 0.45f)
                }
                visual.sky == Sky.CLOUDY -> {
                    top = blend(top, 0xFF5B6678.toInt(), 0.3f)
                    bottom = blend(bottom, 0xFF9AA5B5.toInt(), 0.25f)
                }
            }
        }
        if (feelsLikeF != null) {
            when {
                feelsLikeF >= 85 -> bottom = blend(bottom, 0xFFFF8A3C.toInt(), 0.2f)
                feelsLikeF <= 32 -> top = blend(top, 0xFF9FC3E8.toInt(), 0.15f)
            }
        }
        return top to bottom
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )
}
