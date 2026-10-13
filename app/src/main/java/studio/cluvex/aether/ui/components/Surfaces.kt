package studio.cluvex.aether.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.ui.theme.AetherColors

/**
 * A translucent panel with a hairline edge that catches the light at the top
 * left. [accent] tints that edge, for selected or live content.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    shape: Shape = RoundedCornerShape(24.dp),
    contentPadding: PaddingValues = PaddingValues(18.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val edge = if (accent != null) {
        Brush.linearGradient(listOf(accent.copy(alpha = 0.65f), accent.copy(alpha = 0.08f), AetherColors.Ink.copy(alpha = 0.05f)))
    } else {
        Brush.linearGradient(listOf(AetherColors.Ink.copy(alpha = 0.14f), AetherColors.Ink.copy(alpha = 0.03f)))
    }
    Column(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(AetherColors.SurfaceRaised, AetherColors.Surface),
                ),
            )
            .border(1.5.dp, edge, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(
        modifier = modifier.padding(start = 6.dp, end = 6.dp, top = 30.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = AetherColors.InkFaint)
        Spacer(Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelMedium, color = AetherColors.InkFaint)
        }
    }
}

/**
 * The backdrop: three soft halos in the app's own accents.
 *
 * Painted once and left alone. An earlier version animated them, which meant
 * a full-screen repaint every frame behind everything else.
 */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier) {
    val ground = AetherColors.Void
    // Three faint washes of the same light the mark is made of; any hue here
    // would be the only colour in the app and would read as a mistake.
    val topAlpha = 0.07f
    val middleAlpha = 0.05f
    val bottomAlpha = 0.06f
    val top = AetherColors.Ink
    val middle = AetherColors.Ink
    val bottom = AetherColors.Ink

    Canvas(modifier.fillMaxSize().background(ground)) {
        val w = size.width
        val h = size.height
        val a = Offset(w * 0.18f, h * 0.04f)
        val b = Offset(w * 0.86f, h * 0.40f)
        val c = Offset(w * 0.40f, h * 1.00f)
        drawCircle(Brush.radialGradient(listOf(top.copy(alpha = topAlpha), Color.Transparent), a, w * 1.0f), w * 1.0f, a)
        drawCircle(Brush.radialGradient(listOf(middle.copy(alpha = middleAlpha), Color.Transparent), b, w * 0.8f), w * 0.8f, b)
        drawCircle(Brush.radialGradient(listOf(bottom.copy(alpha = bottomAlpha), Color.Transparent), c, w * 1.0f), w * 1.0f, c)
    }
}
