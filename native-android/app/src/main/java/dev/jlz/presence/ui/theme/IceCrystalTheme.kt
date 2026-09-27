package dev.jlz.presence.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val IceColorScheme = darkColorScheme(
    primary = VioletGlow,
    secondary = BlueGlow,
    background = BgDeep,
    surface = BgBlue,
    onPrimary = TextPrimary,
    onSecondary = TextPrimary,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    error = Danger
)

@Composable
fun IceCrystalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = IceColorScheme,
        typography = MaterialTheme.typography.copy(
            headlineLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold),
            headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
            titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium),
            titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
            bodyLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal),
            bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal),
            labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
            labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal)
        ),
        content = content
    )
}
