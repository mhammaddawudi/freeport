package io.github.mhammaddawudi.freeport.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import io.github.mhammaddawudi.freeport.ui.components.StatusPill
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import io.github.mhammaddawudi.freeport.R
import io.github.mhammaddawudi.freeport.core.ConnectionState
import io.github.mhammaddawudi.freeport.core.TunMode
import io.github.mhammaddawudi.freeport.data.AetherSettings
import io.github.mhammaddawudi.freeport.data.Protocol
import io.github.mhammaddawudi.freeport.ui.components.ConnectOrb
import io.github.mhammaddawudi.freeport.ui.components.Formats
import io.github.mhammaddawudi.freeport.ui.components.Glyph
import io.github.mhammaddawudi.freeport.ui.components.GlyphKind
import io.github.mhammaddawudi.freeport.ui.components.ProtocolActivity
import io.github.mhammaddawudi.freeport.ui.components.ProtocolGrid
import io.github.mhammaddawudi.freeport.ui.components.RegionCard
import io.github.mhammaddawudi.freeport.ui.components.SectionLabel
import io.github.mhammaddawudi.freeport.ui.components.SpeedPanel
import io.github.mhammaddawudi.freeport.ui.SpeedTestUi
import io.github.mhammaddawudi.freeport.ui.TrafficUi
import io.github.mhammaddawudi.freeport.ui.theme.AetherColors
import io.github.mhammaddawudi.freeport.ui.theme.NumericStyle
import io.github.mhammaddawudi.freeport.ui.theme.Tone
import io.github.mhammaddawudi.freeport.ui.theme.Tones

/** The author's profile, reachable from the one screen everyone sees. */
private const val GITHUB_PROFILE = "https://github.com/mhammaddawudi"

/**
 * Deliberately bare: the name, the state, the button, and the four routes.
 *
 * Everything explanatory — taglines, tuning chips, counters, the core badge —
 * lives in Settings now. What is left is what someone opens the app to do.
 */
@Composable
fun HomeScreen(
    state: ConnectionState,
    settings: AetherSettings,
    isNativeCore: Boolean,
    traffic: TrafficUi,
    speedTest: SpeedTestUi,
    onToggle: () -> Unit,
    onSelectProtocol: (Protocol) -> Unit,
    onRunSpeedTest: () -> Unit,
    onStopSpeedTest: () -> Unit,
    onRegionUnlock: (Boolean) -> Unit,
    onSelectExitCountry: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    val tone = Tones.of(state)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        BrandBar(state)

        Spacer(Modifier.height(22.dp))
        StatusBlock(state)
        ConnectOrb(state = state, onClick = onToggle, diameter = 272.dp)
        SessionLine(state, tone)

        Spacer(Modifier.height(18.dp))
        RegionCard(
            state = state,
            regionUnlock = settings.regionUnlock,
            exitCountry = settings.exitCountry,
            onToggle = onRegionUnlock,
            onSelectCountry = onSelectExitCountry,
        )
        Spacer(Modifier.height(12.dp))
        SpeedPanel(
            speed = speedTest,
            traffic = traffic,
            connected = state is ConnectionState.Connected,
            onRun = onRunSpeedTest,
            onStop = onStopSpeedTest,
        )

        SectionLabel(stringResource(R.string.section_protocol), Modifier.fillMaxWidth())
        ProtocolGrid(
            selected = settings.protocol,
            activity = activityOf(state),
            onSelect = onSelectProtocol,
        )
        Spacer(Modifier.height(16.dp))
    }
}

/** The logo, the name, how the connection stands, and the author's profile. */
@Composable
private fun BrandBar(state: ConnectionState) {
    val context = LocalContext.current
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.verticalGradient(listOf(AetherColors.SurfaceRaised, AetherColors.Void)))
                .border(1.dp, AetherColors.Hairline, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(R.mipmap.ic_launcher_foreground), contentDescription = null, Modifier.size(52.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            color = AetherColors.Ink,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        val (label, tint) = when (state) {
            is ConnectionState.Connected -> R.string.state_connected to AetherColors.Mint
            is ConnectionState.Failed -> R.string.state_failed to AetherColors.Rose
            ConnectionState.Idle, ConnectionState.Disconnecting -> R.string.state_idle to AetherColors.InkFaint
            else -> R.string.state_connecting to AetherColors.Amber
        }
        StatusPill(stringResource(label), tint, pulsing = state is ConnectionState.Connected)
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(AetherColors.Ink.copy(alpha = 0.05f))
                .border(1.dp, AetherColors.Hairline, CircleShape)
                .clickable {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_PROFILE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_github),
                contentDescription = GITHUB_PROFILE,
                tint = AetherColors.InkMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** The protocol on the wire right now, for the cards to mark. */
private fun activityOf(state: ConnectionState): ProtocolActivity? = when (state) {
    is ConnectionState.Connected -> ProtocolActivity(state.protocol, live = true, viaAuto = state.viaAuto)
    is ConnectionState.Connecting -> ProtocolActivity(state.protocol, live = false, viaAuto = state.viaAuto)
    is ConnectionState.Preparing -> ProtocolActivity(state.protocol, live = false, viaAuto = false)
    is ConnectionState.Scanning -> ProtocolActivity(state.protocol, live = false, viaAuto = false)
    is ConnectionState.Reconnecting -> ProtocolActivity(state.protocol, live = false, viaAuto = false)
    else -> null
}

@Composable
private fun StatusBlock(state: ConnectionState) {
    val headline = stringResource(
        when (state) {
            ConnectionState.Idle, ConnectionState.Disconnecting -> R.string.state_idle
            is ConnectionState.Preparing -> R.string.state_preparing
            is ConnectionState.Scanning -> R.string.state_scanning
            is ConnectionState.Connecting -> if (state.relaying) R.string.state_relaying else R.string.state_connecting
            is ConnectionState.Connected -> R.string.state_connected
            is ConnectionState.Reconnecting -> R.string.state_reconnecting
            is ConnectionState.Failed -> R.string.state_failed
        },
    )
    // One short line, and only when it says something the headline does not.
    val carrier = when (state) {
        is ConnectionState.Connected ->
            if (state.viaAuto) stringResource(R.string.auto_via, state.protocol.displayName) else state.protocol.displayName
        is ConnectionState.Connecting ->
            if (state.viaAuto) stringResource(R.string.auto_via, state.protocol.displayName) else state.protocol.displayName
        else -> null
    }
    val detail = when (state) {
        is ConnectionState.Connected -> buildString {
            append(carrier.orEmpty())
            state.endpoint?.let { append(" · ").append(it.socket) }
            if (state.mode == TunMode.ProxyOnly) {
                if (isNotEmpty()) append(" · ")
                append("proxy")
            }
        }
        is ConnectionState.Connecting -> listOfNotNull(carrier, state.endpoint?.socket).joinToString(" · ")
        is ConnectionState.Failed -> state.message
        is ConnectionState.Reconnecting -> state.reason
        else -> ""
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedContent(
            targetState = headline,
            transitionSpec = {
                (fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 3 }) togetherWith
                    (fadeOut(tween(160)) + slideOutVertically(tween(160)) { -it / 3 })
            },
            label = "headline",
        ) { text ->
            Text(
                text,
                style = MaterialTheme.typography.displaySmall,
                color = AetherColors.Ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (detail.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (state is ConnectionState.Failed) AetherColors.Rose else AetherColors.InkFaint,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
    }
}

@Composable
private fun SessionLine(state: ConnectionState, tone: Tone) {
    Box(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.Center) {
        when (state) {
            is ConnectionState.Connected -> {
                val now by produceState(System.currentTimeMillis(), state.sinceMillis) {
                    while (true) {
                        value = System.currentTimeMillis()
                        delay(1_000)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Glyph(GlyphKind.Clock, tone.primary, Modifier.size(14.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        Formats.duration(now - state.sinceMillis),
                        style = MaterialTheme.typography.titleMedium.merge(NumericStyle),
                        color = AetherColors.Ink,
                    )
                }
            }
            is ConnectionState.Scanning -> {
                val progress = state.progress
                if (progress.total > 0) {
                    Text(
                        // Isolated left to right, or RTL turns 18 / 40 into 40 / 18.
                        "\u2066${progress.probed} / ${progress.total}\u2069",
                        style = MaterialTheme.typography.labelLarge.merge(NumericStyle),
                        color = tone.primary,
                    )
                }
            }
            else -> Unit
        }
    }
}
