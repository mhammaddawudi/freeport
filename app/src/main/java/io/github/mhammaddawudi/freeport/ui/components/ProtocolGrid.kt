package io.github.mhammaddawudi.freeport.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mhammaddawudi.freeport.R
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

/** Which protocol is on the wire right now, and whether it is carrying traffic yet. */
data class ProtocolActivity(
    val protocol: Protocol,
    val live: Boolean,
    val viaAuto: Boolean,
)

private val Protocol.traitRes: Int?
    get() = when (this) {
        Protocol.Auto -> null
        Protocol.MasqueH3 -> R.string.protocol_trait_masque
        Protocol.MasqueH2 -> R.string.protocol_trait_masque_h2
        Protocol.WireGuard -> R.string.protocol_trait_wireguard
        Protocol.Gool -> R.string.protocol_trait_gool
    }

private val Protocol.glyph: GlyphKind
    get() = when (this) {
        Protocol.Auto -> GlyphKind.Radar
        Protocol.MasqueH3 -> GlyphKind.Bolt
        Protocol.MasqueH2 -> GlyphKind.Stack
        Protocol.WireGuard -> GlyphKind.Wave
        Protocol.Gool -> GlyphKind.Nested
    }

/** The display order: the two MASQUE carriers first, warp in warp last. */
private val GRID_ORDER = listOf(Protocol.MasqueH3, Protocol.MasqueH2, Protocol.WireGuard, Protocol.Gool)

@Composable
fun ProtocolGrid(
    selected: Protocol,
    activity: ProtocolActivity?,
    onSelect: (Protocol) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val choose: (Protocol) -> Unit = { protocol ->
        if (protocol != selected) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onSelect(protocol)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Automatic gets the first row to itself: it is the one most people
        // should leave selected, and it reports what it picked.
        AutoCard(
            selected = selected == Protocol.Auto,
            activity = activity?.takeIf { selected == Protocol.Auto },
            onClick = { choose(Protocol.Auto) },
        )
        // One full-width row each: names like "Warp in Warp" and the apps
        // each protocol suits need the width a two-column grid cannot give.
        GRID_ORDER.forEach { protocol ->
            ProtocolCard(
                protocol = protocol,
                selected = protocol == selected,
                activity = activity?.takeIf { it.protocol == protocol },
                onClick = { choose(protocol) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AutoCard(
    selected: Boolean,
    activity: ProtocolActivity?,
    onClick: () -> Unit,
) {
    val lit by animateFloatAsState(if (selected) 1f else 0f, tween(280), label = "autoLit")
    val accent = Protocol.Auto.accent
    CardShell(lit = lit, accent = accent, onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(lerp(AetherColors.Ink.copy(alpha = 0.06f), accent.copy(alpha = 0.16f), lit)),
                contentAlignment = Alignment.Center,
            ) {
                Glyph(GlyphKind.Radar, lerp(AetherColors.InkMuted, accent, lit), Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.protocol_auto),
                    style = MaterialTheme.typography.titleMedium,
                    color = lerp(AetherColors.Ink, accent, lit),
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.protocol_auto_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherColors.InkMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SelectionMark(lit, accent)
        }
        // What automatic settled on, said on the card the person chose.
        val chosen = activity?.protocol?.takeIf { it != Protocol.Auto }
        AnimatedVisibility(
            visible = chosen != null,
            enter = fadeIn(tween(220)) + expandVertically(tween(220)),
            exit = fadeOut(tween(160)) + shrinkVertically(tween(160)),
        ) {
            val shown = chosen ?: return@AnimatedVisibility
            val live = activity?.live == true
            val tint = if (live) AetherColors.Mint else AetherColors.Amber
            Row(
                Modifier
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(tint.copy(alpha = 0.10f))
                    .border(1.dp, tint.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(tint, pulsing = live)
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(
                        if (live) R.string.protocol_now_using else R.string.protocol_trying,
                        shown.displayName,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = AetherColors.Ink,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AppBadgeRow(shown.suitedApps, size = 20.dp)
            }
        }
    }
}

@Composable
private fun ProtocolCard(
    protocol: Protocol,
    selected: Boolean,
    activity: ProtocolActivity?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = protocol.accent
    val lit by animateFloatAsState(if (selected) 1f else 0f, tween(280), label = "protocolLit")
    val apps = protocol.suitedApps
    val names = apps.map { it.label() }.joinToString(" · ")
    val bestFor = stringResource(R.string.protocol_best_for, names)
    val trait = protocol.traitRes?.let { stringResource(it) }.orEmpty()

    CardShell(
        lit = lit,
        accent = accent,
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = "${protocol.displayName}. $trait. $bestFor" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(lerp(AetherColors.Ink.copy(alpha = 0.06f), accent.copy(alpha = 0.16f), lit)),
                contentAlignment = Alignment.Center,
            ) {
                Glyph(protocol.glyph, lerp(AetherColors.InkMuted, accent, lit), Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        protocol.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = lerp(AetherColors.Ink, accent, lit),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (activity != null) {
                        Spacer(Modifier.width(6.dp))
                        StatusDot(if (activity.live) AetherColors.Mint else AetherColors.Amber, pulsing = activity.live)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    trait,
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherColors.InkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppBadgeRow(apps, size = 22.dp)
                    Spacer(Modifier.width(2.dp))
                    Text(
                        names,
                        style = MaterialTheme.typography.labelMedium,
                        color = AetherColors.InkFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            SelectionMark(lit, accent)
        }
    }
}

/** The surface every protocol card shares: tinted and outlined when chosen. */
@Composable
private fun CardShell(
    lit: Float,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        accent.copy(alpha = 0.02f + 0.16f * lit),
                        AetherColors.SurfaceRaised.copy(alpha = 0.85f),
                        AetherColors.Surface.copy(alpha = 0.75f),
                    ),
                ),
            )
            .border(
                (1f + 0.5f * lit).dp,
                Brush.linearGradient(
                    listOf(
                        lerp(AetherColors.Ink.copy(alpha = 0.12f), accent, lit),
                        lerp(AetherColors.Ink.copy(alpha = 0.04f), accent.copy(alpha = 0.45f), lit),
                    ),
                ),
                shape,
            )
            .selectable(selected = lit > 0.5f, role = Role.RadioButton, onClick = onClick)
            .padding(16.dp),
        content = content,
    )
}

/** A ring that fills when its card is the chosen one. */
@Composable
private fun SelectionMark(lit: Float, accent: Color) {
    Box(
        Modifier
            .size(20.dp)
            .clip(CircleShape)
            .border(1.5.dp, lerp(AetherColors.Ink.copy(alpha = 0.22f), accent, lit), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .alpha(lit)
                .clip(CircleShape)
                .background(accent),
        )
    }
}

/** Green and breathing when live, amber and still while the tunnel comes up. */
@Composable
fun StatusDot(colour: Color, pulsing: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "statusDot")
    val pulse by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1_400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "dotPulse",
    )
    val halo = if (pulsing) pulse else 0f
    Box(modifier.size(20.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size((10f + 8f * halo).dp)
                .clip(CircleShape)
                .background(colour.copy(alpha = 0.25f * (1f - halo) + 0.05f)),
        )
        Box(Modifier.size(8.dp).clip(CircleShape).background(colour))
    }
}
