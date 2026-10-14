package dev.jlz.presence.overlay

import android.content.Context

/** Per-device appearance only; never synced through Runtime or multiplied by window alpha. */
object QOverlayPreferences {
    const val MIN_OPACITY = 25
    const val DEFAULT_OPACITY = 100
    fun clamp(value: Int) = value.coerceIn(MIN_OPACITY, 100)
    fun alpha(value: Int) = clamp(value) / 100f
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("jlz_q_overlay_preferences", Context.MODE_PRIVATE)
    fun opacity(context: Context) = clamp(prefs(context).getInt("opacity_percent", DEFAULT_OPACITY))
    fun setOpacity(context: Context, value: Int): Int = clamp(value).also {
        prefs(context).edit().putInt("opacity_percent", it).apply()
    }
}
