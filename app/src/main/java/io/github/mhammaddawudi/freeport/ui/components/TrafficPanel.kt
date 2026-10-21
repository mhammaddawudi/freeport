package io.github.mhammaddawudi.freeport.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.mhammaddawudi.freeport.ui.TrafficUi
import io.github.mhammaddawudi.freeport.ui.theme.AetherColors
import io.github.mhammaddawudi.freeport.ui.theme.NumericStyle

/** Download, upload and ping on one line; the glyphs say which is which. */
@Composable
fun TrafficPanel(traffic: TrafficUi, pingMs: Long?, modifier: Modifier = Modifier) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (down, downUnit) = Formats.rate(traffic.downRate)
            val (up, upUnit) = Formats.rate(traffic.upRate)
            Stat(GlyphKind.Down, down, downUnit, AetherColors.Ink, AetherColors.Mint, Modifier.weight(1f))
            Separator()
            Stat(GlyphKind.Up, up, upUnit, AetherColors.Ink, AetherColors.Selected, Modifier.weight(1f))
            Separator()
            Stat(GlyphKind.Pulse, pingMs?.toString() ?: "—", "ms", pingTint(pingMs), pingTint(pingMs), Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(glyph: GlyphKind, value: String, unit: String, tint: Color, glyphTint: Color, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Glyph(glyph, glyphTint, Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(5.dp))
        Text(value, style = MaterialTheme.typography.titleSmall.merge(NumericStyle), color = tint, maxLines = 1)
        Spacer(Modifier.width(3.dp))
        Text(unit, style = MaterialTheme.typography.labelSmall, color = AetherColors.InkFaint, maxLines = 1)
    }
}

@Composable
private fun Separator() {
    Box(Modifier.width(1.dp).height(18.dp).background(AetherColors.Hairline))
}

@Composable
private fun pingTint(ping: Long?): Color = when {
    ping == null -> AetherColors.InkFaint
    ping < 150 -> AetherColors.Mint
    ping < 400 -> AetherColors.Amber
    else -> AetherColors.Rose
}
