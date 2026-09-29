package dev.jlz.presence.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import dev.jlz.presence.ui.theme.*

/** Same click and enabled contracts, but styled as parchment-glass controls. */
@Composable
fun IceButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false
) {
    val clickHaptic = IceHaptics.rememberHaptic()
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.965f else 1f,
        animationSpec = tween(120), label = "btnScale"
    )
    val shape = RoundedCornerShape(24.dp)
    val bg = when {
        !enabled -> GlassFill.copy(alpha = 0.35f)
        pressed -> GlassFillPressed
        primary -> ParchmentMineBubble
        else -> GlassFill
    }
    Box(
        modifier = modifier
            .scale(scale)
            .height(48.dp)
            .background(bg, shape)
            .border(0.5.dp, GlassBorder, shape)
            .clickable(interactionSource = interactionSource, indication = null, enabled = enabled) {
                clickHaptic(IceHaptics.TYPE_TICK)
                onClick()
            }
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) TextPrimary else TextTertiary,
            style = androidx.compose.material3.MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
fun IceGlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ParchmentGlass, shape)
            .border(0.5.dp, GlassBorder, shape)
            .padding(18.dp),
        content = content
    )
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        color = TextSecondary,
        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
        modifier = modifier.padding(vertical = 8.dp)
    )
}
