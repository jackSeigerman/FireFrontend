package dev.firefrontend

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

    /** The ambient hero shows until the user actually moves through the apps. */
    private var showingApp = false
    private var userNavigated = false

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
        userNavigated = false
        bindWeather(debugWeather() ?: weather.cached())
        showAmbient(immediate = true)
        reload()
        handler.post(periodic)
    }

    override fun onPause() {
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
        appBanner.setImageDrawable(app.image)
        app.backdrop?.let { backdrop.showImage(it, app.accent) }
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
        view.animate()
            .scaleX(if (focused) 1.1f else 1f)
            .scaleY(if (focused) 1.1f else 1f)
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
                val selected = grid.selectedItemPosition
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

    private fun showAppMenu(app: AppEntry) {
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(arrayOf(getString(R.string.hide_app), getString(R.string.app_info))) { _, which ->
                when (which) {
                    0 -> {
                        repo.setHidden(app.packageName, true)
                        reload()
                    }
                    1 -> try {
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

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_app, parent, false).also {
                it.findViewById<View>(R.id.tile).clipToOutline = true
            }
            val app = items[position]
            view.findViewById<ImageView>(R.id.icon).setImageDrawable(app.image)
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
        const val PERIODIC_MS = 10 * 60 * 1000L
        val DPAD_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        )
    }
}
