package dev.firefrontend

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class SettingsActivity : Activity() {

    private lateinit var weather: WeatherRepository
    private lateinit var city: EditText
    private lateinit var status: TextView
    private lateinit var units: Button
    private val net = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        weather = WeatherRepository(this)
        city = findViewById(R.id.city)
        status = findViewById(R.id.city_status)
        units = findViewById(R.id.units)

        showStatus()
        bindUnits()

        findViewById<Button>(R.id.save_city).setOnClickListener { saveCity() }
        // "Done" on the on-screen keyboard, or Enter from a remote or keyboard.
        city.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_DONE || enter) {
                saveCity(); true
            } else {
                false
            }
        }
        units.setOnClickListener {
            weather.useFahrenheit = !weather.useFahrenheit
            bindUnits()
        }
        findViewById<Button>(R.id.fire_settings).setOnClickListener { showFireSettings() }
    }

    /**
     * Fire OS has no standalone Settings screen (its top level is a row on Amazon's home, which the
     * Home redirect would bounce), so offer the sections this device actually has as direct pages.
     */
    private fun showFireSettings() {
        val sections = FIRE_SETTINGS_SECTIONS.mapNotNull { (label, candidates) ->
            candidates.firstOrNull(::opensRealPage)?.let { getString(label) to it }
        }
        if (sections.isEmpty()) {
            // Unknown firmware: fall back to Amazon's Settings row and let it stay up.
            HomeRedirect.pause()
            open(Intent(Settings.ACTION_SETTINGS))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.fire_settings)
            .setItems(sections.map { it.first }.toTypedArray()) { _, which -> open(sections[which].second) }
            .show()
    }

    /**
     * False for nothing, for pages we can't open (unexported, or behind a permission such as Amazon's
     * signature-only LAUNCHER_SETTINGS), and for Amazon's placeholders that bounce to its home.
     */
    private fun opensRealPage(intent: Intent): Boolean {
        val info = packageManager.resolveActivity(intent, 0)?.activityInfo ?: return false
        val allowed = info.permission == null ||
            packageManager.checkPermission(info.permission, packageName) == PackageManager.PERMISSION_GRANTED
        return info.exported && allowed && !info.name.contains("Dummy") &&
            !HomeRedirect.isAmazonHome(info.packageName, info.name)
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // ActivityNotFoundException, or SecurityException if Amazon locks a page down.
            Toast.makeText(this, getString(R.string.launch_failed, getString(R.string.fire_settings)), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        net.shutdownNow()
        super.onDestroy()
    }

    private fun saveCity() {
        val query = city.text.toString()
        if (query.isBlank()) return
        status.setText(R.string.city_searching)
        net.execute {
            val place = try {
                weather.geocode(query)
            } catch (e: Exception) {
                null
            }
            if (place != null) weather.savePlace(place)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (place == null) status.setText(R.string.city_not_found) else showStatus()
            }
        }
    }

    private fun showStatus() {
        val place = weather.place()
        status.text = if (place == null) getString(R.string.city_none) else getString(R.string.city_current, place.name)
    }

    private fun bindUnits() {
        units.text = getString(R.string.units_label, if (weather.useFahrenheit) "°F" else "°C")
    }

    private companion object {
        const val AMAZON_SETTINGS = "com.amazon.tv.settings.v2"

        /** Each section's ways in, most specific first; the first that opens a real page wins. */
        val FIRE_SETTINGS_SECTIONS = listOf(
            R.string.fire_settings_network to listOf(Intent(Settings.ACTION_WIFI_SETTINGS)),
            R.string.fire_settings_display to listOf(Intent(Settings.ACTION_DISPLAY_SETTINGS), Intent(Settings.ACTION_SOUND_SETTINGS)),
            R.string.fire_settings_apps to listOf(Intent(Settings.ACTION_APPLICATION_SETTINGS), Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)),
            R.string.fire_settings_device to listOf(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)),
            R.string.fire_settings_accessibility to listOf(
                Intent().setClassName(AMAZON_SETTINGS, "$AMAZON_SETTINGS.tv.accessibility.AccessibilityActivity"),
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            ),
        )
    }
}
