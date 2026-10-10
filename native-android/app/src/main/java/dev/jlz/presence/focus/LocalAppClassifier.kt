package dev.jlz.presence.focus

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import java.security.MessageDigest

/** Bounded metadata cache, no screenshot classification or upload of installed apps. */
class LocalAppClassifier(private val context: Context) {
    private val pm = context.packageManager
    private val prefs = context.getSharedPreferences("jlz_app_classification_v1", Context.MODE_PRIVATE)
    private val cache = linkedMapOf<String, Pair<Long, LocalAppCategory>>()

    fun identity(pkg: String): String? = runCatching {
        @Suppress("DEPRECATION")
        val info = pm.getPackageInfo(pkg, if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        @Suppress("DEPRECATION")
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        signatures?.map { signature -> MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) } }?.sorted()?.joinToString(":")
    }.getOrNull()?.takeIf { it.isNotBlank() }

    fun bind(pkg: String, category: LocalAppCategory): Boolean {
        val signature = identity(pkg) ?: return false
        // Generic browsers must never become a whole-browser study exception.
        if (category == LocalAppCategory.LEARNING && !eligibleLearning(pkg)) return false
        if (category == LocalAppCategory.SYSTEM_SAFE) return false
        prefs.edit().putString("class:$pkg", category.name).putString("cert:$pkg", signature).apply()
        cache.remove(pkg)
        return true
    }
    @Synchronized fun invalidate(pkg: String) { cache.remove(pkg) }
    fun clear(pkg: String) { prefs.edit().remove("class:$pkg").remove("cert:$pkg").apply(); cache.remove(pkg) }

    fun eligibleLearning(pkg: String): Boolean {
        if (pkg in setOf("com.openai.chatgpt", "com.fenbi.android.servant")) return true
        if (!pkg.startsWith("org.chromium.webapk.")) return false
        return runCatching {
            @Suppress("DEPRECATION")
            val meta = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA).metaData
            val origin = android.net.Uri.parse(meta?.getString("org.chromium.webapk.shell_apk.startUrl").orEmpty())
            origin.scheme == "https" && origin.host == "banduread-study.sujiaojiao99.chatgpt.site"
        }.getOrDefault(false)
    }

    @Synchronized fun classify(pkg: String): LocalAppCategory {
        if (pkg in setOf(context.packageName, "com.android.settings", "com.android.systemui")) return LocalAppCategory.SYSTEM_SAFE
        val now = System.currentTimeMillis()
        cache[pkg]?.takeIf { now - it.first < 30_000L }?.let { return it.second }
        val result = runCatching {
            @Suppress("DEPRECATION")
            val info = pm.getApplicationInfo(pkg, 0)
            val safe = pkg == context.packageName || pkg == "com.android.systemui" || pkg == "com.android.settings" ||
                pkg == context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage ||
                pkg == pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName ||
                context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == pkg } ||
                ((info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (pkg.contains("clock") || pkg.contains("alarm") || pkg.contains("permissioncontroller") || pkg.contains("packageinstaller") || pkg.contains("incall") || pkg.contains("emergency") || pkg == "com.google.android.gms")))
            if (safe) LocalAppCategory.SYSTEM_SAFE
            else {
                val correction = prefs.getString("class:$pkg", null)
                val sameSigner = correction != null && prefs.getString("cert:$pkg", null) == identity(pkg)
                if (sameSigner) LocalAppCategory.valueOf(correction!!)
                else if (info.category == ApplicationInfo.CATEGORY_GAME || (info.flags and ApplicationInfo.FLAG_IS_GAME) != 0 || pkg in setOf("com.tencent.tmgp.sgame", "com.tencent.tmgp.pubgmhd", "com.miHoYo.Yuanshen")) LocalAppCategory.GAME
                else if (pkg in setOf("com.xingin.xhs", "com.ss.android.ugc.aweme", "com.smile.gifmaker", "tv.danmaku.bili", "com.sina.weibo", "com.taobao.taobao", "com.xunmeng.pinduoduo")) LocalAppCategory.FEED
                else LocalAppCategory.UNKNOWN
            }
        }.getOrDefault(LocalAppCategory.UNKNOWN)
        if (cache.size > 256) cache.clear()
        cache[pkg] = now to result
        return result
    }
}
