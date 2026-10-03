package dev.jlz.presence.launcher

import android.content.Context
import android.webkit.JavascriptInterface
import dev.jlz.presence.data.LocalLifeStore
import dev.jlz.presence.study.StudyShortcuts
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local-only bridge used by the owned World Between WebShell.
 *
 * No app inventory is uploaded to Runtime. The page can only launch apps that
 * Android itself advertises as launcher activities.
 */
class AppHubBridge(context: Context) {
    private val app = context.applicationContext
    private val repo = LauncherRepository(app)
    private val lifeStore = LocalLifeStore(app)

    @JavascriptInterface
    fun version(): String = "app-hub-1"

    @JavascriptInterface
    fun snapshot(): String {
        val apps = repo.loadLaunchableApps()
        val pinnedOrder = repo.pinnedPackagesInOrder()
        return JSONObject()
            .put("version", version())
            .put("native", true)
            .put("pinned_order", JSONArray(pinnedOrder))
            .put("apps", JSONArray().apply {
                apps.forEach { item ->
                    put(
                        JSONObject()
                            .put("package_name", item.packageName)
                            .put("label", item.label)
                            .put("category", item.category)
                            .put("pinned", item.pinned)
                            .put("hidden", item.hidden)
                            .put("home_rank", item.homeRank)
                    )
                }
            })
            .toString()
    }

    @JavascriptInterface
    fun launch(packageName: String): Boolean {
        val item = repo.loadLaunchableApps().firstOrNull { it.packageName == packageName }
            ?: return false
        val ok = repo.launchApp(packageName)
        if (ok) {
            lifeStore.recordTimeline(
                "app_hub_launch",
                "从世界之间打开 " + item.label,
                item.packageName,
                metadataJson = JSONObject()
                    .put("actor", "user")
                    .put("source", "app_hub")
                    .put("package_name", item.packageName)
                    .put("category", item.category)
                    .toString()
            )
        }
        return ok
    }

    @JavascriptInterface
    fun setPinned(packageName: String, pinned: Boolean): Boolean {
        if (repo.loadLaunchableApps().none { it.packageName == packageName }) return false
        repo.setPinned(packageName, pinned)
        return true
    }

    @JavascriptInterface
    fun movePinned(packageName: String, direction: Int): Boolean =
        repo.movePinned(packageName, if (direction < 0) -1 else 1)

    @JavascriptInterface
    fun setHidden(packageName: String, hidden: Boolean): Boolean {
        if (repo.loadLaunchableApps().none { it.packageName == packageName }) return false
        repo.setHidden(packageName, hidden)
        return true
    }

    @JavascriptInterface
    fun openBanduread(): Boolean {
        val result = StudyShortcuts.openBanduread(app)
        lifeStore.recordTimeline(
            "app_hub_launch",
            "从世界之间打开伴读",
            result,
            metadataJson = JSONObject()
                .put("actor", "user")
                .put("source", "app_hub")
                .put("kind", "banduread")
                .toString()
        )
        return !result.contains("失败")
    }
}
