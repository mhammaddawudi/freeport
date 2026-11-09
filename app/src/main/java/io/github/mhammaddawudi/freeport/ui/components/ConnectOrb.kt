package io.github.mhammaddawudi.freeport.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import io.github.mhammaddawudi.freeport.ui.theme.Aurora
import io.github.mhammaddawudi.freeport.R
import io.github.mhammaddawudi.freeport.core.ConnectionState
import io.github.mhammaddawudi.freeport.core.isEngaged
import io.github.mhammaddawudi.freeport.core.isTransitioning
import io.github.mhammaddawudi.freeport.ui.theme.WarpColors
import io.github.mhammaddawudi.freeport.ui.theme.Tones
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The connect button, and the whole of the connection's state: a portal.
 *
 * Three gyroscope rings turn round a glass core. At rest they drift slowly in
 * violet; while a session comes up they spin and the progress arc climbs in
 * magenta; connected, the aurora closes into one bright ring and light waves
 * leave it. The warp field behind streams out from its centre.
 */
@Composable
fun ConnectOrb(
    state: ConnectionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 300.dp,
) {
    val connected = state is ConnectionState.Connected
    val busy = state.isTransitioning

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 420f),
        label = "press",
    )
    val haptics = LocalHapticFeedback.current

    // Nothing turns while disconnected: the loops only run while a session
    // comes up, or once it is up on phones that can afford it, so an idle
    // screen asks for no frames at all.
    val lite = rememberLiteMotion()
    val shimmer by rememberLoop(running = busy || (connected && !lite), durationMs = if (busy) 2_400 else 14_000)
    val ripple by rememberLoop(running = connected && !lite, durationMs = 2_800)
    val pulse by rememberLoop(running = busy, durationMs = 2_200, reverse = true)

    val glow by animateFloatAsState(if (connected) 1f else 0f, tween(900, easing = FastOutSlowInEasing), label = "glow")
    val busyGlow by animateFloatAsState(if (busy) 1f else 0f, tween(500), label = "busyGlow")
    val grow by animateFloatAsState(if (connected) 1f else 0.88f, tween(800, easing = FastOutSlowInEasing), label = "grow")
    val warmth by animateFloatAsState(
        targetValue = when {
            connected -> 1f
            busy -> 0.66f
            state is ConnectionState.Failed -> 0.30f
            else -> 0.46f
        },
        animationSpec = tween(600),
        label = "warmth",
    )

    val tone = Tones.of(state)
    val core = lerp(WarpColors.InkFaint, tone.primary, warmth)
    val rim = lerp(WarpColors.InkFaint, tone.secondary, warmth)

    // How full the ring is, as one climb from empty to closed.
    //
    // The scan reports real numbers, so that stretch is literal. The phases
    // either side have nothing to measure, so they creep towards a ceiling
    // they never reach: the ring only closes when the tunnel is actually up,
    // which is the one thing the person is waiting to see.
    val scan = (state as? ConnectionState.Scanning)?.progress
    val scanned = scan?.takeIf { it.total > 0 }?.let { it.probed.toFloat() / it.total }
    val target = when {
        connected -> 1f
        state is ConnectionState.Preparing -> 0.12f
        state is ConnectionState.Scanning -> scanned?.let { 0.15f + 0.55f * it } ?: 0.45f
        state is ConnectionState.Connecting -> 0.90f
        state is ConnectionState.Reconnecting -> 0.08f
        else -> 0f
    }
    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec = when {
            connected -> tween(500)
            state is ConnectionState.Connecting -> tween(9_000, easing = LinearEasing)
            state is ConnectionState.Scanning && scanned == null -> tween(6_000, easing = LinearEasing)
            else -> tween(700)
        },
        label = "progress",
    )

    val label = when {
        connected -> stringResource(R.string.action_disconnect)
        state.isEngaged -> stringResource(R.string.action_cancel)
        else -> stringResource(R.string.action_connect)
    }

    val discTop = WarpColors.SurfaceRaised
    val discBottom = WarpColors.Void
    val dialIdle = WarpColors.Ink
    val dark = WarpColors.IsDark
    val aurora = Aurora.colors()
    val focus = LocalFieldFocus.current
    DisposableEffect(focus) { onDispose { focus.centre = null } }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(diameter)
                .onGloballyPositioned { box ->
                    val at = box.positionInRoot()
                    focus.centre = Offset(at.x + box.size.width / 2f, at.y + box.size.height / 2f)
                }
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = state !is ConnectionState.Disconnecting,
                    onClickLabel = label,
                    role = Role.Button,
                ) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize().graphicsLayer()) {
                drawPortal(
                    PortalFrame(
                        spin = shimmer,
                        ripple = ripple,
                        pulse = pulse,
                        glow = glow,
                        busyGlow = busyGlow,
                        grow = grow * press * (if (busy) 1f + 0.02f * pulse else 1f),
                        progress = progress,
                        core = core,
                        rim = rim,
                        aurora = aurora,
                        discTop = discTop,
                        discBottom = discBottom,
                        track = dialIdle,
                        dark = dark,
                        waves = !lite,
                    ),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                brush = if (connected) Brush.horizontalGradient(aurora) else null,
                letterSpacing = 3.sp,
            ),
            color = if (connected) Color.Unspecified else WarpColors.InkMuted,
        )
    }
}
