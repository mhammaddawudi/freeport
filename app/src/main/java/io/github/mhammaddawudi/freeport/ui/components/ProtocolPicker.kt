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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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

/** The colour that marks the chosen protocol. */
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

@Composable
private fun Protocol.label(): String =
    if (this == Protocol.Auto) stringResource(R.string.protocol_auto) else displayName

/** Automatic first, then the two MASQUE carriers, warp in warp last. */
private val ORDER = listOf(Protocol.Auto, Protocol.MasqueH3, Protocol.MasqueH2, Protocol.WireGuard, Protocol.Gool)

/**
 * The protocol choice as one folding row.
 *
 * Closed, it shows only what is chosen — and, under Automatic, what it
 * picked. Open, it lists every protocol with the apps each suits; choosing
 * one folds it shut again, so the main screen stays short.
 */
@Composable
fun ProtocolPicker(
    selected: Protocol,
    activity: ProtocolActivity?,
    onSelect: (Protocol) -> Unit,
    modifier: Modifier = Modifier,
    startOpen: Boolean = false,
) {
    var open by rememberSaveable { mutableStateOf(startOpen) }
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(240), label = "pickerChevron")
    val haptics = LocalHapticFeedback.current

    GlassCard(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .clickable(role = Role.DropdownList) { open = !open }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphTile(selected.glyph, AetherColors.Selected, lit = 1f)
            Spacer(Modifier.width(12.dp))
            Text(
                selected.label(),
                style = MaterialTheme.typography.titleMedium,
                color = AetherColors.Ink,
                maxLines = 1,
            )
            // Under Automatic, the protocol it settled on, by name only.
            val picked = activity?.protocol?.takeIf { selected == Protocol.Auto && it != Protocol.Auto }
            if (picked != null) {
                Spacer(Modifier.width(8.dp))
                Text("·", style = MaterialTheme.typography.titleMedium, color = AetherColors.InkFaint)
                Spacer(Modifier.width(8.dp))
                Text(
                    picked.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = AetherColors.InkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (activity != null) {
                Spacer(Modifier.width(6.dp))
                StatusDot(if (activity.live) AetherColors.Mint else AetherColors.Amber, pulsing = activity.live)
            }
            Spacer(Modifier.weight(1f))
            Glyph(GlyphKind.Chevron, AetherColors.InkMuted, Modifier.size(18.dp).rotate(90f + turn), strokeWidth = 2.dp)
        }

        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(200)) + expandVertically(tween(240)),
            exit = fadeOut(tween(140)) + shrinkVertically(tween(200)),
        ) {
            Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                RowDivider(Modifier.padding(horizontal = 8.dp).padding(bottom = 6.dp))
                ORDER.forEach { protocol ->
                    ProtocolRow(
                        protocol = protocol,
                        selected = protocol == selected,
                        live = activity?.takeIf { it.protocol == protocol },
                        onClick = {
                            if (protocol != selected) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(protocol)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProtocolRow(
    protocol: Protocol,
    selected: Boolean,
    live: ProtocolActivity?,
    onClick: () -> Unit,
) {
    val lit by animateFloatAsState(if (selected) 1f else 0f, tween(220), label = "rowLit")
    val accent = protocol.accent
    val apps = protocol.suitedApps
    val described = listOfNotNull(
        protocol.label(),
        protocol.traitRes?.let { stringResource(it) },
        apps.takeIf { it.isNotEmpty() }?.let { list -> stringResource(R.string.protocol_best_for, list.map { it.label() }.joinToString(", ")) },
    ).joinToString(". ")

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(accent.copy(alpha = 0.10f * lit))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = described }
            .heightIn(min = 52.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphTile(protocol.glyph, accent, lit)
        Spacer(Modifier.width(12.dp))
        // The name and its dot take the free width as one block, so the
        // badges and the selection ring end up at the same edge on every row.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                protocol.label(),
                style = MaterialTheme.typography.titleSmall,
                color = lerp(AetherColors.Ink, accent, lit),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (live != null) {
                Spacer(Modifier.width(6.dp))
                StatusDot(if (live.live) AetherColors.Mint else AetherColors.Amber, pulsing = live.live)
            }
        }
        if (apps.isNotEmpty()) {
            AppBadgeRow(apps, size = 22.dp)
            Spacer(Modifier.width(12.dp))
        }
        SelectionMark(lit, accent)
    }
}

@Composable
private fun GlyphTile(glyph: GlyphKind, accent: Color, lit: Float) {
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(lerp(AetherColors.Ink.copy(alpha = 0.06f), accent.copy(alpha = 0.16f), lit)),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(glyph, lerp(AetherColors.InkMuted, accent, lit), Modifier.size(17.dp), strokeWidth = 2.dp)
    }
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
