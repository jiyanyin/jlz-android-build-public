package dev.jlz.presence.overlay

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Visual, local-only Q-avatar wardrobe. Character assets remain APK-private.
 * Browsing a skin is only a preview; "穿上这套" commits the change.
 * The floating avatar keeps exactly the three existing action buttons.
 */
class QAvatarWardrobeActivity : Activity() {
    private val night = 0xFF101B34.toInt()
    private val blue = 0xFF213354.toInt()
    private val porcelain = 0xFFF8F2E8.toInt()
    private val gold = 0xFFD9BD8F.toInt()
    private val muted = 0xFFB9BED1.toInt()
    private val rose = 0xFFD4B4C9.toInt()
    private var previewId: String? = null

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density + 0.5f).toInt()
    private fun rounded(color: Int, radius: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun text(value: String, size: Float, color: Int = porcelain, bold: Boolean = false) =
        TextView(this).apply {
            this.text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun button(title: String, primary: Boolean = false, action: () -> Unit): Button =
        Button(this).apply {
            text = title
            textSize = 13f
            isAllCaps = false
            setTextColor(if (primary) night else porcelain)
            background = rounded(if (primary) rose else blue, 15, if (primary) gold else 0xFF536485.toInt())
            minHeight = dp(52)
            setOnClickListener { action() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previewId = savedInstanceState?.getString("preview_id")
            ?: QAvatarAssetImporter.activePackId(this)
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("preview_id", previewId)
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        val packs = QAvatarAssetImporter.listPacks(this).sortedWith(
            compareBy<QAvatarAssetImporter.PackInfo> {
                when (it.id) {
                    "builtin-cat_lavender" -> 0
                    "builtin-black_gothic" -> 1
                    "builtin-white_winter" -> 2
                    "builtin-purple_coat" -> 3
                    else -> 4
                }
            }.thenBy { it.label }
        )
        val active = QAvatarAssetImporter.activePackId(this)
        if (previewId !in packs.map { it.id }) previewId = active
        val pending = previewId ?: active
        val current = packs.firstOrNull { it.id == pending }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(25), dp(17), dp(22))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(night, blue, 0xFF31334D.toInt())
            )
        }
        val scrolling = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrolling.addView(content)
        root.addView(scrolling, LinearLayout.LayoutParams(-1, 0, 1f))

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(text("✦ 纪临洲 · Q版衣橱", 22f, porcelain, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        titleRow.addView(text("PRIVATE", 9f, muted))
        content.addView(titleRow)
        content.addView(text("四套衣服，同一个纪临洲。先试穿，再确定。", 12f, muted).apply {
            setPadding(0, dp(9), 0, dp(17))
        })

        val preview = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(15), dp(14), dp(15), dp(14))
            background = rounded(0xFF26385D.toInt(), 24, 0xFFBBA789.toInt())
        }
        val star = text("✧  OUTFIT PREVIEW  ✧", 11f, gold)
        star.gravity = Gravity.CENTER
        preview.addView(star)
        val portrait = ChibiPresenceView(this).apply {
            setSizeDp(126)
            setMood("idle")
            previewArtworkPack(pending)
        }
        preview.addView(portrait, LinearLayout.LayoutParams(dp(126), dp(126)).apply { gravity = Gravity.CENTER })
        preview.addView(text(current?.label ?: "当前造型", 18f, porcelain, true).apply {
            gravity = Gravity.CENTER
        })
        preview.addView(text(if (pending == active) "正在穿着" else "试穿中 · 尚未保存", 11f, if (pending == active) gold else muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, 0)
        })
        content.addView(preview)

        content.addView(text("SELECT YOUR LOOK", 10f, muted).apply {
            letterSpacing = 0.13f
            setPadding(dp(2), dp(18), 0, dp(7))
        })

        if (packs.isEmpty()) {
            content.addView(text("未找到可用造型。请确认已安装含私人 Q 版资源的完整 APK。", 13f))
        } else {
            for (start in packs.indices step 2) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for (i in start until minOf(start + 2, packs.size)) {
                    val pack = packs[i]
                    val selected = pack.id == pending
                    val worn = pack.id == active
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(dp(9), dp(11), dp(9), dp(12))
                        background = rounded(
                            if (selected) 0xFF455278.toInt() else 0xFF243350.toInt(),
                            19, if (selected) gold else 0xFF516184.toInt()
                        )
                        isClickable = true
                        isFocusable = true
                        contentDescription = "预览${pack.label}" + if (worn) "，当前造型" else ""
                        setOnClickListener { previewId = pack.id; render() }
                    }
                    val pose = ChibiPresenceView(this).apply {
                        setSizeDp(83)
                        setMood("idle")
                        previewArtworkPack(pack.id)
                    }
                    card.addView(pose, LinearLayout.LayoutParams(dp(83), dp(83)))
                    card.addView(text(pack.label, 13f, porcelain, selected).apply {
                        gravity = Gravity.CENTER
                        maxLines = 1
                    })
                    card.addView(text(if (worn) "✓ 当前" else if (selected) "✦ 试穿中" else "点击试穿", 10f, if (worn || selected) gold else muted).apply {
                        gravity = Gravity.CENTER
                        setPadding(0, dp(4), 0, 0)
                    })
                    row.addView(card, LinearLayout.LayoutParams(0, dp(151), 1f).apply {
                        rightMargin = if (i == start) dp(6) else 0
                        leftMargin = if (i == start) 0 else dp(6)
                    })
                }
                content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(11) })
            }
        }

        content.addView(text("只改变造型，不影响专注、来电、睡眠与 Q 版状态动作。你选中的衣服会在下次启动后保留。", 11f, muted).apply {
            setPadding(dp(3), dp(4), dp(3), dp(13))
        })
        val apply = button(if (pending == active) "这套已经穿好了" else "穿上这套  ✦", primary = true) {
            if (QAvatarAssetImporter.selectPack(this, pending)) {
                FloatingPresenceService.refreshArtworkAfterImport()
                Toast.makeText(this, "已经换好啦", Toast.LENGTH_SHORT).show()
                render()
            } else Toast.makeText(this, "切换失败，请重新选择", Toast.LENGTH_LONG).show()
        }
        apply.isEnabled = packs.any { it.id == pending } && pending != active
        root.addView(apply, LinearLayout.LayoutParams(-1, dp(51)).apply { bottomMargin = dp(9) })
        root.addView(button("返回世界之间") { finish() }, LinearLayout.LayoutParams(-1, dp(47)))
        setContentView(root)
    }
}
