package dev.firefrontend

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckedTextView
import android.widget.ListView
import java.util.concurrent.Executors

/** Lists every discovered app; checked = shown on the home screen. */
class ManageAppsActivity : Activity() {

    private lateinit var repo: AppRepository
    private lateinit var list: ListView
    private var apps: List<AppEntry> = emptyList()
    private val loader = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manage)
        repo = AppRepository(this)
        list = findViewById(R.id.list)

        list.setOnItemClickListener { _, _, position, _ ->
            repo.setHidden(apps[position].packageName, !list.isItemChecked(position))
        }

        loader.execute {
            val loaded = repo.loadAll()
            val hidden = repo.hidden()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                apps = loaded
                list.adapter = Adapter()
                loaded.forEachIndexed { i, app -> list.setItemChecked(i, app.packageName !in hidden) }
                list.requestFocus()
            }
        }
    }

    override fun onDestroy() {
        loader.shutdownNow()
        super.onDestroy()
    }

    private inner class Adapter : BaseAdapter() {
        private val iconSize = (40 * resources.displayMetrics.density).toInt()

        override fun getCount() = apps.size
        override fun getItem(position: Int) = apps[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = (convertView ?: layoutInflater.inflate(R.layout.item_manage, parent, false)) as CheckedTextView
            val app = apps[position]
            view.text = app.label
            // Drawables are shared with other views, so give this one its own bounds via a mutated copy.
            val icon = app.image.constantState?.newDrawable(resources)?.mutate() ?: app.image
            val width = if (icon.intrinsicHeight > 0) iconSize * icon.intrinsicWidth / icon.intrinsicHeight else iconSize
            icon.setBounds(0, 0, width.coerceAtMost(iconSize * 2), iconSize)
            view.setCompoundDrawables(icon, null, null, null)
            return view
        }
    }
}
