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
    /** What raised surfaces cast; a black shadow on a light ground reads as dirt. */
    val shadow: Color,
    /** The top of the update card's gradient, a shade off the raised surface. */
    val sheetTop: Color,
    val isDark: Boolean,
)

/**
 * Taken from the logo: a white mark on a near-black tile, and nothing else.
 *
 * The accent slots keep their old names so call sites did not all have to
 * change, but they are now a brightness ladder rather than different hues —
 * which is also what separates the connect states: grey at rest, warm while
 * a session comes up, white once it is carrying traffic. Only the warning
 * and error tones hold any real colour, because those have to be read as
 * warnings rather than as decoration.
 */
val DarkPalette = WarpPalette(
    // A cool near-black rather than neutral grey: it reads as depth, and
    // the white mark and the green "connected" both sit brighter on it.
    void = Color(0xFF08080C),
    abyss = Color(0xFF0D0D12),
    surface = Color(0xFF121218),
    surfaceRaised = Color(0xFF1A1A22),
    hairline = Color(0x1FFFFFFF),
    hairlineStrong = Color(0x47FFFFFF),
    ink = Color(0xFFF5F5F8),
    inkMuted = Color(0xFFA0A0AD),
    inkFaint = Color(0xFF62626F),

    // connected: green, the one thing that has to be readable at a glance
    mint = Color(0xFF3EE08F),
    cyan = Color(0xFFE6E7EC),
    // and the colour that marks a chosen protocol
    selected = Color(0xFFB98CFF),
    // what a chosen tab or switch is tinted with: the logo's white, softened
    // so a filled pill does not glare on a near-black ground
    control = Color(0xFFE9E9F0),
    aqua = Color(0xFF7CEBC0),

    // at rest
    violet = Color(0xFF8C8C99),
    indigo = Color(0xFF6E6E7B),

    // working, and gone wrong
    amber = Color(0xFFF2C46B),
    coral = Color(0xFFE39A7A),
    rose = Color(0xFFFF5F6D),
    shadow = Color(0xFF000000),
    sheetTop = Color(0xFF23232D),
    isDark = true,
)

/**
 * Day: paper white with the same ladder of greys, ink for text and for the
 * chosen tab, and accents deep enough to hold their contrast on white.
 */
val LightPalette = WarpPalette(
    void = Color(0xFFF2F2F6),
    abyss = Color(0xFFEAEAF0),
    surface = Color(0xFFF9F9FB),
    surfaceRaised = Color(0xFFFFFFFF),
    hairline = Color(0x1A10101A),
    hairlineStrong = Color(0x3D10101A),
    ink = Color(0xFF15151C),
    inkMuted = Color(0xFF5A5A68),
    inkFaint = Color(0xFF8C8C9A),

    mint = Color(0xFF0B9A5B),
    cyan = Color(0xFF22222B),
    selected = Color(0xFF7446DB),
    control = Color(0xFF1B1B23),
    aqua = Color(0xFF0E9C7A),

    violet = Color(0xFF6C6C7A),
    indigo = Color(0xFF9C9CAA),

    amber = Color(0xFFB7790A),
    coral = Color(0xFFC9623C),
    rose = Color(0xFFD9344A),
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
    val Shadow: Color @Composable @ReadOnlyComposable get() = LocalWarpPalette.current.shadow
    val SheetTop: Color @Composable @ReadOnlyComposable get() = LocalWarpPalette.current.sheetTop
    val IsDark: Boolean @Composable @ReadOnlyComposable get() = LocalWarpPalette.current.isDark
}
