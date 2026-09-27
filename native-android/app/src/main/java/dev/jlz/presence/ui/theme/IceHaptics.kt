package dev.jlz.presence.ui.theme

import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView

object IceHaptics {
    @Composable
    fun rememberHaptic(): (Int) -> Unit {
        val view = LocalView.current
        return { type ->
            view.performHapticFeedback(
                when (type) {
                    TYPE_CLICK -> HapticFeedbackConstants.CONTEXT_CLICK
                    TYPE_HEAVY -> HapticFeedbackConstants.LONG_PRESS
                    TYPE_LONG -> HapticFeedbackConstants.LONG_PRESS
                    else -> HapticFeedbackConstants.KEYBOARD_TAP
                }
            )
        }
    }
    const val TYPE_TICK = 0
    const val TYPE_CLICK = 1
    const val TYPE_HEAVY = 2
    const val TYPE_LONG = 3
}
