package dev.jlz.presence.overlay

import dev.jlz.presence.persona.JlzVoiceMood

/** Optional companion appearance only. Safety and explicit modes always win. */
internal object QAvatarVoicePresentation {
    fun idleSpriteMood(mood: JlzVoiceMood): String = when(mood) {
        JlzVoiceMood.LONGING -> "clingy"
        JlzVoiceMood.PROUD -> "proud"
        JlzVoiceMood.CONCERNED -> "thinking"
        JlzVoiceMood.FOCUSED -> "watch"
        JlzVoiceMood.QUIET -> "sleepy"
        JlzVoiceMood.STEADY -> "idle"
    }
}
