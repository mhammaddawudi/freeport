package io.github.mhammaddawudi.freeport.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import io.github.mhammaddawudi.freeport.ui.theme.AetherColors
import java.util.Locale

/**
 * A raised glass panel.
 *
 * Three layers make it read as an object rather than a box: a soft shadow
 * underneath, a body that is a shade lighter at the top, and a hairline edge
 * that is brightest along the top where the light would catch it. [accent]
 * tints that edge and adds a faint wash, for live or selected content.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    shape: Shape = RoundedCornerShape(26.dp),
    contentPadding: PaddingValues = PaddingValues(18.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val edge = if (accent != null) {
        Brush.verticalGradient(listOf(accent.copy(alpha = 0.70f), accent.copy(alpha = 0.18f), accent.copy(alpha = 0.06f)))
    } else {
        Brush.verticalGradient(
            listOf(AetherColors.Ink.copy(alpha = 0.16f), AetherColors.Ink.copy(alpha = 0.05f), AetherColors.Ink.copy(alpha = 0.03f)),
        )
    }
    val wash = accent?.copy(alpha = 0.07f) ?: Color.Transparent
    Column(
        modifier = modifier
            .shadow(18.dp, shape, ambientColor = Color.Black, spotColor = Color.Black.copy(alpha = 0.6f))
            .clip(shape)
            .background(Brush.verticalGradient(listOf(AetherColors.SurfaceRaised, AetherColors.Surface)))
            .background(Brush.verticalGradient(listOf(wash, Color.Transparent)))
            .border(1.dp, edge, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(
        modifier = modifier.padding(start = 6.dp, end = 6.dp, top = 28.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 3.dp, height = 12.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(AetherColors.Ink.copy(alpha = 0.35f)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text.uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.labelSmall,
            color = AetherColors.InkMuted,
        )
        Spacer(Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelMedium, color = AetherColors.InkFaint)
        }
    }
}

/** A small rounded label with a dot, for states and facts. */
@Composable
fun StatusPill(text: String, tint: Color, modifier: Modifier = Modifier, pulsing: Boolean = false) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .clip(shape)
            .background(tint.copy(alpha = 0.12f))
            .border(1.dp, tint.copy(alpha = 0.30f), shape)
            .padding(start = 6.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(tint, pulsing = pulsing)
        Spacer(Modifier.width(2.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = AetherColors.Ink, maxLines = 1)
    }
}

/**
 * The backdrop: a dark ground, a pool of light behind the connect button
 * that takes the colour of the connection, and a vignette at the edges.
 *
 * Nothing loops here. The colour eases over once per state change and the
 * canvas is otherwise still, so it costs nothing while the screen sits idle.
 */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier, glow: Color = AetherColors.Ink) {
    val ground = AetherColors.Void
    val light by animateColorAsState(glow, tween(900), label = "auroraGlow")
    val ink = AetherColors.Ink

    Canvas(modifier.fillMaxSize().background(ground)) {
        val w = size.width
        val h = size.height
        val stage = Offset(w * 0.5f, h * 0.24f)
        drawCircle(
            Brush.radialGradient(
                listOf(light.copy(alpha = 0.16f), light.copy(alpha = 0.05f), Color.Transparent),
                stage,
                w * 0.95f,
            ),
            w * 0.95f,
            stage,
        )
        val corner = Offset(w * 0.08f, h * 0.02f)
        drawCircle(Brush.radialGradient(listOf(ink.copy(alpha = 0.06f), Color.Transparent), corner, w * 0.7f), w * 0.7f, corner)
        val floor = Offset(w * 0.75f, h * 1.02f)
        drawCircle(Brush.radialGradient(listOf(ink.copy(alpha = 0.04f), Color.Transparent), floor, w * 0.9f), w * 0.9f, floor)
        // Darkens toward the bottom, so the floating bar sits on shade.
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, ground.copy(alpha = 0.85f)), startY = h * 0.72f, endY = h))
    }
}

/** A thin bright line along the top edge, for surfaces that need a lip of light. */
fun Modifier.topHighlight(alpha: Float = 0.10f): Modifier = drawWithContent {
    drawContent()
    drawLine(
        Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = alpha), Color.Transparent)),
        Offset(size.width * 0.1f, 0.5f),
        Offset(size.width * 0.9f, 0.5f),
        strokeWidth = 1f,
    )
}

@Composable
fun CircleIconTile(
    modifier: Modifier = Modifier,
    tint: Color = AetherColors.Ink,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.08f))
            .border(1.dp, tint.copy(alpha = 0.14f), CircleShape),
        contentAlignment = Alignment.Center,
    ) { content() }
}
