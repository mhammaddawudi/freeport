package io.github.mhammaddawudi.freeport.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mhammaddawudi.freeport.data.Protocol
import io.github.mhammaddawudi.freeport.ui.theme.AetherColors

/**
 * One accent for every transport.
 *
 * Giving each its own colour meant the chosen card looked different depending
 * on which one it was, and the pale ones barely read as chosen. A single
 * purple says "this is the selected one" wherever it lands.
 */
val Protocol.accent: Color
    @Composable get() = AetherColors.Selected

@Composable
fun ProtocolGrid(
    selected: Protocol,
    onSelect: (Protocol) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Protocol.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { protocol ->
                    ProtocolCard(
                        protocol = protocol,
                        selected = protocol == selected,
                        onClick = { onSelect(protocol) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProtocolCard(
    protocol: Protocol,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = protocol.accent
    val lit by animateFloatAsState(if (selected) 1f else 0f, tween(280), label = "protocolLit")
    val shape = RoundedCornerShape(22.dp)

    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        accent.copy(alpha = 0.02f + 0.18f * lit),
                        AetherColors.Surface.copy(alpha = 0.7f),
                        AetherColors.Surface.copy(alpha = 0.55f),
                    ),
                ),
            )
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        lerpColor(AetherColors.Ink.copy(alpha = 0.10f), accent, lit),
                        lerpColor(AetherColors.Ink.copy(alpha = 0.03f), accent.copy(alpha = 0.55f), lit),
                    ),
                ),
                shape,
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 18.dp),
    ) {
        // Name only: no glyph, no radio dot. The accent border and tint carry
        // the selection, which is all the card has to say.
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                protocol.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = lerpColor(AetherColors.InkMuted, accent, lit),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun lerpColor(start: Color, stop: Color, fraction: Float): Color =
    androidx.compose.ui.graphics.lerp(start, stop, fraction.coerceIn(0f, 1f))
