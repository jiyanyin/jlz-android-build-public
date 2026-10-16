package dev.jlz.presence.ui

import android.app.Activity
import android.app.AlertDialog
import android.widget.LinearLayout
import android.widget.TextView

data class AutomaticCaptureAppChoice(val packageName: String, val label: String)

/** Keep the native scrollable list in the content area: setMessage hides that list. */
object AutomaticCaptureAppDialog {
    fun show(
        activity: Activity,
        choices: List<AutomaticCaptureAppChoice>,
        selected: Set<String>,
        onSave: (Set<String>) -> Boolean,
    ): AlertDialog {
        val checked = BooleanArray(choices.size) { choices[it].packageName in selected }
        fun packages() = choices.indices.filter { checked[it] }.map { choices[it].packageName }.toSet()
        val padding = (20 * activity.resources.displayMetrics.density).toInt()
        val count = TextView(activity).apply { textSize = 14f }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding / 2)
            addView(TextView(activity).apply {
                text = "允许我在哪些 App 截图？"
                textSize = 20f
            })
            addView(TextView(activity).apply {
                text = "仅截图勾选的 App，存入手机和私人 Runtime，供 GPT 读取。敏感页不拍，可随时暂停。"
                textSize = 14f
            })
            addView(count)
        }
        val dialog = AlertDialog.Builder(activity)
            .setCustomTitle(header)
            .setMultiChoiceItems(choices.map { it.label }.toTypedArray(), checked) { dialogInterface, index, value ->
                checked[index] = value
                count.text = "已选择 ${packages().size} 个应用"
                (dialogInterface as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = checked.any { it }
            }
            .setPositiveButton("保存并开启", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        count.text = "已选择 ${packages().size} 个应用"
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            isEnabled = checked.any { it }
            setOnClickListener {
                val requested = packages()
                if (requested.isNotEmpty() && onSave(requested)) dialog.dismiss()
            }
        }
        return dialog
    }
}
