package dev.jlz.presence.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
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
        typography = MaterialTheme.typography.copy(
            headlineLarge = TextStyle(fontSize = 30.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium),
            headlineMedium = TextStyle(fontSize = 26.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium),
            headlineSmall = TextStyle(fontSize = 22.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium),
            titleLarge = TextStyle(fontSize = 20.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium),
            titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
            bodyLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal),
            bodyMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal),
            labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
            labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal)
        ),
        content = content
    )
}
