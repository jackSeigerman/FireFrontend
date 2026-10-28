package dev.firefrontend

import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Shared by the two ways FireFrontend takes the Home button back:
 *
 * - [HomeRedirectService], an accessibility service: near-instant on Fire OS 8, and on Fire OS 7 it
 *   covers Amazon's screen while Fire OS holds app switches. Needs to be switched on over adb.
 * - [HomeWatcherService], which polls usage stats: works wherever usage access is granted, including
 *   firmware that withholds accessibility events. Slower (Fire OS 7 holds it ~5 s).
 *
 * Either may be on, both, or neither; whichever notices Amazon's home first claims the redirect.
 */
object HomeRedirect {

    private val AMAZON_HOME_PACKAGES = setOf("com.amazon.tv.launcher", "com.amazon.firehomestarter")

    /** Amazon's home screen itself — not its Apps grid, settings or other launcher pages. */
    fun isAmazonHome(packageName: CharSequence?, className: CharSequence?) =
        packageName?.toString() in AMAZON_HOME_PACKAGES && className?.toString()?.contains("HomeActivity") == true

    @Volatile private var lastClaim = 0L
    @Volatile private var pausedUntil = 0L

    /**
     * Lets Amazon's home show for a moment. Fire OS has no standalone Settings screen: the
     * settings intent opens Amazon's home on its Settings row, which would otherwise bounce back.
     */
    fun pause() {
        pausedUntil = SystemClock.elapsedRealtime() + PAUSE_MS
    }

    /** True for the first caller per Home press; repeats within [DEBOUNCE_MS] are ignored. */
    @Synchronized
    fun claim(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now < pausedUntil) return false
        if (now - lastClaim < DEBOUNCE_MS) return false
        lastClaim = now
        return true
    }

    fun launch(context: Context) {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
    }

    private const val DEBOUNCE_MS = 1_500L
    // Longer than the watcher's lookback, so it doesn't catch the same appearance afterwards.
    private const val PAUSE_MS = 8_000L
}
