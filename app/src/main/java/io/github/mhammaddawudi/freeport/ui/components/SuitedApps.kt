package io.github.mhammaddawudi.freeport.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.github.mhammaddawudi.freeport.R
import io.github.mhammaddawudi.freeport.data.Protocol
import io.github.mhammaddawudi.freeport.ui.theme.AetherColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The look of an app's badge when the app itself is not on the phone. */
enum class BrandMark { Telegram, YouTube, Instagram, TikTok, WhatsApp, Chrome, X, Games }

/**
 * An app a protocol suits, with the packages it ships under: the first one
 * installed lends its real icon.
 */
data class SuitedApp(
    val name: String,
    @StringRes val nameRes: Int? = null,
    val packages: List<String>,
    val mark: BrandMark,
)

private val Telegram = SuitedApp(
    "Telegram",
    packages = listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram"),
    mark = BrandMark.Telegram,
)
private val YouTube = SuitedApp("YouTube", packages = listOf("com.google.android.youtube"), mark = BrandMark.YouTube)
private val Instagram = SuitedApp("Instagram", packages = listOf("com.instagram.android"), mark = BrandMark.Instagram)
private val TikTok = SuitedApp(
    "TikTok",
    packages = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
    mark = BrandMark.TikTok,
)
private val WhatsApp = SuitedApp("WhatsApp", packages = listOf("com.whatsapp", "com.whatsapp.w4b"), mark = BrandMark.WhatsApp)
private val Chrome = SuitedApp("Chrome", packages = listOf("com.android.chrome"), mark = BrandMark.Chrome)
private val X = SuitedApp("X", packages = listOf("com.twitter.android"), mark = BrandMark.X)
private val Games = SuitedApp(
    "Games",
    nameRes = R.string.app_games,
    packages = listOf("com.tencent.ig", "com.activision.callofduty.shooter", "com.supercell.clashofclans"),
    mark = BrandMark.Games,
)

/**
 * What each protocol is at its best for.
 *
 * Warp in warp's two hops keep long-lived streams steady, which is what
 * Telegram and YouTube need. MASQUE H2 looks like ordinary HTTPS on TCP and
 * survives the filtering that photo and short-video feeds sit behind. MASQUE
 * over QUIC is quickest for browsing and calls, and WireGuard's low latency
 * suits games and live timelines.
 */
val Protocol.suitedApps: List<SuitedApp>
    get() = when (this) {
        Protocol.Auto -> emptyList()
        Protocol.Gool -> listOf(Telegram, YouTube)
        Protocol.MasqueH2 -> listOf(Instagram, TikTok)
        Protocol.MasqueH3 -> listOf(WhatsApp, Chrome)
        Protocol.WireGuard -> listOf(Games, X)
    }

@Composable
fun SuitedApp.label(): String = nameRes?.let { stringResource(it) } ?: name

/** Overlapping round badges, the way chat apps stack avatars. */
@Composable
fun AppBadgeRow(apps: List<SuitedApp>, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val overlap = size * 0.28f
    Row(modifier) {
        apps.forEachIndexed { index, app ->
            AppBadge(
                app = app,
                size = size,
                modifier = Modifier.offset(x = -overlap * index),
            )
        }
    }
}

@Composable
fun AppBadge(app: SuitedApp, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val icon = rememberInstalledIcon(app.packages)
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(AetherColors.Surface)
            .border(1.5.dp, AetherColors.Surface, CircleShape),
    ) {
        if (icon != null) {
            Image(icon, contentDescription = null, modifier = Modifier.size(size).clip(CircleShape))
        } else {
            Canvas(Modifier.size(size).clip(CircleShape)) { drawMark(app.mark) }
        }
    }
}

/** The icon of the first installed package, loaded off the main thread. */
@Composable
private fun rememberInstalledIcon(packages: List<String>): ImageBitmap? {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, packages) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            packages.firstNotNullOfOrNull { name ->
                runCatching { pm.getApplicationIcon(name).toBitmap(96, 96).asImageBitmap() }.getOrNull()
            }
        }
    }
    return icon
}

private fun DrawScope.drawMark(mark: BrandMark) {
    val u = size.minDimension / 24f
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    fun path(vararg xy: Float) = Path().apply {
        moveTo(xy[0] * u, xy[1] * u)
        var i = 2
        while (i < xy.size) {
            lineTo(xy[i] * u, xy[i + 1] * u)
            i += 2
        }
        close()
    }
    val white = Color.White

    when (mark) {
        BrandMark.Telegram -> {
            drawRect(Color(0xFF2AABEE))
            drawPath(path(5f, 11.6f, 18.4f, 6.4f, 16.1f, 17.6f, 11.7f, 14.5f, 9.7f, 16.6f, 9.5f, 13.4f, 15.2f, 8.7f, 8.6f, 12.9f), white)
        }
        BrandMark.YouTube -> {
            drawRect(Color(0xFFFF0033))
            drawPath(path(9.6f, 7.8f, 16.6f, 12f, 9.6f, 16.2f), white)
        }
        BrandMark.Instagram -> {
            drawRect(
                Brush.linearGradient(
                    listOf(Color(0xFFFEDA75), Color(0xFFFA7E1E), Color(0xFFD62976), Color(0xFF962FBF), Color(0xFF4F5BD5)),
                    start = p(2f, 22f),
                    end = p(22f, 2f),
                ),
            )
            val stroke = Stroke(1.7f * u)
            drawRoundRect(white, p(6.2f, 6.2f), Size(11.6f * u, 11.6f * u), CornerRadius(3.6f * u), style = stroke)
            drawCircle(white, 2.8f * u, p(12f, 12f), style = stroke)
            drawCircle(white, 0.8f * u, p(15.4f, 8.6f))
        }
        BrandMark.TikTok -> {
            drawRect(Color(0xFF111111))
            fun note(colour: Color, dx: Float, dy: Float) {
                val stroke = Stroke(2f * u, cap = StrokeCap.Round)
                drawLine(colour, p(13.2f + dx, 5.6f + dy), p(13.2f + dx, 14.6f + dy), 2f * u, StrokeCap.Round)
                drawCircle(colour, 2.4f * u, p(10.8f + dx, 14.8f + dy), style = stroke)
                drawArc(
                    colour, 180f, -90f, false,
                    topLeft = p(13.2f + dx, 3.4f + dy), size = Size(4.4f * u, 4.4f * u), style = stroke,
                )
            }
            note(Color(0xFF25F4EE), -0.7f, -0.5f)
            note(Color(0xFFFE2C55), 0.7f, 0.5f)
            note(white, 0f, 0f)
        }
        BrandMark.WhatsApp -> {
            drawRect(Color(0xFF25D366))
            drawCircle(white, 6.2f * u, p(12f, 11.6f), style = Stroke(1.6f * u))
            drawPath(path(6.4f, 18.6f, 7.4f, 14.8f, 9.6f, 17.2f), white)
            drawRoundRect(white, p(9.6f, 9.2f), Size(4.8f * u, 4.8f * u), CornerRadius(2.4f * u))
        }
        BrandMark.Chrome -> {
            val centre = p(12f, 12f)
            drawArc(Color(0xFFDB4437), 210f, 120f, true, Offset.Zero, size)
            drawArc(Color(0xFFF4B400), 330f, 120f, true, Offset.Zero, size)
            drawArc(Color(0xFF0F9D58), 90f, 120f, true, Offset.Zero, size)
            drawCircle(white, 5.2f * u, centre)
            drawCircle(Color(0xFF4285F4), 4f * u, centre)
        }
        BrandMark.X -> {
            drawRect(Color(0xFF0B0B0B))
            drawLine(white, p(7.4f, 7f), p(16.6f, 17f), 1.9f * u, StrokeCap.Round)
            drawLine(white, p(16.6f, 7f), p(7.4f, 17f), 1.2f * u, StrokeCap.Round)
        }
        BrandMark.Games -> {
            drawRect(Brush.linearGradient(listOf(Color(0xFFFFB13B), Color(0xFFFF6A00)), p(0f, 0f), p(24f, 24f)))
            drawRoundRect(white, p(4.6f, 8.4f), Size(14.8f * u, 8f * u), CornerRadius(4f * u), style = Stroke(1.6f * u))
            drawLine(white, p(8f, 10.8f), p(8f, 14f), 1.5f * u, StrokeCap.Round)
            drawLine(white, p(6.4f, 12.4f), p(9.6f, 12.4f), 1.5f * u, StrokeCap.Round)
            drawCircle(white, 0.9f * u, p(15.4f, 11.4f))
            drawCircle(white, 0.9f * u, p(17f, 13.4f))
        }
    }
}
