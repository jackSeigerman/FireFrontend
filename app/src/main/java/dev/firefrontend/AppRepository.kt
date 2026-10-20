package dev.firefrontend

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.drawable.Drawable

data class AppEntry(
    val packageName: String,
    val label: String,
    val image: Drawable,
    val launchIntent: Intent,
    /** Blurred backdrop and accent color for the home hero; only built when asked for. */
    val backdrop: Bitmap? = null,
    val accent: Int = 0,
)

/** Discovers launchable apps (Leanback + regular launcher) and tracks which ones the user hid. */
class AppRepository(private val context: Context) {

    private val pm: PackageManager = context.packageManager
    private val prefs = context.getSharedPreferences("apps", Context.MODE_PRIVATE)

    fun loadAll(withArt: Boolean = false): List<AppEntry> {
        val leanback = query(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val launcher = query(Intent.CATEGORY_LAUNCHER)

        // Prefer the Leanback activity for a package: it's the TV entry point and has a banner.
        val byPackage = LinkedHashMap<String, ResolveInfo>()
        for (info in leanback + launcher) {
            val pkg = info.activityInfo.packageName
            if (pkg != context.packageName && pkg !in byPackage) byPackage[pkg] = info
        }

        val isLeanback = leanback.mapTo(HashSet()) { it.activityInfo.packageName }
        return byPackage.values.map { info ->
            val activity = info.activityInfo
            val pkg = activity.packageName
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(if (pkg in isLeanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
                .setClassName(pkg, activity.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            val image = activity.loadBanner(pm) ?: activity.applicationInfo.loadBanner(pm) ?: info.loadIcon(pm)
            val backdrop = if (withArt) HeroArt.backdrop(image) else null
            AppEntry(
                packageName = pkg,
                label = info.loadLabel(pm).toString(),
                image = image,
                launchIntent = intent,
                backdrop = backdrop,
                accent = backdrop?.let { HeroArt.accent(it) } ?: 0,
            )
        }.sortedBy { it.label.lowercase() }
    }

    /** Last time each package was in the foreground, from usage access (empty if not granted). */
    fun lastUsed(): Map<String, Long> {
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        return try {
            usage.queryAndAggregateUsageStats(now - 30L * 24 * 60 * 60 * 1000, now)
                .mapValues { it.value.lastTimeUsed }
                .filterValues { it > 0 }
        } catch (e: SecurityException) {
            emptyMap()
        }
    }

    private fun query(category: String): List<ResolveInfo> =
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)

    fun hidden(): Set<String> = prefs.getStringSet(KEY_HIDDEN, emptySet())!!.toSet()

    fun setHidden(packageName: String, hide: Boolean) {
        val updated = hidden().toMutableSet()
        if (hide) updated.add(packageName) else updated.remove(packageName)
        prefs.edit().putStringSet(KEY_HIDDEN, updated).apply()
    }

    private companion object {
        const val KEY_HIDDEN = "hidden_packages"
    }
}
