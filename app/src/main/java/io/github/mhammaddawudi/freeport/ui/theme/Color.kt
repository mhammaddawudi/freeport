package io.github.mhammaddawudi.freeport.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The colours that change with the theme.
 *
 * Light is not the dark palette inverted: the accents are re-picked so they
 * keep their contrast against a near-white ground, where the neon versions
 * would wash out. Nothing in the light palette is black — the darkest value
 * is the ink used for text.
 */
@Immutable
data class WarpPalette(
    val void: Color,
    val abyss: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val hairline: Color,
    val hairlineStrong: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val cyan: Color,
    val selected: Color,
    val control: Color,
    val aqua: Color,
    val mint: Color,
    val violet: Color,
    val indigo: Color,
    val amber: Color,
    val coral: Color,
    val rose: Color,
    /** Magenta: the colour of a session coming up. */
    val flare: Color,
    /** What raised surfaces cast; a black shadow on a light ground reads as dirt. */
    val shadow: Color,
    /** The top of the update card's gradient, a shade off the raised surface. */
    val sheetTop: Color,
    val isDark: Boolean,
)

/**
 * Deep space with an aurora running through it: violet, cyan and mint, the
 * colours of the warp field behind the portal. The states keep their old
 * meaning — violet at rest, magenta while a session comes up, mint once it
 * carries traffic — so they read at a glance without a word on screen.
 */
val DarkPalette = WarpPalette(
    void = Color(0xFF04040B),
    abyss = Color(0xFF080816),
    surface = Color(0xFF0D0D1D),
    surfaceRaised = Color(0xFF16162B),
    hairline = Color(0x1FFFFFFF),
    hairlineStrong = Color(0x47FFFFFF),
    ink = Color(0xFFF4F4FF),
    inkMuted = Color(0xFFA6A6C6),
    inkFaint = Color(0xFF60607F),

    mint = Color(0xFF2EF2A0),
    cyan = Color(0xFFE8E8F6),
    selected = Color(0xFFA78BFA),
    control = Color(0xFFE9E9F5),
    aqua = Color(0xFF22D3EE),

    violet = Color(0xFF8B5CFF),
    indigo = Color(0xFF4F7CFF),

    amber = Color(0xFFF2C46B),
    coral = Color(0xFFFF7AB6),
    rose = Color(0xFFFF4D6D),
    flare = Color(0xFFFF4FD8),
    shadow = Color(0xFF000000),
    sheetTop = Color(0xFF22223A),
    isDark = true,
)

/** Day: pearl white, with the same aurora in deeper tones that hold on white. */
val LightPalette = WarpPalette(
    void = Color(0xFFF3F2FA),
    abyss = Color(0xFFEAE8F6),
    surface = Color(0xFFF9F8FE),
    surfaceRaised = Color(0xFFFFFFFF),
    hairline = Color(0x1A14102A),
    hairlineStrong = Color(0x3D14102A),
    ink = Color(0xFF14122A),
    inkMuted = Color(0xFF5A5874),
    inkFaint = Color(0xFF8D8BA6),

    mint = Color(0xFF07A36A),
    cyan = Color(0xFF221F3A),
    selected = Color(0xFF6D3CF5),
    control = Color(0xFF1B1930),
    aqua = Color(0xFF0891B2),

    violet = Color(0xFF6D3CF5),
    indigo = Color(0xFF3B5BDB),

    amber = Color(0xFFB7790A),
    coral = Color(0xFFDB2777),
    rose = Color(0xFFD9344A),
    flare = Color(0xFFC026D3),
    shadow = Color(0xFF3A3A55),
    sheetTop = Color(0xFFFFFFFF),
    isDark = false,
)

val LocalWarpPalette = staticCompositionLocalOf { DarkPalette }

/**
 * Call sites read colours from here.
 *
 * Every colour resolves through the palette in force, which is why these are
 * composable getters rather than plain constants.
 */
object WarpColors {

    val Void: Color @Composable @ReadOnlyComposable get() = LocalWarpPalette.current.void
    val Abyss: Color @Composable @ReadOnlyComposable get() = LocalWarpPalette.current.abyss
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalWarpPalette.current.surface
    val SurfaceRaised: Color @Composable @ReadOnlyComposable get() = LocalWarpPalette.current.surfaceRaised
    val Hairline: Color @Composable @ReadOnlyComposable get() = LocalWarpPalette.current.hairline
    val HairlineStrong: Color @Composable @ReadOnlyComposable get() = LocalWarpPalette.current.hairlineStrong
    val Ink: Color @Composable @ReadOnlyComposable get() = LocalWarpPalette.current.ink
    val InkMuted: Color @Composable @ReadOnlyComposable get() = LocalWarpPalette.current.inkMuted
    val InkFaint: Color @Composable @ReadOnlyComposable get() = LocalWarpPalette.current.inkFaint
    val Cyan: Color @Composable @ReadOnlyComposable get() = LocalWarpPalette.current.cyan
    val Selected: Color @Composable @ReadOnlyComposable get() = LocalWarpPalette.current.selected
    val Control: Color @Composable @ReadOnlyComposable get() = LocalWarpPalette.current.control
    val Aqua: Color @Composable @ReadOnlyComposable get() = LocalWarpPalette.current.aqua
    val Mint: Color @Composable @ReadOnlyComposable get() = LocalWarpPalette.current.mint
    val Violet: Color @Composable @ReadOnlyComposable get() = LocalWarpPalette.current.violet
    val Indigo: Color @Composable @ReadOnlyComposable get() = LocalWarpPalette.current.indigo
    val Amber: Color @Composable @ReadOnlyComposable get() = LocalWarpPalette.current.amber
    val Coral: Color @Composable @ReadOnlyComposable get() = LocalWarpPalette.current.coral
    val Rose: Color @Composable @ReadOnlyComposable get() = LocalWarpPalette.current.rose
    val Flare: Color @Composable @ReadOnlyComposable get() = LocalWarpPalette.current.flare
    val Shadow: Color @Composable @ReadOnlyComposable get() = LocalWarpPalette.current.shadow
    val SheetTop: Color @Composable @ReadOnlyComposable get() = LocalWarpPalette.current.sheetTop
    val IsDark: Boolean @Composable @ReadOnlyComposable get() = LocalWarpPalette.current.isDark
}

/** The brand's aurora, left to right: violet, cyan, mint. */
object Aurora {
    @Composable
    @ReadOnlyComposable
    fun colors(): List<Color> {
        val p = LocalWarpPalette.current
        return listOf(p.violet, p.aqua, p.mint)
    }
}
