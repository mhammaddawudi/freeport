package io.github.mhammaddawudi.freeport.ui.components

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.rotate
import android.graphics.Paint
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mhammaddawudi.freeport.R
import io.github.mhammaddawudi.freeport.core.ConnectionState
import io.github.mhammaddawudi.freeport.data.ExitCountries
import io.github.mhammaddawudi.freeport.ui.theme.WarpColors
import java.util.Locale

/**
 * Where the session comes out, and the switch that moves it abroad.
 *
 * Cloudflare places every WARP exit in the phone's own country, so on an
 * Iranian line ChatGPT, Supercell games and licensed Instagram audio refuse
 * whatever protocol is chosen. The card says so plainly, with the switch
 * that fixes it right beside the explanation.
 */
@Composable
fun RegionCard(
    state: ConnectionState,
    regionUnlock: Boolean,
    exitCountry: String,
    onToggle: (Boolean) -> Unit,
    onSelectCountry: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** False for Psiphon and Tor, which already leave from abroad. */
    enabled: Boolean = true,
) {
    val connected = state as? ConnectionState.Connected
    val exit = connected?.exitCountry
    val relaying = (state as? ConnectionState.Connecting)?.relaying == true

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        accent = if (connected?.relayed == true && exit != null) WarpColors.Mint else null,
        contentPadding = PaddingValues(start = 14.dp, end = 18.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(WarpColors.Ink.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center,
            ) {
                if (relaying) {
                    StatusDot(WarpColors.Amber, pulsing = true)
                } else {
                    // Where it leaves from once known; until then, where it was asked to.
                    val shown = exit ?: exitCountry.takeIf { regionUnlock && enabled }.orEmpty()
                    CountryFlag(shown, emojiSize = 19.sp, width = 22.dp)
                }
            }
            Spacer(Modifier.width(12.dp))
            // The flag says where the session leaves from; the switch moves it.
            SwitchRow(
                title = stringResource(R.string.region_unlock),
                subtitle = if (enabled) exit?.let { countryName(it) } else stringResource(R.string.region_unlock_not_needed),
                checked = regionUnlock && enabled,
                enabled = enabled,
                onCheckedChange = onToggle,
                accent = WarpColors.Mint,
                modifier = Modifier.weight(1f),
            )
        }

        AnimatedVisibility(
            visible = regionUnlock && enabled,
            enter = fadeIn(tween(220)) + expandVertically(tween(220)),
            exit = fadeOut(tween(160)) + shrinkVertically(tween(160)),
        ) {
            CountryPicker(exitCountry, onSelectCountry)
        }
    }
}

/** The chosen exit on one row; tapped, the other countries open beneath it, one per row. */
@Composable
private fun CountryPicker(exitCountry: String, onSelectCountry: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val turn by animateFloatAsState(if (open) -90f else 90f, label = "chevron")
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clip(shape)
                .border(1.dp, if (open) WarpColors.Mint.copy(alpha = 0.6f) else WarpColors.Hairline, shape)
                .clickable(role = Role.DropdownList) { open = !open }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CountryLabel(exitCountry, WarpColors.Ink, Modifier.weight(1f))
            Glyph(GlyphKind.Chevron, WarpColors.InkMuted, Modifier.size(16.dp).rotate(turn), strokeWidth = 1.8.dp)
        }
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(180)) + expandVertically(tween(180)),
            exit = fadeOut(tween(140)) + shrinkVertically(tween(140)),
        ) {
            Column(
                Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .heightIn(max = 264.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, WarpColors.Hairline, RoundedCornerShape(14.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(4.dp),
            ) {
                ExitCountries.offered.forEach { code ->
                    val selected = code == exitCountry
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 42.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) WarpColors.Mint.copy(alpha = 0.12f) else Color.Transparent)
                            .selectable(selected = selected, role = Role.RadioButton) {
                                open = false
                                onSelectCountry(code)
                            }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CountryLabel(code, if (selected) WarpColors.Ink else WarpColors.InkMuted, Modifier.weight(1f))
                        if (selected) Glyph(GlyphKind.Check, WarpColors.Mint, Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun CountryLabel(code: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        CountryFlag(code, emojiSize = 16.sp, width = 18.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            if (code.isEmpty()) stringResource(R.string.region_any) else countryName(code),
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The country's name in the app's language. */
@Composable
private fun countryName(code: String): String {
    val locale = LocalConfiguration.current.locales[0]
    return Locale("", code).getDisplayCountry(locale).ifBlank { code }
}

/**
 * A country's flag. Iran is drawn as its three colours alone, green, white
 * and red, without the emblem or the script the emoji carries; every other
 * country is its emoji.
 */
@Composable
private fun CountryFlag(code: String, emojiSize: TextUnit, width: Dp) {
    if (code.length != 2) {
        // No country yet, or "any": a plain globe rather than the colour emoji.
        Glyph(GlyphKind.Globe, WarpColors.InkMuted, Modifier.size(width), strokeWidth = 1.6.dp)
        return
    }
    if (code != IRAN) {
        val emoji = ExitCountries.flag(code)
        val drawable = remember(emoji) { Paint().hasGlyph(emoji) }
        if (drawable) {
            Text(emoji, fontSize = emojiSize)
        } else {
            // Some phones' fonts have no flags and would show two letters in boxes.
            CodeBadge(code, width)
        }
        return
    }
    val shape = RoundedCornerShape(3.dp)
    Column(
        Modifier
            .size(width = width, height = width * 0.68f)
            .clip(shape)
            .border(0.5.dp, WarpColors.Hairline, shape),
    ) {
        IranStripes.forEach { stripe -> Box(Modifier.fillMaxWidth().weight(1f).background(stripe)) }
    }
}

@Composable
private fun CodeBadge(code: String, width: Dp) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        Modifier
            .size(width = width + 4.dp, height = width * 0.78f)
            .clip(shape)
            .background(WarpColors.Ink.copy(alpha = 0.08f))
            .border(0.5.dp, WarpColors.Hairline, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(code, fontSize = 9.sp, color = WarpColors.InkMuted, maxLines = 1)
    }
}

private const val IRAN = "IR"
private val IranStripes = listOf(Color(0xFF239F40), Color(0xFFFFFFFF), Color(0xFFDA0000))
