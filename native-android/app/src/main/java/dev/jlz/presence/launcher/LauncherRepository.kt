package dev.jlz.presence.launcher

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

data class LauncherAppInfo(
    val packageName: String,
    val activityName: String,
    val label: String,
    val icon: Drawable?,
    var pinned: Boolean = false,
    var hidden: Boolean = false,
    var category: String = "其他"
)

class LauncherRepository(private val context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("jlz_launcher", Context.MODE_PRIVATE)

    private fun ensureSeeded() {
        if (prefs.getBoolean(KEY_SEEDED, false)) return
        prefs.edit()
            .putStringSet(KEY_PINNED, DEFAULT_PINNED)
            .putStringSet(KEY_HIDDEN, DEFAULT_HIDDEN)
            .putBoolean(KEY_SEEDED, true)
            .apply()
    }

    private fun pinnedSet(): Set<String> { ensureSeeded(); return prefs.getStringSet(KEY_PINNED, DEFAULT_PINNED)?.toSet() ?: DEFAULT_PINNED }
    private fun hiddenSet(): Set<String> { ensureSeeded(); return prefs.getStringSet(KEY_HIDDEN, DEFAULT_HIDDEN)?.toSet() ?: DEFAULT_HIDDEN }

    fun isPinned(pkg: String): Boolean = pkg in pinnedSet()
    fun isHidden(pkg: String): Boolean = pkg in hiddenSet()

    fun setPinned(pkg: String, value: Boolean) {
        val set = pinnedSet().toMutableSet()
        if (value) set.add(pkg) else set.remove(pkg)
        prefs.edit().putStringSet(KEY_PINNED, set).apply()
    }

    fun setHidden(pkg: String, value: Boolean) {
        val set = hiddenSet().toMutableSet()
        if (value) set.add(pkg) else set.remove(pkg)
        prefs.edit().putStringSet(KEY_HIDDEN, set).apply()
    }

    fun loadLaunchableApps(): List<LauncherAppInfo> {
        val pm = context.packageManager
        val pinned = pinnedSet()
        val hidden = hiddenSet()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0).map { resolve ->
            val pkg = resolve.activityInfo.packageName
            LauncherAppInfo(
                packageName = pkg,
                activityName = resolve.activityInfo.name,
                label = resolve.loadLabel(pm).toString(),
                icon = resolve.activityInfo.loadIcon(pm),
                pinned = pkg in pinned,
                hidden = pkg in hidden,
                category = categorize(pkg)
            )
        }.sortedBy { it.label }
    }

    fun launchApp(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    private fun categorize(pkg: String): String = when {
        pkg.startsWith("com.openai") -> "学习"
        pkg.startsWith("com.fenbi") -> "学习"
        pkg.startsWith("com.tencent.mm") -> "社交"
        pkg.startsWith("com.xingin") -> "娱乐"
        pkg.startsWith("com.android") || pkg.startsWith("com.google.android") -> "系统"
        else -> "其他"
    }

    companion object {
        private const val KEY_SEEDED = "seeded_v1"
        private const val KEY_PINNED = "pinned_packages"
        private const val KEY_HIDDEN = "hidden_packages"
        val DEFAULT_PINNED = setOf("com.openai.chatgpt", "com.fenbi.android.servant", "com.tencent.mm")
        val DEFAULT_HIDDEN = setOf("com.xingin.xhs")
    }
}
