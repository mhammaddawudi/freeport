package io.github.mhammaddawudi.freeport.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mhammaddawudi.freeport.R
import io.github.mhammaddawudi.freeport.core.SpeedReport
import io.github.mhammaddawudi.freeport.ui.SpeedTestUi
import io.github.mhammaddawudi.freeport.ui.TrafficUi
import io.github.mhammaddawudi.freeport.ui.theme.AetherColors
import io.github.mhammaddawudi.freeport.ui.theme.NumericStyle
import java.util.Locale

/**
 * Ping, download and upload on the main screen, plus the live rate while a
 * session is up — the numbers someone needs to decide whether to switch
 * protocol, without leaving the button they would switch with.
 */
@Composable
fun SpeedPanel(
    speed: SpeedTestUi,
    traffic: TrafficUi,
    connected: Boolean,
    onRun: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val report = when (speed) {
        SpeedTestUi.Idle -> null
        is SpeedTestUi.Running -> speed.report
        is SpeedTestUi.Done -> speed.report
        is SpeedTestUi.Failed -> speed.last
    }
    val running = speed is SpeedTestUi.Running
    val phase = (speed as? SpeedTestUi.Running)?.report?.phase

    GlassCard(modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.section_speed),
                    style = MaterialTheme.typography.titleMedium,
                    color = AetherColors.Ink,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(
                        when {
                            report != null && report.viaTunnel -> R.string.speed_via_tunnel
                            report != null -> R.string.speed_via_line
                            else -> R.string.speed_hint
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherColors.InkFaint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            PillButton(
                text = stringResource(
                    when {
                        running -> R.string.speed_stop
                        speed is SpeedTestUi.Done || speed is SpeedTestUi.Failed -> R.string.speed_again
                        else -> R.string.speed_run
                    },
                ),
                emphasised = !running,
                onClick = if (running) onStop else onRun,
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            MetricCell(
                label = stringResource(R.string.speed_ping),
                glyph = GlyphKind.Pulse,
                value = report?.pingMs?.toString(),
                unit = "ms",
                footnote = report?.jitterMs?.let { stringResource(R.string.speed_jitter, it.toInt()) },
                tint = pingTint(report?.pingMs),
                progress = if (phase == SpeedReport.Phase.Ping) report?.progress else null,
                modifier = Modifier.weight(1f),
            )
            CellDivider()
            MetricCell(
                label = stringResource(R.string.speed_download),
                glyph = GlyphKind.Down,
                value = report?.downMbps?.let(::formatMbps),
                unit = "Mbps",
                tint = AetherColors.Ink,
                progress = if (phase == SpeedReport.Phase.Download) report?.progress else null,
                modifier = Modifier.weight(1f),
            )
            CellDivider()
            MetricCell(
                label = stringResource(R.string.speed_upload),
                glyph = GlyphKind.Up,
                value = report?.upMbps?.let(::formatMbps),
                unit = "Mbps",
                tint = AetherColors.Ink,
                progress = if (phase == SpeedReport.Phase.Upload) report?.progress else null,
                modifier = Modifier.weight(1f),
            )
        }

        if (speed is SpeedTestUi.Failed) {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.speed_failed, speed.message),
                style = MaterialTheme.typography.bodySmall,
                color = AetherColors.Rose,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        AnimatedVisibility(
            visible = connected,
            enter = fadeIn(tween(240)) + expandVertically(tween(240)),
            exit = fadeOut(tween(160)) + shrinkVertically(tween(160)),
        ) {
            Column {
                Spacer(Modifier.height(16.dp))
                RowDivider()
                Spacer(Modifier.height(14.dp))
                LiveTraffic(traffic)
            }
        }
    }
}

@Composable
private fun LiveTraffic(traffic: TrafficUi) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RateLabel(GlyphKind.Down, stringResource(R.string.traffic_down), traffic.downRate, AetherColors.Mint)
        Spacer(Modifier.width(18.dp))
        RateLabel(GlyphKind.Up, stringResource(R.string.traffic_up), traffic.upRate, AetherColors.Selected)
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.traffic_session, Formats.bytes(traffic.downTotal + traffic.upTotal)),
            style = MaterialTheme.typography.labelMedium.merge(NumericStyle),
            color = AetherColors.InkFaint,
            maxLines = 1,
        )
    }
}

@Composable
private fun RateLabel(glyph: GlyphKind, label: String, bytesPerSecond: Long, tint: Color) {
    val (value, unit) = Formats.rate(bytesPerSecond)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Glyph(glyph, tint, Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            "$value $unit",
            style = MaterialTheme.typography.labelLarge.merge(NumericStyle),
            color = AetherColors.Ink,
            maxLines = 1,
        )
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = AetherColors.InkFaint, maxLines = 1)
    }
}

@Composable
private fun MetricCell(
    label: String,
    glyph: GlyphKind,
    value: String?,
    unit: String,
    tint: Color,
    progress: Float?,
    modifier: Modifier = Modifier,
    footnote: String? = null,
) {
    val measuring = progress != null
    Column(modifier.padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(glyph, if (measuring) AetherColors.Amber else AetherColors.InkFaint, Modifier.size(13.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(5.dp))
            Text(
                label.uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.labelSmall,
                color = AetherColors.InkFaint,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value ?: "—",
                style = MaterialTheme.typography.headlineSmall.merge(NumericStyle),
                color = when {
                    measuring -> AetherColors.Amber
                    value == null -> AetherColors.InkFaint
                    else -> tint
                },
                maxLines = 1,
            )
            if (value != null) {
                Spacer(Modifier.width(3.dp))
                Text(
                    unit,
                    style = MaterialTheme.typography.labelMedium,
                    color = AetherColors.InkMuted,
                    modifier = Modifier.padding(bottom = 3.dp),
                    maxLines = 1,
                )
            }
        }
        Box(Modifier.heightIn(min = 18.dp).padding(top = 6.dp), contentAlignment = Alignment.Center) {
            when {
                progress != null -> ProgressLine(progress)
                footnote != null -> Text(
                    footnote,
                    style = MaterialTheme.typography.labelMedium.merge(NumericStyle),
                    color = AetherColors.InkFaint,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ProgressLine(progress: Float) {
    val shown by animateFloatAsState(progress.coerceIn(0f, 1f), tween(220), label = "speedProgress")
    Box(
        Modifier
            .width(56.dp)
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(AetherColors.Ink.copy(alpha = 0.08f)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(shown)
                .background(AetherColors.Amber),
        )
    }
}

@Composable
private fun CellDivider() {
    Box(
        Modifier
            .padding(vertical = 4.dp)
            .width(1.dp)
            .fillMaxHeight()
            .background(AetherColors.Hairline),
    )
}

/** A compact rounded action, filled when it starts something. */
@Composable
fun PillButton(
    text: String,
    emphasised: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    val fill = if (emphasised) AetherColors.Control else Color.Transparent
    val ink = if (emphasised) AetherColors.Void else AetherColors.Ink
    Box(
        modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(fill)
            .border(1.dp, if (emphasised) fill else AetherColors.HairlineStrong, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1)
    }
}

@Composable
private fun pingTint(ping: Long?): Color = when {
    ping == null -> AetherColors.Ink
    ping < 150 -> AetherColors.Mint
    ping < 400 -> AetherColors.Amber
    else -> AetherColors.Rose
}

private fun formatMbps(value: Double): String =
    if (value < 10) String.format(Locale.US, "%.1f", value) else String.format(Locale.US, "%.0f", value)
