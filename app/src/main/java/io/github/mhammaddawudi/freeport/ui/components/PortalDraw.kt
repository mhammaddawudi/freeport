package io.github.mhammaddawudi.freeport.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Everything one frame of the portal needs; all animation values are 0..1. */
internal data class PortalFrame(
    val spin: Float,
    val ripple: Float,
    val pulse: Float,
    val glow: Float,
    val busyGlow: Float,
    val grow: Float,
    val progress: Float,
    val core: Color,
    val rim: Color,
    val aurora: List<Color>,
    val discTop: Color,
    val discBottom: Color,
    val track: Color,
    val dark: Boolean,
    /** The light waves leaving a connected portal; off on low-end phones. */
    val waves: Boolean = true,
)

/** A gyroscope ring: how far out, how much of it is lit, and how fast it turns. */
private data class Gyro(val reach: Float, val sweep: Float, val turns: Float, val offset: Float)

private val GYROS = listOf(
    Gyro(reach = 1.40f, sweep = 110f, turns = 1f, offset = 0f),
    Gyro(reach = 1.26f, sweep = 70f, turns = -1.6f, offset = 140f),
    Gyro(reach = 1.14f, sweep = 150f, turns = 2.3f, offset = 260f),
)

internal fun DrawScope.drawPortal(f: PortalFrame) {
    val centre = center
    val ring = size.minDimension / 2f * 0.58f * f.grow
    val stroke = 7.dp.toPx()
    val lit = maxOf(f.glow, f.busyGlow)
    val sweep = Brush.sweepGradient(f.aurora + f.aurora.first(), centre)
    val busySweep = Brush.sweepGradient(listOf(f.rim, f.core, f.rim), centre)

    // The halo behind everything, in the colour of the state.
    drawCircle(
        Brush.radialGradient(
            listOf(f.core.copy(alpha = 0.10f + 0.22f * lit), Color.Transparent),
            centre,
            ring * 1.75f,
        ),
        ring * 1.75f,
        centre,
    )

    // Connected: waves of aurora leaving the portal.
    if (f.waves && f.glow > 0.01f) {
        for (i in 0 until 3) {
            val t = (f.ripple + i / 3f) % 1f
            drawCircle(
                brush = sweep,
                radius = ring * (1f + 0.62f * t),
                center = centre,
                style = Stroke(2.dp.toPx() * (1f - t) + 0.5f),
                alpha = (1f - t) * 0.45f * f.glow,
            )
        }
    }

    drawGyros(f, ring, centre, sweep, busySweep, lit)
    drawCoreRing(f, ring, stroke, centre, sweep, busySweep)
    drawDisc(f, ring - stroke * 1.5f, centre, lit)
    drawPowerMark(f, ring * 0.36f, stroke, centre)
}

private fun DrawScope.drawGyros(f: PortalFrame, ring: Float, centre: Offset, sweep: Brush, busySweep: Brush, lit: Float) {
    GYROS.forEachIndexed { index, gyro ->
        val radius = ring * gyro.reach
        drawCircle(f.track.copy(alpha = 0.05f + 0.02f * index), radius, centre, style = Stroke(1.dp.toPx()))
        val angle = gyro.offset + f.spin * 360f * gyro.turns
        val box = Offset(centre.x - radius, centre.y - radius)
        val span = Size(radius * 2, radius * 2)
        rotate(angle, centre) {
            drawArc(
                brush = if (f.busyGlow > f.glow) busySweep else sweep,
                startAngle = 0f,
                sweepAngle = gyro.sweep,
                useCenter = false,
                topLeft = box,
                size = span,
                style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round),
                alpha = 0.30f + 0.60f * lit,
            )
        }
        // A satellite riding the lit end of each ring.
        val head = ((angle + gyro.sweep) * PI / 180f).toFloat()
        val at = Offset(centre.x + radius * cos(head), centre.y + radius * sin(head))
        val dot = f.aurora[index % f.aurora.size]
        drawCircle(
            Brush.radialGradient(listOf(dot.copy(alpha = 0.7f * (0.4f + 0.6f * lit)), Color.Transparent), at, 9.dp.toPx()),
            9.dp.toPx(),
            at,
        )
        drawCircle(dot.copy(alpha = 0.5f + 0.5f * lit), 2.4.dp.toPx(), at)
    }
}

private fun DrawScope.drawCoreRing(f: PortalFrame, ring: Float, stroke: Float, centre: Offset, sweep: Brush, busySweep: Brush) {
    val box = Offset(centre.x - ring, centre.y - ring)
    val span = Size(ring * 2, ring * 2)
    drawCircle(f.track.copy(alpha = if (f.dark) 0.10f else 0.12f), ring, centre, style = Stroke(stroke))

    if (f.glow > 0.01f) {
        rotate(f.spin * 360f, centre) {
            // A wide soft pass under the ring, then the ring itself.
            drawCircle(brush = sweep, radius = ring, center = centre, style = Stroke(stroke * 3.2f), alpha = 0.16f * f.glow)
            drawArc(
                brush = sweep,
                startAngle = -90f,
                sweepAngle = 360f * f.glow,
                useCenter = false,
                topLeft = box,
                size = span,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
    }
    if (f.busyGlow > 0.01f) {
        drawArc(
            brush = busySweep,
            startAngle = -90f,
            sweepAngle = 360f * f.progress,
            useCenter = false,
            topLeft = box,
            size = span,
            style = Stroke(stroke, cap = StrokeCap.Round),
            alpha = f.busyGlow,
        )
        if (f.progress > 0.01f) {
            val head = ((-90f + 360f * f.progress) * PI / 180f).toFloat()
            val at = Offset(centre.x + ring * cos(head), centre.y + ring * sin(head))
            val halo = stroke * (2.4f + 0.6f * f.pulse)
            drawCircle(
                Brush.radialGradient(listOf(f.core.copy(alpha = 0.6f * f.busyGlow), Color.Transparent), at, halo),
                halo,
                at,
            )
            drawCircle(Color.White.copy(alpha = 0.9f * f.busyGlow), stroke * 0.42f, at)
        }
    }
}

private fun DrawScope.drawDisc(f: PortalFrame, radius: Float, centre: Offset, lit: Float) {
    drawCircle(
        Brush.radialGradient(
            listOf(f.discTop, f.discBottom),
            Offset(centre.x - radius * 0.3f, centre.y - radius * 0.45f),
            radius * 1.6f,
        ),
        radius,
        centre,
    )
    // Light pooling inside the glass, in the state's colour.
    drawCircle(
        Brush.radialGradient(
            listOf(f.core.copy(alpha = 0.05f + 0.16f * lit), Color.Transparent),
            Offset(centre.x, centre.y + radius * 0.35f),
            radius,
        ),
        radius,
        centre,
    )
    // The glass edge: bright where the light catches it, gone at the bottom.
    drawCircle(
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = if (f.dark) 0.22f else 0.6f), Color.Transparent),
            startY = centre.y - radius,
            endY = centre.y + radius * 0.4f,
        ),
        radius,
        centre,
        style = Stroke(1.2.dp.toPx()),
    )
}

private fun DrawScope.drawPowerMark(f: PortalFrame, radius: Float, stroke: Float, centre: Offset) {
    val start = lerp(f.core, f.aurora.first(), f.glow)
    val end = lerp(f.core, f.aurora.last(), f.glow)
    val brush = Brush.linearGradient(
        listOf(start, end),
        Offset(centre.x - radius, centre.y - radius),
        Offset(centre.x + radius, centre.y + radius),
    )
    val alpha = 0.6f + 0.4f * maxOf(f.glow, f.busyGlow)
    drawArc(
        brush = brush,
        startAngle = 300f,
        sweepAngle = 300f,
        useCenter = false,
        topLeft = Offset(centre.x - radius, centre.y - radius + radius * 0.12f),
        size = Size(radius * 2, radius * 2),
        style = Stroke(stroke * 0.85f, cap = StrokeCap.Round),
        alpha = alpha,
    )
    drawLine(
        brush = brush,
        start = Offset(centre.x, centre.y - radius * 1.15f),
        end = Offset(centre.x, centre.y + radius * 0.05f),
        strokeWidth = stroke * 0.85f,
        cap = StrokeCap.Round,
        alpha = alpha,
    )
}
