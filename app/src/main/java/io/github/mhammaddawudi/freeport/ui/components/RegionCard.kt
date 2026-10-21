package io.github.mhammaddawudi.freeport.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mhammaddawudi.freeport.R
import io.github.mhammaddawudi.freeport.core.ConnectionState
import io.github.mhammaddawudi.freeport.data.ExitCountries
import io.github.mhammaddawudi.freeport.ui.theme.AetherColors
import java.util.Locale

/** The country a session leaves from — which is what decides region errors. */
private const val HOME_COUNTRY = "IR"

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
) {
    val connected = state as? ConnectionState.Connected
    val exit = connected?.exitCountry
    val locked = exit == HOME_COUNTRY
    val relaying = (state as? ConnectionState.Connecting)?.relaying == true

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        accent = when {
            locked -> AetherColors.Rose
            connected?.relayed == true && exit != null -> AetherColors.Mint
            else -> null
        },
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(AetherColors.Ink.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(exit?.let(ExitCountries::flag) ?: ExitCountries.flag(""), fontSize = 22.sp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.region_title).uppercase(Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = AetherColors.InkFaint,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    when {
                        relaying -> stringResource(R.string.state_relaying)
                        exit != null -> countryName(exit)
                        connected != null -> stringResource(R.string.region_checking)
                        else -> stringResource(R.string.region_not_connected)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (locked) AetherColors.Rose else AetherColors.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        val note = when {
            locked && !regionUnlock -> stringResource(R.string.region_warning)
            locked -> stringResource(R.string.region_warning_unlock_failed)
            connected?.relayed == true && exit != null -> stringResource(R.string.region_ok)
            else -> null
        }
        if (note != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = if (locked) AetherColors.Rose else AetherColors.Mint,
            )
        }

        Spacer(Modifier.height(4.dp))
        SwitchRow(
            title = stringResource(R.string.region_unlock),
            subtitle = stringResource(R.string.region_unlock_desc),
            checked = regionUnlock,
            onCheckedChange = onToggle,
            accent = AetherColors.Mint,
        )

        AnimatedVisibility(
            visible = regionUnlock,
            enter = fadeIn(tween(220)) + expandVertically(tween(220)),
            exit = fadeOut(tween(160)) + shrinkVertically(tween(160)),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 4.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ExitCountries.offered.forEach { code ->
                    CountryChip(
                        label = if (code.isEmpty()) stringResource(R.string.region_any) else countryName(code),
                        flag = ExitCountries.flag(code),
                        selected = code == exitCountry,
                        onClick = { onSelectCountry(code) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CountryChip(label: String, flag: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val accent = AetherColors.Mint
    Row(
        Modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.14f) else Color.Transparent)
            .border(1.dp, if (selected) accent else AetherColors.Hairline, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(flag, fontSize = 16.sp)
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) AetherColors.Ink else AetherColors.InkMuted,
            maxLines = 1,
        )
    }
}

/** The country's name in the app's language. */
@Composable
private fun countryName(code: String): String {
    val locale = LocalConfiguration.current.locales[0]
    return Locale("", code).getDisplayCountry(locale).ifBlank { code }
}
