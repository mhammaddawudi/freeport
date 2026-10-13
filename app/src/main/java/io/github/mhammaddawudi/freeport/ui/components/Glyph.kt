package io.github.mhammaddawudi.freeport.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class GlyphKind {
    Power, Shield, Sliders, Terminal,
    Signal, Stack, Wave, Nested,
    Check, Chevron, Down, Up, Pulse, Radar, Mask, Globe,
    Copy, Trash, Clock, Key, Close, Bolt, Plug, Code,
}

/**
 * The app's icon set, drawn on a 24-unit grid with round caps.
 *
 * Drawn rather than pulled from an icon font so every glyph shares one
 * stroke weight and optical size, and so the protocol marks — which no stock
 * set has — sit comfortably next to the rest.
 */
@Composable
fun Glyph(
    kind: GlyphKind,
    tint: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 1.8.dp,
) {
    Canvas(modifier) {
        val side = size.minDimension
        val u = side / 24f
        val ox = (size.width - side) / 2f
        val oy = (size.height - side) / 2f
        val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)

        fun pt(x: Float, y: Float) = Offset(ox + x * u, oy + y * u)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, pt(x1, y1), pt(x2, y2), strokeWidth = stroke.width, cap = StrokeCap.Round)
        fun ring(cx: Float, cy: Float, r: Float) = drawCircle(tint, r * u, pt(cx, cy), style = stroke)
        fun dot(cx: Float, cy: Float, r: Float) = drawCircle(tint, r * u, pt(cx, cy))
        fun box(l: Float, t: Float, r: Float, b: Float, radius: Float) = drawRoundRect(
            tint, pt(l, t), Size((r - l) * u, (b - t) * u), CornerRadius(radius * u), style = stroke,
        )
        fun poly(vararg xy: Float, closed: Boolean = false) {
            val path = Path()
            path.moveTo(ox + xy[0] * u, oy + xy[1] * u)
            var i = 2
            while (i < xy.size) {
                path.lineTo(ox + xy[i] * u, oy + xy[i + 1] * u)
                i += 2
            }
            if (closed) path.close()
            drawPath(path, tint, style = stroke)
        }
        fun arc(cx: Float, cy: Float, r: Float, start: Float, sweep: Float) = drawArc(
            tint, start, sweep, useCenter = false,
            topLeft = pt(cx - r, cy - r), size = Size(2 * r * u, 2 * r * u), style = stroke,
        )

        when (kind) {
            GlyphKind.Power -> {
                arc(12f, 13f, 7.5f, 300f, 300f)
                line(12f, 3f, 12f, 11.5f)
            }
            GlyphKind.Shield -> {
                val p = Path().apply {
                    moveTo(ox + 12f * u, oy + 2.8f * u)
                    lineTo(ox + 19.5f * u, oy + 5.6f * u)
                    lineTo(ox + 19.5f * u, oy + 11.5f * u)
                    cubicTo(ox + 19.5f * u, oy + 16.3f * u, ox + 16.4f * u, oy + 19.9f * u, ox + 12f * u, oy + 21.4f * u)
                    cubicTo(ox + 7.6f * u, oy + 19.9f * u, ox + 4.5f * u, oy + 16.3f * u, ox + 4.5f * u, oy + 11.5f * u)
                    lineTo(ox + 4.5f * u, oy + 5.6f * u)
                    close()
                }
                drawPath(p, tint, style = stroke)
                poly(8.8f, 12.2f, 11.2f, 14.6f, 15.4f, 9.6f)
            }
            GlyphKind.Sliders -> {
                line(4f, 6f, 13f, 6f); line(17f, 6f, 20f, 6f); ring(15f, 6f, 2f)
                line(4f, 12f, 7f, 12f); line(11f, 12f, 20f, 12f); ring(9f, 12f, 2f)
                line(4f, 18f, 11f, 18f); line(15f, 18f, 20f, 18f); ring(13f, 18f, 2f)
            }
            GlyphKind.Terminal -> {
                box(3f, 4.5f, 21f, 19.5f, 3f)
                poly(7.5f, 9.5f, 10.5f, 12f, 7.5f, 14.5f)
                line(12.5f, 14.5f, 16.5f, 14.5f)
            }
            GlyphKind.Signal -> {
                arc(12f, 18.5f, 4f, 225f, 90f)
                arc(12f, 18.5f, 8.5f, 225f, 90f)
                arc(12f, 18.5f, 13f, 225f, 90f)
                dot(12f, 18.5f, 1.5f)
            }
            GlyphKind.Stack -> {
                box(4f, 5f, 20f, 10.5f, 2.2f)
                box(4f, 13.5f, 20f, 19f, 2.2f)
                dot(7.6f, 7.75f, 1f)
                dot(7.6f, 16.25f, 1f)
                line(11f, 7.75f, 16.5f, 7.75f)
                line(11f, 16.25f, 16.5f, 16.25f)
            }
            GlyphKind.Wave -> {
                val p = Path().apply {
                    moveTo(ox + 2.5f * u, oy + 12f * u)
                    cubicTo(ox + 5.5f * u, oy + 4.5f * u, ox + 8.8f * u, oy + 4.5f * u, ox + 12f * u, oy + 12f * u)
                    cubicTo(ox + 15.2f * u, oy + 19.5f * u, ox + 18.5f * u, oy + 19.5f * u, ox + 21.5f * u, oy + 12f * u)
                }
                drawPath(p, tint, style = stroke)
            }
            GlyphKind.Nested -> {
                box(3f, 3f, 21f, 21f, 5.5f)
                box(8f, 8f, 16f, 16f, 2.6f)
            }
            GlyphKind.Check -> poly(5.5f, 12.5f, 10f, 17f, 18.5f, 7.5f)
            GlyphKind.Chevron -> poly(9.5f, 6f, 15.5f, 12f, 9.5f, 18f)
            GlyphKind.Down -> {
                line(12f, 5f, 12f, 19f)
                poly(6.5f, 13.5f, 12f, 19f, 17.5f, 13.5f)
            }
            GlyphKind.Up -> {
                line(12f, 19f, 12f, 5f)
                poly(6.5f, 10.5f, 12f, 5f, 17.5f, 10.5f)
            }
            GlyphKind.Pulse -> poly(2.5f, 12f, 7f, 12f, 9.5f, 6f, 14f, 18.5f, 16.5f, 12f, 21.5f, 12f)
            GlyphKind.Radar -> {
                ring(12f, 12f, 8.5f)
                ring(12f, 12f, 4f)
                line(12f, 12f, 18f, 6f)
                dot(12f, 12f, 1.2f)
            }
            GlyphKind.Mask -> {
                val p = Path().apply {
                    moveTo(ox + 3f * u, oy + 9f * u)
                    cubicTo(ox + 3f * u, oy + 7f * u, ox + 5f * u, oy + 6f * u, ox + 7f * u, oy + 6.5f * u)
                    lineTo(ox + 12f * u, oy + 8f * u)
                    lineTo(ox + 17f * u, oy + 6.5f * u)
                    cubicTo(ox + 19f * u, oy + 6f * u, ox + 21f * u, oy + 7f * u, ox + 21f * u, oy + 9f * u)
                    lineTo(ox + 21f * u, oy + 11f * u)
                    cubicTo(ox + 21f * u, oy + 15f * u, ox + 18f * u, oy + 17.5f * u, ox + 15.5f * u, oy + 16.5f * u)
                    cubicTo(ox + 14f * u, oy + 16f * u, ox + 13f * u, oy + 14.5f * u, ox + 12f * u, oy + 14.5f * u)
                    cubicTo(ox + 11f * u, oy + 14.5f * u, ox + 10f * u, oy + 16f * u, ox + 8.5f * u, oy + 16.5f * u)
                    cubicTo(ox + 6f * u, oy + 17.5f * u, ox + 3f * u, oy + 15f * u, ox + 3f * u, oy + 11f * u)
                    close()
                }
                drawPath(p, tint, style = stroke)
                dot(8f, 11f, 1.5f)
                dot(16f, 11f, 1.5f)
            }
            GlyphKind.Globe -> {
                ring(12f, 12f, 9f)
                drawOval(tint, pt(8f, 3f), Size(8f * u, 18f * u), style = stroke)
                line(3f, 12f, 21f, 12f)
            }
            GlyphKind.Copy -> {
                box(8.5f, 8.5f, 20f, 20f, 2.5f)
                val p = Path().apply {
                    moveTo(ox + 4f * u, oy + 14.5f * u)
                    lineTo(ox + 4f * u, oy + 6.5f * u)
                    cubicTo(ox + 4f * u, oy + 5.1f * u, ox + 5.1f * u, oy + 4f * u, ox + 6.5f * u, oy + 4f * u)
                    lineTo(ox + 14.5f * u, oy + 4f * u)
                }
                drawPath(p, tint, style = stroke)
            }
            GlyphKind.Trash -> {
                line(4f, 7f, 20f, 7f)
                poly(9f, 7f, 9.5f, 4.5f, 14.5f, 4.5f, 15f, 7f)
                poly(6f, 7f, 7.2f, 20f, 16.8f, 20f, 18f, 7f)
            }
            GlyphKind.Clock -> {
                ring(12f, 12f, 9f)
                poly(12f, 7f, 12f, 12f, 15.5f, 14f)
            }
            GlyphKind.Key -> {
                ring(7.5f, 12f, 4f)
                line(11.5f, 12f, 21f, 12f)
                line(18f, 12f, 18f, 15f)
                line(15f, 12f, 15f, 14.5f)
            }
            GlyphKind.Code -> {
                poly(9.5f, 7.5f, 5f, 12f, 9.5f, 16.5f)
                poly(14.5f, 7.5f, 19f, 12f, 14.5f, 16.5f)
                line(13.2f, 6f, 10.8f, 18f)
            }
            GlyphKind.Close -> {
                line(6f, 6f, 18f, 18f)
                line(18f, 6f, 6f, 18f)
            }
            GlyphKind.Bolt -> poly(13f, 2.5f, 5f, 13.5f, 11.5f, 13.5f, 10.5f, 21.5f, 19f, 10f, 12.5f, 10f, closed = true)
            GlyphKind.Plug -> {
                line(9f, 3f, 9f, 7f)
                line(15f, 3f, 15f, 7f)
                val p = Path().apply {
                    moveTo(ox + 6f * u, oy + 7f * u)
                    lineTo(ox + 18f * u, oy + 7f * u)
                    lineTo(ox + 18f * u, oy + 11f * u)
                    cubicTo(ox + 18f * u, oy + 14.5f * u, ox + 15.5f * u, oy + 17f * u, ox + 12f * u, oy + 17f * u)
                    cubicTo(ox + 8.5f * u, oy + 17f * u, ox + 6f * u, oy + 14.5f * u, ox + 6f * u, oy + 11f * u)
                    close()
                }
                drawPath(p, tint, style = stroke)
                line(12f, 17f, 12f, 21f)
            }
        }
    }
}
