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
    var category: String = "其他",
    var homeRank: Int = Int.MAX_VALUE
)

class LauncherRepository(private val context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("jlz_launcher", Context.MODE_PRIVATE)

    private fun ensureSeeded() {
        if (!prefs.getBoolean(KEY_SEEDED, false)) {
            prefs.edit()
                .putStringSet(KEY_PINNED, DEFAULT_PINNED)
                .putStringSet(KEY_HIDDEN, DEFAULT_HIDDEN)
                .putString(KEY_PINNED_ORDER, DEFAULT_PINNED_ORDER.joinToString(","))
                .putBoolean(KEY_SEEDED, true)
                .apply()
            return
        }
        // Stage E migration: older installs only had an unordered StringSet.
        if (!prefs.contains(KEY_PINNED_ORDER)) {
            val existing = prefs.getStringSet(KEY_PINNED, DEFAULT_PINNED)
                ?.toSet().orEmpty()
            val migrated = (
                DEFAULT_PINNED_ORDER.filter { it in existing } +
                    existing.filter { it !in DEFAULT_PINNED_ORDER }.sorted()
                ).distinct()
            prefs.edit().putString(KEY_PINNED_ORDER, migrated.joinToString(",")).apply()
        }
    }

    private fun pinnedSet(): Set<String> {
        ensureSeeded()
        return prefs.getStringSet(KEY_PINNED, DEFAULT_PINNED)?.toSet() ?: DEFAULT_PINNED
    }

    private fun hiddenSet(): Set<String> {
        ensureSeeded()
        return prefs.getStringSet(KEY_HIDDEN, DEFAULT_HIDDEN)?.toSet() ?: DEFAULT_HIDDEN
    }

    fun pinnedPackagesInOrder(): List<String> {
        ensureSeeded()
        val pinned = pinnedSet()
        val ordered = prefs.getString(KEY_PINNED_ORDER, "")
            .orEmpty()
            .split(",")
            .map(String::trim)
            .filter { it.isNotBlank() && it in pinned }
        return (ordered + pinned.filter { it !in ordered }.sorted()).distinct()
    }

    fun isPinned(pkg: String): Boolean = pkg in pinnedSet()
    fun isHidden(pkg: String): Boolean = pkg in hiddenSet()

    fun setPinned(pkg: String, value: Boolean) {
        if (pkg.isBlank() || pkg == context.packageName) return
        val pinned = pinnedSet().toMutableSet()
        val order = pinnedPackagesInOrder().toMutableList()
        if (value) {
            pinned.add(pkg)
            if (pkg !in order) order.add(pkg)
        } else {
            pinned.remove(pkg)
            order.remove(pkg)
        }
        prefs.edit()
            .putStringSet(KEY_PINNED, pinned)
            .putString(KEY_PINNED_ORDER, order.filter { it in pinned }.joinToString(","))
            .apply()
    }

    fun setHidden(pkg: String, value: Boolean) {
        if (pkg.isBlank() || pkg == context.packageName) return
        val hidden = hiddenSet().toMutableSet()
        if (value) hidden.add(pkg) else hidden.remove(pkg)
        prefs.edit().putStringSet(KEY_HIDDEN, hidden).apply()
    }

    fun movePinned(pkg: String, delta: Int): Boolean {
        val order = pinnedPackagesInOrder().toMutableList()
        val from = order.indexOf(pkg)
        if (from < 0 || order.size < 2) return false
        val to = (from + delta).coerceIn(0, order.lastIndex)
        if (from == to) return false
        order.removeAt(from)
        order.add(to, pkg)
        prefs.edit().putString(KEY_PINNED_ORDER, order.joinToString(",")).apply()
        return true
    }

    fun loadLaunchableApps(): List<LauncherAppInfo> {
        val pm = context.packageManager
        val pinned = pinnedSet()
        val hidden = hiddenSet()
        val order = pinnedPackagesInOrder()
        val rank = order.withIndex().associate { it.value to it.index }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        val apps = pm.queryIntentActivities(intent, 0)
            .asSequence()
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { resolve ->
                val pkg = resolve.activityInfo.packageName
                LauncherAppInfo(
                    packageName = pkg,
                    activityName = resolve.activityInfo.name,
                    label = resolve.loadLabel(pm).toString(),
                    icon = runCatching { resolve.activityInfo.loadIcon(pm) }.getOrNull(),
                    pinned = pkg in pinned,
                    hidden = pkg in hidden,
                    category = categorize(pkg),
                    homeRank = rank[pkg] ?: Int.MAX_VALUE
                )
            }
            .toList()

        return apps.sortedWith(
            compareBy<LauncherAppInfo> { categoryRank(it.category) }
                .thenBy { it.label.lowercase() }
        )
    }

    fun homeApps(limit: Int = 4): List<LauncherAppInfo> {
        val byPackage = loadLaunchableApps().associateBy { it.packageName }
        return pinnedPackagesInOrder()
            .mapNotNull(byPackage::get)
            .filter { !it.hidden }
            .take(limit.coerceIn(1, 8))
    }

    fun launchApp(packageName: String): Boolean {
        if (packageName.isBlank() || packageName == context.packageName) return false
        // Only apps Android itself advertises as launchable can be opened here.
        if (loadLaunchableApps().none { it.packageName == packageName }) return false
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun categorize(pkg: String): String = when {
        pkg.startsWith("com.fenbi") ||
            pkg.startsWith("com.openai") ||
            pkg.contains("xuexi", ignoreCase = true) -> "学习"
        pkg.startsWith("com.tencent.mm") ||
            pkg.startsWith("com.tencent.mobileqq") -> "通讯"
        pkg.startsWith("com.xingin") ||
            pkg.startsWith("com.ss.android.ugc.aweme") ||
            pkg.startsWith("com.taobao") ||
            pkg.startsWith("com.xunmeng") ||
            pkg.startsWith("com.jingdong") -> "娱乐与购物"
        pkg.startsWith("com.android") ||
            pkg.startsWith("com.google.android") ||
            pkg.startsWith("com.miui") -> "系统"
        else -> "其他"
    }

    private fun categoryRank(category: String): Int = when (category) {
        "学习" -> 0
        "通讯" -> 1
        "其他" -> 2
        "娱乐与购物" -> 3
        "系统" -> 4
        else -> 5
    }

    companion object {
        private const val KEY_SEEDED = "seeded_v1"
        private const val KEY_PINNED = "pinned_packages"
        private const val KEY_PINNED_ORDER = "pinned_order_v2"
        private const val KEY_HIDDEN = "hidden_packages"

        val DEFAULT_PINNED_ORDER = listOf(
            "com.fenbi.android.servant",
            "com.openai.chatgpt",
            "com.tencent.mm"
        )
        val DEFAULT_PINNED = DEFAULT_PINNED_ORDER.toSet()
        val DEFAULT_HIDDEN = setOf("com.xingin.xhs")
    }
}
