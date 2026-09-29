package dev.jlz.presence.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.jlz.presence.R

/**
 * Font resources are downloaded by the pinned Gradle prepareWorldFonts task.
 * They are embedded in the APK, so displayed text never needs a network request.
 */
object WorldFonts {
    val playfairDisplay = FontFamily(Font(R.font.playfair_display, weight = FontWeight.Normal))
    val greatVibes = FontFamily(Font(R.font.great_vibes, weight = FontWeight.Normal))
    val inter = FontFamily(Font(R.font.inter, weight = FontWeight.Normal))
    // Noto Serif SC and Source Han Serif use the same Pan-CJK design lineage.
    val chineseSerif = FontFamily(Font(R.font.noto_serif_sc, weight = FontWeight.Normal))
}
