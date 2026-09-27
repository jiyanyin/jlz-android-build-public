package dev.jlz.presence.study

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/** External apps are optional: never pretend a web fallback opened a native app. */
object StudyShortcuts {
    const val BANDUREAD_URL = "https://banduread-study.sujiaojiao99.chatgpt.site/"
    const val FENBI_URL = "https://www.fenbi.com/"

    fun openBanduread(context: Context): String =
        openWeb(context, BANDUREAD_URL, "伴读")

    fun openFenbi(context: Context): String {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pm = context.packageManager
        val installed = pm.queryIntentActivities(launcher, 0)
            .firstOrNull { entry ->
                val label = entry.loadLabel(pm).toString()
                label == "粉笔" || label.startsWith("粉笔") ||
                    label.contains("粉笔公考")
            }
        if (installed != null) {
            val packageName = installed.activityInfo.packageName
            val target = pm.getLaunchIntentForPackage(packageName)
            if (target != null) {
                return runCatching {
                    target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(target)
                    "已尝试打开粉笔 APP"
                }.getOrElse { "粉笔 APP 启动失败：" + it.javaClass.simpleName }
            }
        }
        return "本机未找到可启动的粉笔 APP；" +
            openWeb(context, FENBI_URL, "粉笔网站")
    }

    private fun openWeb(context: Context, address: String, name: String): String =
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(address))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            "已尝试打开" + name
        }.getOrElse { name + "跳转失败：" + it.javaClass.simpleName }
}
