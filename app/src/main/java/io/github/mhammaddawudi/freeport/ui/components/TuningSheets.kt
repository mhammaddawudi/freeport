package io.github.mhammaddawudi.freeport.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import io.github.mhammaddawudi.freeport.R
import io.github.mhammaddawudi.freeport.data.AetherSettings
import io.github.mhammaddawudi.freeport.data.IpVersion
import io.github.mhammaddawudi.freeport.data.ScanMode
import io.github.mhammaddawudi.freeport.ui.theme.AetherColors

/** The three tuning choices that get their own sheet. */
enum class TuningSheet { Scan, Noize, Ip }

/**
 * One sheet, shared by the connect screen's chips and the settings rows, so
 * the same choice is never presented two different ways.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TuningSheetHost(
    sheet: TuningSheet?,
    settings: AetherSettings,
    onUpdateSettings: ((AetherSettings) -> AetherSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    if (sheet == null) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AetherColors.Surface,
        contentColor = AetherColors.Ink,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(AetherColors.HairlineStrong),
            )
        },
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
        ) {
            val (title, subtitle) = when (sheet) {
                TuningSheet.Scan ->
                    stringResource(R.string.sheet_scan_title) to stringResource(R.string.sheet_scan_subtitle)
                TuningSheet.Noize ->
                    stringResource(R.string.sheet_noize_title) to stringResource(R.string.sheet_noize_subtitle)
                TuningSheet.Ip ->
                    stringResource(R.string.sheet_ip_title) to stringResource(R.string.sheet_ip_subtitle)
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, color = AetherColors.Ink)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = AetherColors.InkMuted)
            Spacer(Modifier.height(16.dp))

            when (sheet) {
                TuningSheet.Scan -> ScanMode.entries.forEach { mode ->
                    ChoiceRow(
                        title = stringResource(mode.labelRes),
                        description = stringResource(mode.descriptionRes),
                        selected = mode == settings.scanMode,
                        accent = AetherColors.Control,
                        onClick = {
                            onUpdateSettings { it.copy(scanMode = mode) }
                            close()
                        },
                    )
                }

                TuningSheet.Noize -> settings.protocol.noizeProfiles.forEach { profile ->
                    ChoiceRow(
                        title = stringResource(profile.labelRes),
                        description = stringResource(profile.descriptionRes),
                        selected = profile == settings.effectiveNoize,
                        accent = AetherColors.Control,
                        onClick = {
                            onUpdateSettings { it.copy(noize = profile) }
                            close()
                        },
                    )
                }

                TuningSheet.Ip -> IpVersion.entries.forEach { version ->
                    ChoiceRow(
                        title = stringResource(version.labelRes),
                        description = "",
                        selected = version == settings.ipVersion,
                        accent = AetherColors.Control,
                        onClick = {
                            onUpdateSettings { it.copy(ipVersion = version) }
                            close()
                        },
                    )
                }
            }
        }
    }
}
