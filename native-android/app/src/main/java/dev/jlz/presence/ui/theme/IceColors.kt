package dev.jlz.presence.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 世界之间 · 羊皮纸童话主题 V1.
 * Keep the original Ice* aliases while migrating the View layer so existing
 * presenters, repositories, device services and runtime protocols stay intact.
 */
val ParchmentBackground = Color(0xFFF9F1F1)
val ParchmentPaper = Color(0xFFFDF8F8)
val ParchmentGlass = Color(0xB3FFFFFF) // white 70%
val ParchmentInk = Color(0xFF8C5D5D)
val ParchmentMuted = Color(0xFFB89B9B)
val ParchmentCarbon = Color(0xFF1A1A1A)
val ParchmentGold = Color(0xFFA68A56)
val ParchmentMineBubble = Color(0xFFFADADD)
val ParchmentCompanionBubble = Color(0xCCFFFFFF) // white 80%
val ParchmentRoseShadow = Color(0x148C5D5D) // 8% rosy shadow

// Compatibility names: these are only visual aliases, not data migrations.
val BgDeep = ParchmentBackground
val BgBlue = ParchmentPaper
val VioletGlow = ParchmentInk
val BlueGlow = ParchmentGold
val GlassFill = ParchmentGlass
val GlassFillPressed = Color(0xD9FADADD)
val GlassBorder = Color(0x4DA68A56)
val GlassHighlight = Color(0xEBFFFFFF)
val TextPrimary = ParchmentInk
val TextSecondary = ParchmentMuted
val TextTertiary = Color(0xFFBEA7A7)
val Danger = Color(0xFFAE6570)
val Success = Color(0xFF668975)
val Warning = Color(0xFFA5824E)
