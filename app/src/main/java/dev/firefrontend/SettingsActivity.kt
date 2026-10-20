package dev.firefrontend

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
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
        city.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveCity(); true
            } else {
                false
            }
        }
        units.setOnClickListener {
            weather.useFahrenheit = !weather.useFahrenheit
            bindUnits()
        }
        findViewById<Button>(R.id.fire_settings).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, getString(R.string.launch_failed, getString(R.string.fire_settings)), Toast.LENGTH_SHORT).show()
            }
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
}
