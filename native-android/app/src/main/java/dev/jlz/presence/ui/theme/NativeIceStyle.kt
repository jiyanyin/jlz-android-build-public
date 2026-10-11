package dev.jlz.presence.ui.theme

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.widget.Button

/** Native equivalent of the existing WebShell mist/deepsea/rose/gothic palette. */
class NativeIceStyle(private val context: Context) {
    private val theme = dev.jlz.presence.runtime.WorldContentCache(context).text("theme.id", "mist")
    val dark = theme in setOf("deepsea", "gothic") ||
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
    val ink = if (dark) 0xFFF7F8FF.toInt() else 0xFF344463.toInt()
    val muted = if (dark) 0xFFB9C5DA.toInt() else 0xFF596783.toInt()
    val accent = if (dark) 0xFFD9BD8F.toInt() else 0xFF76678D.toInt()
    val fill = if (dark) 0xD92A3551.toInt() else 0xCFFFFFFF.toInt()
    val border = if (dark) 0xFF667490.toInt() else 0xFFFDFEFF.toInt()
    fun dp(n: Int) = (n * context.resources.displayMetrics.density + .5f).toInt()
    val titleFont: Typeface by lazy {
        // Use a packaged font if present; no runtime font download.
        listOf("world-between/fonts/LXGWWenKai-Regular.ttf", "fonts/LXGWWenKai-Regular.ttf")
            .firstNotNullOfOrNull { runCatching { Typeface.createFromAsset(context.assets, it) }.getOrNull() }
            ?: Typeface.create("serif", Typeface.NORMAL)
    }
    fun background() = GradientDrawable(GradientDrawable.Orientation.TL_BR,
        if (dark) intArrayOf(0xFF101A36.toInt(), 0xFF282D46.toInt())
        else if (theme == "rose") intArrayOf(0xFFF4E5EA.toInt(), 0xFFEBE8F3.toInt())
        else intArrayOf(0xFFE2EBF7.toInt(), 0xFFF0E8F4.toInt()))
    fun card() = GradientDrawable().apply { setColor(fill); cornerRadius = dp(20).toFloat(); setStroke(dp(1), border) }
    fun button(title: String, action: () -> Unit) = Button(context).apply {
        text = title; textSize = 13f; isAllCaps = false; setTextColor(ink)
        gravity = Gravity.CENTER; minHeight = dp(48); minimumHeight = dp(48)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        backgroundTintList = null
        background = RippleDrawable(ColorStateList.valueOf(if (dark) 0x4078B8FF else 0x308D7BFF), card(), null)
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(.98f).scaleY(.98f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
        setOnClickListener { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); action() }
    }
}
