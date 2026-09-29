package dev.jlz.presence.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The original entry point is kept for source/binary compatibility.
// Only presentation tokens change; no routing or server behavior changes.
private val ParchmentColorScheme = lightColorScheme(
    primary = ParchmentInk,
    secondary = ParchmentGold,
    background = ParchmentBackground,
    surface = ParchmentPaper,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    onSecondary = ParchmentCarbon,
    onBackground = ParchmentInk,
    onSurface = ParchmentInk,
    error = Danger
)

@Composable
fun IceCrystalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ParchmentColorScheme,
        shapes = MaterialTheme.shapes.copy(
            small = RoundedCornerShape(16.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(28.dp)
        ),
        // Slightly smaller typography preserves the existing romantic palette.
        // Keep system font scaling enabled for accessibility.
        typography = MaterialTheme.typography.copy(
            // English serif titles / dates / nicknames / card headings.
            headlineLarge = TextStyle(fontSize = 26.sp, fontFamily = WorldFonts.playfairDisplay, fontWeight = FontWeight.Medium),
            headlineMedium = TextStyle(fontSize = 23.sp, fontFamily = WorldFonts.playfairDisplay, fontWeight = FontWeight.Medium),
            headlineSmall = TextStyle(fontSize = 20.sp, fontFamily = WorldFonts.playfairDisplay, fontWeight = FontWeight.Medium),
            titleLarge = TextStyle(fontSize = 18.sp, fontFamily = WorldFonts.playfairDisplay, fontWeight = FontWeight.Medium),
            titleMedium = TextStyle(fontSize = 15.sp, fontFamily = WorldFonts.playfairDisplay, fontWeight = FontWeight.Medium),
            titleSmall = TextStyle(fontSize = 13.sp, fontFamily = WorldFonts.playfairDisplay, fontWeight = FontWeight.Medium),
            // Chat bubbles, buttons, settings, captions and timestamps use Inter.
            bodyLarge = TextStyle(fontSize = 14.sp, fontFamily = WorldFonts.inter, fontWeight = FontWeight.Normal),
            bodyMedium = TextStyle(fontSize = 12.sp, fontFamily = WorldFonts.inter, fontWeight = FontWeight.Normal),
            bodySmall = TextStyle(fontSize = 12.sp, fontFamily = WorldFonts.inter, fontWeight = FontWeight.Normal),
            labelLarge = TextStyle(fontSize = 13.sp, fontFamily = WorldFonts.inter, fontWeight = FontWeight.Medium),
            labelMedium = TextStyle(fontSize = 12.sp, fontFamily = WorldFonts.inter, fontWeight = FontWeight.Normal),
            labelSmall = TextStyle(fontSize = 11.sp, fontFamily = WorldFonts.inter, fontWeight = FontWeight.Normal)
        ),
        content = content
    )
}
