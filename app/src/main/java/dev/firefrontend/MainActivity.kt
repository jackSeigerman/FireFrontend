package dev.firefrontend

import android.graphics.drawable.Drawable
import android.graphics.Rect
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateUtils
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var repo: AppRepository
    private lateinit var weather: WeatherRepository
    private lateinit var backdrop: HeroBackdropView
    private lateinit var grid: GridView
    private lateinit var empty: TextView
    private lateinit var smallClock: View
    private lateinit var ambientHero: View
    private lateinit var appHero: View
    private lateinit var appName: TextView
    private lateinit var appMeta: TextView
    private lateinit var appBanner: ImageView
    private lateinit var weatherIcon: WeatherIconView
    private lateinit var weatherTemp: TextView
    private lateinit var weatherCondition: TextView
    private lateinit var weatherDetail: TextView

    private val adapter = AppAdapter()
    private val loader = Executors.newSingleThreadExecutor()
    private val net = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var lastUsed: Map<String, Long> = emptyMap()
    private var currentWeather: WeatherRepository.Current? = null
    private var focusedTile: View? = null
    private var firstLoad = true
    private var resetSelection = false

    /** The ambient hero shows until the user actually moves through the apps. */
    private var showingApp = false
    private var userNavigated = false

    /** Grid position of the app being moved, or -1 when not moving one. */
    private var movingIndex = -1

    private val idleToAmbient = Runnable { showAmbient() }
    private val periodic = object : Runnable {
        override fun run() {
            refreshWeather()
            if (!showingApp) applyAmbientBackdrop()
            handler.postDelayed(this, PERIODIC_MS)
        }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        repo = AppRepository(this)
        weather = WeatherRepository(this)

        backdrop = findViewById(R.id.backdrop)
        grid = findViewById(R.id.grid)
        empty = findViewById(R.id.empty)
        smallClock = findViewById(R.id.small_clock)
        ambientHero = findViewById(R.id.ambient_hero)
        appHero = findViewById(R.id.app_hero)
        appName = findViewById(R.id.app_name)
        appMeta = findViewById(R.id.app_meta)
        appBanner = findViewById(R.id.app_banner)
        weatherIcon = findViewById(R.id.weather_icon)
        weatherTemp = findViewById(R.id.weather_temp)
        weatherCondition = findViewById(R.id.weather_condition)
        weatherDetail = findViewById(R.id.weather_detail)

        val density = resources.displayMetrics.density
        findViewById<View>(R.id.weather_card).background = GlassDrawable(density, radiusDp = 26f)
        findViewById<View>(R.id.app_banner_frame).background = GlassDrawable(density, radiusDp = 16f)
        appBanner.clipToOutline = true

        grid.adapter = adapter
        // The grid doesn't clip so focused tiles can grow past its sides, but rows scrolled off its
        // top would then draw over the hero. Clip the top edge only.
        grid.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val overflow = (48 * density).toInt()
            v.clipBounds = Rect(-overflow, 0, v.width + overflow, v.height + overflow)
        }
        grid.setOnItemClickListener { _, _, position, _ -> launch(adapter.getItem(position)) }
        grid.setOnItemLongClickListener { _, _, position, _ ->
            showAppMenu(adapter.getItem(position)); true
        }
        grid.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                setFocusedTile(view)
                if (userNavigated && grid.hasFocus()) showApp(adapter.getItem(position))
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = setFocusedTile(null)
        }
        grid.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                setFocusedTile(grid.selectedView)
            } else {
                setFocusedTile(null)
                showAmbient()
            }
        }

        findViewById<View>(R.id.manage).setOnClickListener {
            startActivity(Intent(this, ManageAppsActivity::class.java))
        }
        findViewById<View>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
    }

    override fun onResume() {
        super.onResume()
        // On resume rather than create: usage access may have been granted while we were in the background.
        HomeWatcherService.start(this)
        // Back in front: drop the cover the Home redirect may have put over Amazon's screen.
        HomeRedirectService.onHomeShown()
        userNavigated = false
        bindWeather(debugWeather() ?: weather.cached())
        showAmbient(immediate = true)
        // The ambient hero goes with the top of the grid: coming back to the old selection, maybe
        // rows down, would leave it highlighted under the weather instead of its own hero.
        resetSelection = true
        if (adapter.count > 0) grid.setSelection(0)
        reload()
        handler.post(periodic)
    }

    override fun onPause() {
        // Leaving mid-move (Home, an app opening) keeps where the app was put.
        if (movingIndex >= 0) finishMove()
        handler.removeCallbacks(periodic)
        handler.removeCallbacks(idleToAmbient)
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        loader.shutdownNow()
        net.shutdownNow()
        super.onDestroy()
    }

    // As a home screen, Back should do nothing rather than exit.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (showingApp) showAmbient()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (movingIndex >= 0 && handleMoveKey(event)) return true
        if (event.action == KeyEvent.ACTION_DOWN) {
            handler.removeCallbacks(idleToAmbient)
            handler.postDelayed(idleToAmbient, IDLE_MS)
            if (event.keyCode in DPAD_KEYS) userNavigated = true
        }
        val handled = super.dispatchKeyEvent(event)
        // Pressing a direction that doesn't move the selection still wakes the app hero.
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in DPAD_KEYS && grid.hasFocus()) {
            val position = grid.selectedItemPosition
            if (position >= 0) showApp(adapter.getItem(position))
        }
        return handled
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU && grid.hasFocus()) {
            val position = grid.selectedItemPosition
            if (position >= 0) {
                showAppMenu(adapter.getItem(position))
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // --- Hero -------------------------------------------------------------------------------

    private fun showAmbient(immediate: Boolean = false) {
        showingApp = false
        // Back to the top of the grid with the weather, so the first row isn't left faded out under
        // it. The selection stays where it was; the next press scrolls back to it.
        if (!immediate && adapter.count > 0) grid.smoothScrollToPosition(0)
        crossfade(show = ambientHero, hide = appHero, immediate)
        smallClock.animate().alpha(0f).setDuration(if (immediate) 0 else FADE_MS).start()
        applyAmbientBackdrop()
    }

    private fun applyAmbientBackdrop() {
        val c = currentWeather
        val visual = c?.let { WeatherCodes.visual(it.code, it.isDay) }
        val (top, bottom) = AmbientPalette.colors(Calendar.getInstance().get(Calendar.HOUR_OF_DAY), visual, c?.feelsLikeF, c?.isDay)
        backdrop.showGradient(top, bottom)
    }

    private fun showApp(app: AppEntry) {
        if (!showingApp) {
            showingApp = true
            crossfade(show = appHero, hide = ambientHero, immediate = false)
            smallClock.animate().alpha(1f).setDuration(FADE_MS).start()
        }
        appName.text = app.label
        val used = lastUsed[app.packageName]
        appMeta.text = if (used != null) {
            getString(
                R.string.last_opened,
                DateUtils.getRelativeTimeSpanString(used, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
            )
        } else {
            getString(R.string.never_opened)
        }
        appBanner.showArt(app.image)
        app.backdrop?.let { backdrop.showImage(it, app.accent) }
        if (movingIndex >= 0) appMeta.setText(R.string.move_hint)
    }

    /**
     * Banners fill the 16:9 tile, cropping a little if they aren't quite 16:9 (YouTube TV's is about
     * 4:3), rather than sitting between bars. Square icons, for apps without a banner, stay whole.
     */
    private fun ImageView.showArt(image: Drawable) {
        val w = image.intrinsicWidth
        val h = image.intrinsicHeight
        val banner = w > 0 && h > 0 && w.toFloat() / h >= BANNER_MIN_ASPECT
        scaleType = if (banner) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        setImageDrawable(image)
    }

    private fun crossfade(show: View, hide: View, immediate: Boolean) {
        val duration = if (immediate) 0L else FADE_MS
        hide.animate().cancel()
        show.animate().cancel()
        hide.animate().alpha(0f).setDuration(duration).withEndAction { hide.visibility = View.INVISIBLE }.start()
        show.visibility = View.VISIBLE
        show.animate().alpha(1f).setDuration(duration).start()
    }

    private fun setFocusedTile(view: View?) {
        if (focusedTile === view) return
        focusedTile?.let { animateTile(it, focused = false) }
        focusedTile = view
        view?.let { animateTile(it, focused = true) }
    }

    private fun animateTile(view: View, focused: Boolean) {
        val elevation = if (focused) 18 * resources.displayMetrics.density else 0f
        // The app being moved sits lifted a little higher than a normal focused tile.
        val scale = if (!focused) 1f else if (movingIndex >= 0) 1.18f else 1.1f
        view.animate()
            .scaleX(scale)
            .scaleY(scale)
            .translationZ(elevation)
            .setDuration(TILE_MS)
            .start()
        view.findViewById<View>(R.id.label)?.animate()?.alpha(if (focused) 1f else 0.55f)?.setDuration(TILE_MS)?.start()
    }

    // --- Weather ----------------------------------------------------------------------------

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    /**
     * Testing aid: `adb shell am start -n dev.firefrontend/.MainActivity --ei debug_weather_code 95
     * --ez debug_weather_day false --ei debug_weather_temp 30` previews any condition.
     */
    private fun debugWeather(): WeatherRepository.Current? {
        val code = intent?.getIntExtra("debug_weather_code", -1) ?: -1
        if (code < 0) return null
        val temp = intent.getIntExtra("debug_weather_temp", 55).toDouble()
        val day = intent.getBooleanExtra("debug_weather_day", true)
        return WeatherRepository.Current(code, temp, temp, day, temp + 6, temp - 8, System.currentTimeMillis())
    }

    private fun refreshWeather() {
        if (debugWeather() != null) return
        if (weather.place() == null || !weather.isStale()) return
        net.execute {
            val c = try {
                weather.refresh()
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (c != null) {
                    bindWeather(c)
                    if (!showingApp) applyAmbientBackdrop()
                } else if (currentWeather == null) {
                    weatherCondition.setText(R.string.weather_unavailable)
                }
            }
        }
    }

    private fun bindWeather(c: WeatherRepository.Current?) {
        currentWeather = c
        val place = weather.place() ?: debugWeather()?.let { WeatherRepository.Place("Preview", 0.0, 0.0) }
        if (place == null || c == null) {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            weatherIcon.setWeather(WeatherVisual("", Sky.PARTLY), isDay = hour in 6..19)
            weatherTemp.text = ""
            weatherCondition.setText(if (place == null) R.string.weather_setup else R.string.weather_unavailable)
            weatherDetail.text = place?.name ?: ""
            return
        }
        val visual = WeatherCodes.visual(c.code, c.isDay)
        weatherIcon.setWeather(visual, c.isDay)
        weatherTemp.text = weather.format(c.tempF)
        weatherCondition.text = listOfNotNull(visual.label, WeatherCodes.feel(c.feelsLikeF)).joinToString(" · ")
        weatherDetail.text = getString(R.string.weather_range, weather.format(c.highF), weather.format(c.lowF), place.name)
    }

    // --- Apps -------------------------------------------------------------------------------

    private fun reload() {
        loader.execute {
            val apps = repo.loadAll(withArt = true)
            val hidden = repo.hidden()
            val used = repo.lastUsed()
            val visible = apps.filter { it.packageName !in hidden }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                lastUsed = used
                val selected = if (resetSelection) 0 else grid.selectedItemPosition
                resetSelection = false
                adapter.items = visible
                empty.visibility = if (visible.isEmpty() && apps.isNotEmpty()) View.VISIBLE else View.GONE
                if (visible.isNotEmpty()) grid.setSelection(selected.coerceIn(0, visible.size - 1))
                if (firstLoad) {
                    firstLoad = false
                    grid.requestFocus()
                }
            }
        }
    }

    private fun launch(app: AppEntry) {
        try {
            startActivity(app.launchIntent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.launch_failed, app.label), Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, getString(R.string.launch_failed, app.label), Toast.LENGTH_SHORT).show()
        }
    }

    // --- Reordering ---------------------------------------------------------------------------

    private fun startMove(position: Int) {
        movingIndex = position
        grid.requestFocus()
        grid.setSelection(position)
        showApp(adapter.getItem(position))
        appMeta.setText(R.string.move_hint)
        focusedTile?.animate()?.scaleX(1.18f)?.scaleY(1.18f)?.setDuration(TILE_MS)?.start()
    }

    /** While moving, the arrows carry the app through the grid and OK or Back drops it. */
    private fun handleMoveKey(event: KeyEvent): Boolean {
        val step = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            KeyEvent.KEYCODE_DPAD_UP -> -grid.numColumns.coerceAtLeast(1)
            KeyEvent.KEYCODE_DPAD_DOWN -> grid.numColumns.coerceAtLeast(1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> 0
            else -> return false
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        if (step == 0) {
            finishMove()
            return true
        }
        val target = (movingIndex + step).coerceIn(0, adapter.count - 1)
        if (target != movingIndex) {
            adapter.move(movingIndex, target)
            movingIndex = target
            grid.setSelection(target)
            // The selected view is rebound; re-apply the lifted look once it's laid out.
            grid.post { grid.selectedView?.animate()?.scaleX(1.18f)?.scaleY(1.18f)?.setDuration(0)?.start() }
        }
        return true
    }

    private fun finishMove() {
        movingIndex = -1
        repo.saveOrder(adapter.items.map { it.packageName })
        grid.selectedView?.let { animateTile(it, focused = true) }
        val position = grid.selectedItemPosition
        if (position >= 0) showApp(adapter.getItem(position))
    }

    private fun moveToFront(position: Int) {
        adapter.move(position, 0)
        repo.saveOrder(adapter.items.map { it.packageName })
        grid.setSelection(0)
    }

    private fun showAppMenu(app: AppEntry) {
        val position = adapter.items.indexOf(app)
        val options = arrayOf(
            getString(R.string.move_app),
            getString(R.string.move_to_front),
            getString(R.string.hide_app),
            getString(R.string.app_info),
        )
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startMove(position)
                    1 -> moveToFront(position)
                    2 -> {
                        repo.setHidden(app.packageName, true)
                        reload()
                    }
                    3 -> try {
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))
                        )
                    } catch (e: ActivityNotFoundException) {
                        // Some Fire OS builds don't expose this screen.
                    }
                }
            }
            .show()
    }

    private inner class AppAdapter : BaseAdapter() {
        var items: List<AppEntry> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        fun move(from: Int, to: Int) {
            items = items.toMutableList().apply { add(to, removeAt(from)) }
        }

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_app, parent, false).also {
                it.findViewById<View>(R.id.tile).clipToOutline = true
            }
            val app = items[position]
            view.findViewById<ImageView>(R.id.icon).showArt(app.image)
            view.findViewById<TextView>(R.id.label).text = app.label
            // Recycled views may still carry the focus animation from another position.
            if (view !== focusedTile) {
                view.animate().cancel()
                view.scaleX = 1f
                view.scaleY = 1f
                view.translationZ = 0f
                view.findViewById<View>(R.id.label).alpha = 0.55f
            }
            return view
        }
    }

    private companion object {
        const val FADE_MS = 300L
        const val TILE_MS = 160L
        const val IDLE_MS = 30_000L
        // Anything this wide is a banner; narrower art is an icon.
        const val BANNER_MIN_ASPECT = 1.2f
        const val PERIODIC_MS = 10 * 60 * 1000L
        val DPAD_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        )
    }
}
