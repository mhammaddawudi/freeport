package io.github.mhammaddawudi.freeport.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import io.github.mhammaddawudi.freeport.R
import io.github.mhammaddawudi.freeport.core.ConnectionState
import io.github.mhammaddawudi.freeport.core.isEngaged
import io.github.mhammaddawudi.freeport.core.isTransitioning
import io.github.mhammaddawudi.freeport.ui.theme.AetherColors
import io.github.mhammaddawudi.freeport.ui.theme.Tones
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The connect button, and the whole of the connection's state.
 *
 * There is no disc, ring or plate behind it — the star is the target, and its
 * own state carries the status. Idle it sits dim and still-ish, turning very
 * slowly; while a session comes up it breathes; connected it burns at full
 * gold inside a halo that pulses with it.
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

    val infinite = rememberInfiniteTransition(label = "orb")
    // Fast enough to read as motion at a glance. The arc it drives is only
    // drawn while a session is coming up, so nothing turns when idle and the
    // speed is free to say "working" rather than "idling".

    val shimmer by infinite.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1_800, easing = LinearEasing)), label = "shimmer",
    )
    val ripple by infinite.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2800, easing = LinearEasing)), label = "ripple",
    )
    val pulse by infinite.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse",
    )

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
    val core = lerp(AetherColors.InkFaint, tone.primary, warmth)
    val rim = lerp(AetherColors.InkFaint, tone.secondary, warmth)

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

    val discTop = AetherColors.SurfaceRaised
    val discBottom = AetherColors.Void
    val dialIdle = AetherColors.Ink

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(diameter)
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
            Canvas(Modifier.fillMaxSize()) {
                val centre = center
                val radius = size.minDimension / 2f
                val breath = if (busy) 1f + 0.02f * pulse else 1f
                val ringRadius = radius * 0.62f * grow * press * breath
                val stroke = 6.dp.toPx()
                val box = Offset(centre.x - ringRadius, centre.y - ringRadius)
                val span = Size(ringRadius * 2, ringRadius * 2)

                // Connected: a halo, and rings travelling outward from it.
                if (glow > 0.01f) {
                    val halo = glow * (0.6f + 0.4f * pulse)
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(
                                tone.primary.copy(alpha = 0.34f * halo),
                                tone.primary.copy(alpha = 0.12f * halo),
                                Color.Transparent,
                            ),
                            centre,
                            radius,
                        ),
                        radius = radius,
                        center = centre,
                    )
                    for (i in 0 until 3) {
                        val t = (ripple + i / 3f) % 1f
                        drawCircle(
                            color = tone.primary.copy(alpha = (1f - t) * 0.22f * glow),
                            radius = ringRadius * (1f + 0.5f * t),
                            center = centre,
                            style = Stroke(1.5.dp.toPx()),
                        )
                    }
                }

                // Connecting: a warm wash under the ring that breathes.
                if (busyGlow > 0.01f) {
                    val warm = busyGlow * (0.35f + 0.65f * pulse)
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(tone.secondary.copy(alpha = 0.20f * warm), Color.Transparent),
                            centre,
                            radius * 0.92f,
                        ),
                        radius = radius * 0.92f,
                        center = centre,
                    )
                }

                // The inside of the ring is lit while the session is coming up
                // as well as once it is up, so the button reads as something
                // switched on rather than an outline.
                if (busyGlow > 0.01f) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(
                                rim.copy(alpha = 0.07f * busyGlow * (0.6f + 0.4f * pulse)),
                                Color.Transparent,
                            ),
                            centre,
                            ringRadius,
                        ),
                        radius = ringRadius,
                        center = centre,
                    )
                }
                if (glow > 0.01f) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(
                                tone.primary.copy(alpha = 0.10f * glow),
                                tone.primary.copy(alpha = 0.03f * glow),
                                Color.Transparent,
                            ),
                            centre,
                            ringRadius,
                        ),
                        radius = ringRadius,
                        center = centre,
                    )
                }

                // The dial: ticks round the ring that light up as the session
                // comes up — the same progress the arc shows, made legible at
                // a glance like an instrument rather than a spinner.
                val ticks = 60
                val lit = when {
                    connected -> 1f
                    busy -> progress
                    else -> 0f
                }
                for (i in 0 until ticks) {
                    val fraction = i / ticks.toFloat()
                    val angle = ((-90f + 360f * fraction) * (PI / 180f)).toFloat()
                    val major = i % 5 == 0
                    val inner = radius * (if (major) 0.80f else 0.82f) * grow * press
                    val outer = radius * 0.86f * grow * press
                    val on = fraction < lit
                    val colour = if (on) {
                        core.copy(alpha = 0.55f + 0.45f * glow)
                    } else {
                        dialIdle.copy(alpha = if (major) 0.16f else 0.07f)
                    }
                    drawLine(
                        color = colour,
                        start = Offset(centre.x + inner * cos(angle), centre.y + inner * sin(angle)),
                        end = Offset(centre.x + outer * cos(angle), centre.y + outer * sin(angle)),
                        strokeWidth = if (major) 2.dp.toPx() else 1.4.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }

                // A glass disc inside the ring, lit from the top left, so the
                // button reads as something you can press.
                val discRadius = ringRadius - stroke * 1.4f
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(discTop, discBottom),
                        Offset(centre.x - discRadius * 0.35f, centre.y - discRadius * 0.45f),
                        discRadius * 1.5f,
                    ),
                    radius = discRadius,
                    center = centre,
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(tone.primary.copy(alpha = 0.16f * glow + 0.06f * busyGlow), Color.Transparent),
                        centre,
                        discRadius,
                    ),
                    radius = discRadius,
                    center = centre,
                )

                // The ring. Idle it is a faint outline; connected it closes
                // into a full bright circle; connecting, an arc runs round it.
                drawCircle(
                    color = core.copy(alpha = 0.30f + 0.25f * glow),
                    radius = ringRadius,
                    center = centre,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                if (glow > 0.01f) {
                    drawArc(
                        brush = Brush.sweepGradient(listOf(rim, core, rim), centre),
                        startAngle = -90f,
                        sweepAngle = 360f * glow,
                        useCenter = false,
                        topLeft = box,
                        size = span,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
                // Connecting. The arc is the honest part — it only advances
                // when the connection does — so the life comes from three
                // things layered over it that cost nothing in meaning: a lit
                // head at the growing end, a highlight that keeps travelling
                // the ring, and faint rings leaving it.
                if (busyGlow > 0.01f) {
                    for (i in 0 until 2) {
                        val t = (ripple + i / 2f) % 1f
                        drawCircle(
                            color = rim.copy(alpha = (1f - t) * 0.13f * busyGlow),
                            radius = ringRadius * (1f + 0.34f * t),
                            center = centre,
                            style = Stroke(1.5.dp.toPx()),
                        )
                    }

                    rotate(shimmer * 360f, centre) {
                        drawArc(
                            color = core.copy(alpha = 0.22f * busyGlow),
                            startAngle = -115f,
                            sweepAngle = 50f,
                            useCenter = false,
                            topLeft = box,
                            size = span,
                            style = Stroke(stroke * 0.7f, cap = StrokeCap.Round),
                        )
                    }

                    drawArc(
                        brush = Brush.sweepGradient(listOf(rim, core, rim), centre),
                        startAngle = -90f,
                        sweepAngle = 360f * progress,
                        useCenter = false,
                        topLeft = box,
                        size = span,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                        alpha = busyGlow,
                    )

                    if (progress > 0.01f) {
                        val head = (-90f + 360f * progress) * (PI / 180f).toFloat()
                        val headAt = Offset(
                            centre.x + ringRadius * cos(head),
                            centre.y + ringRadius * sin(head),
                        )
                        val halo = stroke * (2.0f + 0.5f * pulse)
                        drawCircle(
                            brush = Brush.radialGradient(
                                listOf(core.copy(alpha = 0.50f * busyGlow), Color.Transparent),
                                headAt,
                                halo,
                            ),
                            radius = halo,
                            center = headAt,
                        )
                        drawCircle(
                            color = core.copy(alpha = busyGlow),
                            radius = stroke * 0.52f,
                            center = headAt,
                        )
                    }
                }

                // The power mark, bright when connected and dim when not.
                val markRadius = ringRadius * 0.40f
                val markColour = core.copy(alpha = 0.55f + 0.45f * glow)
                drawArc(
                    color = markColour,
                    startAngle = 300f,
                    sweepAngle = 300f,
                    useCenter = false,
                    topLeft = Offset(centre.x - markRadius, centre.y - markRadius + markRadius * 0.12f),
                    size = Size(markRadius * 2, markRadius * 2),
                    style = Stroke(stroke * 0.8f, cap = StrokeCap.Round),
                )
                drawLine(
                    color = markColour,
                    start = Offset(centre.x, centre.y - markRadius * 1.15f),
                    end = Offset(centre.x, centre.y + markRadius * 0.05f),
                    strokeWidth = stroke * 0.8f,
                    cap = StrokeCap.Round,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = AetherColors.InkMuted,
        )
    }
}
