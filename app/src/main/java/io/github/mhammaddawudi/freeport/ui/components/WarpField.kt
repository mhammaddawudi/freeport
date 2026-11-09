package io.github.mhammaddawudi.freeport.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import io.github.mhammaddawudi.freeport.core.ConnectionState
import io.github.mhammaddawudi.freeport.core.isTransitioning
import io.github.mhammaddawudi.freeport.ui.theme.Tones
import io.github.mhammaddawudi.freeport.ui.theme.WarpColors
import kotlin.math.max
import kotlin.random.Random

/**
 * Where the warp field streams out from: the portal's centre, in root
 * coordinates, or null when no portal is on screen.
 */
@Stable
class FieldFocus {
    var centre by mutableStateOf<Offset?>(null)
}

val LocalFieldFocus = staticCompositionLocalOf { FieldFocus() }

/** One star, fixed for the life of the field; only the travel moves it. */
private data class Star(val x: Float, val y: Float, val depth: Float, val size: Float, val hue: Float)

private const val STAR_COUNT = 120

/** Low-end phones get fewer stars, and they only move while connecting. */
private const val LITE_STAR_COUNT = 60
private const val NEAREST = 0.035f

/**
 * The backdrop: deep space, a nebula in the colour of the connection, and a
 * field of stars falling towards the viewer from the portal.
 *
 * At rest the stars drift. While a session comes up they stretch into
 * streaks — the jump to warp — and once it is up they cruise. Every star is
 * a pure function of its seed and the distance travelled, so nothing is
 * mutated per frame but one float.
 */
@Composable
fun WarpField(state: ConnectionState, modifier: Modifier = Modifier) {
    val focus = LocalFieldFocus.current
    val lite = rememberLiteMotion()
    val stars = remember(lite) {
        val random = Random(1819)
        List(if (lite) LITE_STAR_COUNT else STAR_COUNT) {
            Star(
                x = random.nextFloat() * 2f - 1f,
                y = random.nextFloat() * 2f - 1f,
                depth = random.nextFloat(),
                size = 0.6f + random.nextFloat() * 1.2f,
                hue = random.nextFloat(),
            )
        }
    }

    val speed by animateFloatAsState(
        targetValue = when {
            state is ConnectionState.Connected -> 0.10f
            state.isTransitioning -> 0.85f
            else -> 0.025f
        },
        animationSpec = tween(1_400, easing = FastOutSlowInEasing),
        label = "warpSpeed",
    )
    val streak by animateFloatAsState(
        targetValue = if (state.isTransitioning) 1f else 0.15f,
        animationSpec = tween(1_200, easing = FastOutSlowInEasing),
        label = "warpStreak",
    )
    val tone = Tones.of(state)
    val nebula by animateColorAsState(tone.primary, tween(1_200), label = "nebula")
    val nebulaTwo by animateColorAsState(tone.secondary, tween(1_200), label = "nebulaTwo")

    val travel = remember { mutableFloatStateOf(0f) }
    val currentSpeed by rememberUpdatedState(speed)
    // Frames are asked for only while the stars move: always while a session
    // comes up, and once connected on phones that can afford it. At rest the
    // field is a still picture and costs nothing.
    val moving = state.isTransitioning || (state is ConnectionState.Connected && !lite)
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / 1e9f).coerceAtMost(0.05f)
                    travel.floatValue = (travel.floatValue + currentSpeed * dt) % 1_000f
                }
                last = now
            }
        }
    }

    val ground = WarpColors.Void
    val ink = WarpColors.Ink
    val dark = WarpColors.IsDark
    val aqua = WarpColors.Aqua
    val violet = WarpColors.Violet

    // Two layers: the nebula repaints only when its colour changes, the
    // stars on every frame they move.
    Canvas(modifier.fillMaxSize().background(ground)) {
        val origin = focus.centre ?: Offset(size.width / 2f, size.height * 0.36f)
        drawNebula(origin, nebula, nebulaTwo, dark)
        // Darkens toward the bottom, so the floating bar sits on shade.
        drawRect(
            Brush.verticalGradient(
                listOf(Color.Transparent, ground.copy(alpha = 0.9f)),
                startY = size.height * 0.70f,
                endY = size.height,
            ),
        )
    }
    // Its own layer: a moving star repaints the stars, not the screen.
    Canvas(modifier.fillMaxSize().graphicsLayer()) {
        val origin = focus.centre ?: Offset(size.width / 2f, size.height * 0.36f)

        val reach = max(size.width, size.height) * 0.55f
        val moved = travel.floatValue
        stars.forEach { star ->
            val z = NEAREST + ((star.depth - moved) % 1f + 1f) % 1f * (1f - NEAREST)
            val tail = (z + 0.02f + 0.16f * streak).coerceAtMost(1f)
            val head = project(star, z, origin, reach)
            val back = project(star, tail, origin, reach)
            val near = 1f - z
            // Faded in from the far end, so stars never pop into being.
            val alpha = (near * near * 1.2f).coerceIn(0f, 1f) * if (dark) 1f else 0.55f
            val tint = when {
                star.hue < 0.18f -> aqua
                star.hue < 0.30f -> violet
                else -> ink
            }
            val colour = lerp(tint, nebula, 0.25f).copy(alpha = alpha)
            val width = star.size * (0.6f + 2.2f * near) * density / 2.4f
            drawLine(colour, back, head, strokeWidth = width, cap = StrokeCap.Round)
        }
    }
}

private fun project(star: Star, z: Float, origin: Offset, reach: Float): Offset =
    Offset(origin.x + star.x / z * reach * 0.12f, origin.y + star.y / z * reach * 0.12f)

private fun DrawScope.drawNebula(origin: Offset, primary: Color, secondary: Color, dark: Boolean) {
    val strength = if (dark) 1f else 0.6f
    val w = size.width
    drawCircle(
        Brush.radialGradient(
            listOf(primary.copy(alpha = 0.22f * strength), primary.copy(alpha = 0.06f * strength), Color.Transparent),
            origin,
            w * 0.85f,
        ),
        w * 0.85f,
        origin,
    )
    val drift = Offset(w * 0.92f, size.height * 0.08f)
    drawCircle(
        Brush.radialGradient(listOf(secondary.copy(alpha = 0.16f * strength), Color.Transparent), drift, w * 0.7f),
        w * 0.7f,
        drift,
    )
    val low = Offset(w * 0.05f, size.height * 0.78f)
    drawCircle(
        Brush.radialGradient(listOf(secondary.copy(alpha = 0.10f * strength), Color.Transparent), low, w * 0.8f),
        w * 0.8f,
        low,
    )
}
