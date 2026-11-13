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
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class GlyphKind {
    Power, Shield, Sliders, Terminal,
    Signal, Stack, Wave, Nested,
    Check, Chevron, Down, Up, Pulse, Radar, Mask, Globe,
    Copy, Trash, Clock, Key, Close, Bolt, Plug, Code,
    Sparkle, H3, H2, Serpent, CloudNested, LetterP, Onion, V2Ray,
}

/**
 * The protocol marks, two-tone: a soft tinted body, the outline over it and
 * solid accents. SVG path data on the 24 grid, shared verbatim with the
 * desktop app (desktop/app/ui/glyphs.js, generated from the same table).
 */
private enum class Layer { Soft, Stroke, Fill }

private val PROTOCOL_LAYERS: Map<GlyphKind, List<Pair<Layer, String>>> = mapOf(
    GlyphKind.Sparkle to listOf(
        Layer.Soft to "M20.5 12A8.5 8.5 0 1 1 3.5 12A8.5 8.5 0 1 1 20.5 12Z",
        Layer.Stroke to "M19.4 8.2A8.5 8.5 0 1 0 20.5 12M20.6 4.8L19.4 8.2L16 7.2",
        Layer.Fill to "M12 7.4Q12.69 11.31 16.6 12Q12.69 12.69 12 16.6Q11.31 12.69 7.4 12Q11.31 11.31 12 7.4Z",
        Layer.Fill to "M17.6 15.1Q17.86 16.55 19.3 16.8Q17.86 17.05 17.6 18.5Q17.35 17.05 15.9 16.8Q17.35 16.55 17.6 15.1Z",
        Layer.Fill to "M7.3 7.4A0.9 0.9 0 1 1 5.5 7.4A0.9 0.9 0 1 1 7.3 7.4Z",
    ),
    GlyphKind.H3 to listOf(
        Layer.Soft to "M3 8C3 6.2 4.8 5.5 6.8 5.5C9 5.5 10.4 7 12 7C13.6 7 15 5.5 17.2 5.5C19.2 5.5 21 6.2 21 8C21 12 19 14.5 16.5 14.5C14.5 14.5 13.5 12.5 12 12.5C10.5 12.5 9.5 14.5 7.5 14.5C5 14.5 3 12 3 8Z",
        Layer.Stroke to "M3 8C3 6.2 4.8 5.5 6.8 5.5C9 5.5 10.4 7 12 7C13.6 7 15 5.5 17.2 5.5C19.2 5.5 21 6.2 21 8C21 12 19 14.5 16.5 14.5C14.5 14.5 13.5 12.5 12 12.5C10.5 12.5 9.5 14.5 7.5 14.5C5 14.5 3 12 3 8ZM3.1 7.2L1.5 6M20.9 7.2L22.5 6",
        Layer.Fill to "M5.6 9.5Q7.6 7.4 9.6 9.5Q7.6 11.1 5.6 9.5ZM14.4 9.5Q16.4 7.4 18.4 9.5Q16.4 11.1 14.4 9.5Z",
        Layer.Stroke to "M7 19H8.6M11.2 19H12.8M15.4 19H17",
    ),
    GlyphKind.H2 to listOf(
        Layer.Soft to "M3 8C3 6.2 4.8 5.5 6.8 5.5C9 5.5 10.4 7 12 7C13.6 7 15 5.5 17.2 5.5C19.2 5.5 21 6.2 21 8C21 12 19 14.5 16.5 14.5C14.5 14.5 13.5 12.5 12 12.5C10.5 12.5 9.5 14.5 7.5 14.5C5 14.5 3 12 3 8Z",
        Layer.Stroke to "M3 8C3 6.2 4.8 5.5 6.8 5.5C9 5.5 10.4 7 12 7C13.6 7 15 5.5 17.2 5.5C19.2 5.5 21 6.2 21 8C21 12 19 14.5 16.5 14.5C14.5 14.5 13.5 12.5 12 12.5C10.5 12.5 9.5 14.5 7.5 14.5C5 14.5 3 12 3 8ZM3.1 7.2L1.5 6M20.9 7.2L22.5 6",
        Layer.Fill to "M5.6 9.5Q7.6 7.4 9.6 9.5Q7.6 11.1 5.6 9.5ZM14.4 9.5Q16.4 7.4 18.4 9.5Q16.4 11.1 14.4 9.5Z",
        Layer.Stroke to "M9.1 19H10.7M13.3 19H14.9",
    ),
    GlyphKind.Serpent to listOf(
        Layer.Soft to "M12 2.8L20 7.4V16.6L12 21.2L4 16.6V7.4Z",
        Layer.Stroke to "M12 2.8L20 7.4V16.6L12 21.2L4 16.6V7.4ZM14.8 8.6C12.6 7.4 9.4 8.2 9.6 10.2C9.8 12.2 14.4 11.6 14.4 14C14.4 16 11.4 16.6 9.2 15.3",
        Layer.Fill to "M16.5 8.9A1.2 1.2 0 1 1 14.1 8.9A1.2 1.2 0 1 1 16.5 8.9ZM9.9 15A1.2 1.2 0 1 1 7.5 15A1.2 1.2 0 1 1 9.9 15Z",
    ),
    GlyphKind.CloudNested to listOf(
        Layer.Soft to "M12 2.5L21.5 12L12 21.5L2.5 12Z",
        Layer.Stroke to "M12 2.5L21.5 12L12 21.5L2.5 12ZM12 7.2L16.8 12L12 16.8L7.2 12Z",
        Layer.Fill to "M12 10L14 12L12 14L10 12Z",
    ),
    GlyphKind.V2Ray to listOf(
        Layer.Soft to "M4 8.5H8.6L12 14.6L15.4 8.5H20L12 21Z",
        Layer.Stroke to "M4 8.5H8.6L12 14.6L15.4 8.5H20L12 21ZM4.6 3.8L6 5.2M19.4 3.8L18 5.2",
        Layer.Fill to "M12 1.8Q12.48 4.52 15.2 5Q12.48 5.48 12 8.2Q11.52 5.48 8.8 5Q11.52 4.52 12 1.8Z",
    ),
    GlyphKind.LetterP to listOf(
        Layer.Soft to "M21 12A9 9 0 1 1 3 12A9 9 0 1 1 21 12Z",
        Layer.Stroke to "M21 12A9 9 0 1 1 3 12A9 9 0 1 1 21 12ZM9.6 17.2V6.8H13.2A3.1 3.1 0 0 1 13.2 13H9.6",
        Layer.Soft to "M9.6 6.8H13.2A3.1 3.1 0 0 1 13.2 13H9.6Z",
        Layer.Fill to "M18.9 17.8A1.1 1.1 0 1 1 16.7 17.8A1.1 1.1 0 1 1 18.9 17.8Z",
    ),
    GlyphKind.Onion to listOf(
        Layer.Soft to "M12 4.2C12.3 5.8 13.3 6.9 15 8C17.6 9.7 19.3 12 19.3 15C19.3 18.7 16.1 21.2 12 21.2C7.9 21.2 4.7 18.7 4.7 15C4.7 12 6.4 9.7 9 8C10.7 6.9 11.7 5.8 12 4.2Z",
        Layer.Stroke to "M12 4.2C12.3 5.8 13.3 6.9 15 8C17.6 9.7 19.3 12 19.3 15C19.3 18.7 16.1 21.2 12 21.2C7.9 21.2 4.7 18.7 4.7 15C4.7 12 6.4 9.7 9 8C10.7 6.9 11.7 5.8 12 4.2ZM12 10C10.1 11.5 8.9 13.2 8.9 15.2C8.9 17.4 10.3 18.6 12 18.6C13.7 18.6 15.1 17.4 15.1 15.2C15.1 13.2 13.9 11.5 12 10Z",
        Layer.Fill to "M12 4.2C12.4 2.6 14 1.6 16 1.9C15.4 3.6 13.8 4.6 12 4.2ZM12 13.2C11.2 14 10.9 14.7 10.9 15.4C10.9 16.2 11.4 16.7 12 16.7C12.6 16.7 13.1 16.2 13.1 15.4C13.1 14.7 12.8 14 12 13.2Z",
    ),
)

private const val SOFT_ALPHA = 0.35f

private val parsedLayers = PROTOCOL_LAYERS.mapValues { (_, layers) ->
    layers.map { (layer, d) -> layer to PathParser().parsePathString(d).toPath() }
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
        parsedLayers[kind]?.let { layers ->
            translate(ox, oy) {
                scale(u, u, pivot = Offset.Zero) {
                    val line = Stroke(stroke.width / u, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    layers.forEach { (layer, path) ->
                        when (layer) {
                            Layer.Soft -> drawPath(path, tint.copy(alpha = tint.alpha * SOFT_ALPHA))
                            Layer.Stroke -> drawPath(path, tint, style = line)
                            Layer.Fill -> drawPath(path, tint)
                        }
                    }
                }
            }
            return@Canvas
        }

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
            GlyphKind.Sparkle, GlyphKind.H3, GlyphKind.H2, GlyphKind.Serpent, GlyphKind.CloudNested,
            GlyphKind.LetterP, GlyphKind.Onion, GlyphKind.V2Ray -> Unit
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
