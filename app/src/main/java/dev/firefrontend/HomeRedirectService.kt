package dev.firefrontend

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Primary Home redirect: an accessibility service that notices Amazon's home screen the moment it
 * appears and brings FireFrontend back — the same approach Projectivy and Home on Fire use.
 *
 * - Fire OS 8: near-instant; Amazon's screen flashes for a fraction of a second.
 * - Fire OS 7 and older: Fire OS holds app switches for up to ~5 s after Home. While it does, this
 *   service covers Amazon's screen with a loading skeleton of FireFrontend's home so the wait doesn't show
 *   Amazon's home. Accessibility overlays need no extra permission.
 * - Some firmware withholds accessibility events entirely; then nothing here runs and
 *   [HomeWatcherService] still handles Home.
 *
 * Switched on over adb by the installer. Reads no screen content.
 */
class HomeRedirectService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var cover: View? = null
    private val hideCoverLater = Runnable { hideCover() }

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!HomeRedirect.isAmazonHome(event.packageName, event.className)) return
        if (!HomeRedirect.claim()) return
        Log.i(TAG, "Amazon home appeared; redirecting")
        if (Build.VERSION.SDK_INT < FIRE_OS_8_API) showCover()
        HomeRedirect.launch(this)
    }

    /**
     * Home, caught before Fire OS acts on it: Amazon's home never opens, so there's nothing to flash
     * and no app-switch hold. Every other key goes through untouched.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled && HomeRedirect.claim()) {
            Log.i(TAG, "Home pressed; opening FireFrontend")
            HomeRedirect.launch(this)
        }
        return true
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        hideCover()
        instance = null
        return super.onUnbind(intent)
    }

    /** A loading skeleton of FireFrontend's home over Amazon's while Fire OS holds FireFrontend back. */
    private fun showCover() {
        if (cover != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = HomeSkeletonView(this).apply {
            // Swallow remote presses so they don't act on the hidden Amazon screen underneath.
            isFocusable = true
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, _ -> keyCode != KeyEvent.KEYCODE_HOME }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.OPAQUE,
        )
        try {
            wm.addView(view, params)
            view.requestFocus()
            cover = view
            handler.postDelayed(hideCoverLater, COVER_TIMEOUT_MS)
        } catch (e: RuntimeException) {
            // Overlay refused on this firmware: the redirect still happens, just without the cover.
            Log.w(TAG, "cover unavailable", e)
        }
    }

    private fun hideCover() {
        handler.removeCallbacks(hideCoverLater)
        val view = cover ?: return
        cover = null
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
        } catch (e: RuntimeException) {
            // Already gone.
        }
    }

    companion object {
        private const val TAG = "HomeRedirect"
        // Fire OS 8 is Android 11 and doesn't hold app switches after Home.
        private const val FIRE_OS_8_API = 30
        // Fire OS 7's hold is at most ~5 s; drop the cover after that even if the launch never lands.
        private const val COVER_TIMEOUT_MS = 6_000L

        @Volatile private var instance: HomeRedirectService? = null

        /** Called when FireFrontend's home screen is back in front. */
        fun onHomeShown() {
            instance?.let { service -> service.handler.post { service.hideCover() } }
        }
    }
}
