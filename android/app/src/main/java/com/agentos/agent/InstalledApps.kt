package com.agentos.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class AppEntry(val label: String, val packageName: String, val activity: String) {
    fun icon(ctx: Context): Drawable? = try {
        ctx.packageManager.getActivityIcon(android.content.ComponentName(packageName, activity))
    } catch (_: Throwable) { null }
}

/** Launcher-visible apps, with simple fuzzy resolution by label or package for the agent. */
class InstalledApps(private val ctx: Context) {
    @Volatile private var cache: List<AppEntry> = emptyList()
    /** Bumped on every refresh, so screens showing apps redraw after an install or uninstall. */
    val version = kotlinx.coroutines.flow.MutableStateFlow(0)

    init {
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ctx.registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { refresh() }
        }, filter)
    }

    fun refresh(): List<AppEntry> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        val list = infos.map {
            AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name)
        }.filter { it.packageName != ctx.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
        cache = list
        version.value++
        return list
    }

    fun all(): List<AppEntry> = cache.ifEmpty { refresh() }

    fun labels(): List<String> = all().map { it.label }

    fun describe(): String = all().joinToString("\n") { "${it.label} -> ${it.packageName}" }

    fun resolve(query: String): AppEntry? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        val apps = all()
        apps.firstOrNull { it.packageName.equals(q, true) }?.let { return it }
        apps.firstOrNull { it.label.equals(q, true) }?.let { return it }
        apps.firstOrNull { it.label.lowercase().startsWith(q) }?.let { return it }
        apps.firstOrNull { it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }?.let { return it }
        // Token overlap as a last resort ("google maps" vs "Maps").
        val qt = q.split(' ', '-', '_').filter { it.length > 2 }.toSet()
        return apps.maxByOrNull { a ->
            val lt = a.label.lowercase().split(' ', '-', '_').toSet()
            qt.count { it in lt }
        }?.takeIf { a -> qt.any { it in a.label.lowercase() } }
    }
}
