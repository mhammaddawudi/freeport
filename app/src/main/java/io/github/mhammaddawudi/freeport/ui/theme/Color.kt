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
data class AetherPalette(
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
val DarkPalette = AetherPalette(
    void = Color(0xFF0E0E12),
    abyss = Color(0xFF131318),
    surface = Color(0xFF16161C),
    surfaceRaised = Color(0xFF1E1E25),
    hairline = Color(0x26FFFFFF),
    hairlineStrong = Color(0x52FFFFFF),
    ink = Color(0xFFF4F4F6),
    inkMuted = Color(0xFF9B9BA6),
    inkFaint = Color(0xFF5E5E6B),

    // connected: green, the one thing that has to be readable at a glance
    mint = Color(0xFF42D98A),
    cyan = Color(0xFFE6E7EC),
    // and the colour that marks a chosen protocol
    selected = Color(0xFFC08BFF),
    // what a chosen tab or switch is tinted with: the logo's white, softened
    // so a filled pill does not glare on a near-black ground
    control = Color(0xFFCFCFD8),
    aqua = Color(0xFF7BE8B4),

    // at rest
    violet = Color(0xFF8C8C99),
    indigo = Color(0xFF6E6E7B),

    // working, and gone wrong
    amber = Color(0xFFCBB68C),
    coral = Color(0xFFC79A86),
    rose = Color(0xFFE0616B),
)

val LocalAetherPalette = staticCompositionLocalOf { DarkPalette }

/**
 * Call sites read colours from here.
 *
 * Every colour resolves through the palette in force, which is why these are
 * composable getters rather than plain constants.
 */
object AetherColors {

    val Void: Color @Composable @ReadOnlyComposable get() = LocalAetherPalette.current.void
    val Abyss: Color @Composable @ReadOnlyComposable get() = LocalAetherPalette.current.abyss
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalAetherPalette.current.surface
    val SurfaceRaised: Color @Composable @ReadOnlyComposable get() = LocalAetherPalette.current.surfaceRaised
    val Hairline: Color @Composable @ReadOnlyComposable get() = LocalAetherPalette.current.hairline
    val HairlineStrong: Color @Composable @ReadOnlyComposable get() = LocalAetherPalette.current.hairlineStrong
    val Ink: Color @Composable @ReadOnlyComposable get() = LocalAetherPalette.current.ink
    val InkMuted: Color @Composable @ReadOnlyComposable get() = LocalAetherPalette.current.inkMuted
    val InkFaint: Color @Composable @ReadOnlyComposable get() = LocalAetherPalette.current.inkFaint
    val Cyan: Color @Composable @ReadOnlyComposable get() = LocalAetherPalette.current.cyan
    val Selected: Color @Composable @ReadOnlyComposable get() = LocalAetherPalette.current.selected
    val Control: Color @Composable @ReadOnlyComposable get() = LocalAetherPalette.current.control
    val Aqua: Color @Composable @ReadOnlyComposable get() = LocalAetherPalette.current.aqua
    val Mint: Color @Composable @ReadOnlyComposable get() = LocalAetherPalette.current.mint
    val Violet: Color @Composable @ReadOnlyComposable get() = LocalAetherPalette.current.violet
    val Indigo: Color @Composable @ReadOnlyComposable get() = LocalAetherPalette.current.indigo
    val Amber: Color @Composable @ReadOnlyComposable get() = LocalAetherPalette.current.amber
    val Coral: Color @Composable @ReadOnlyComposable get() = LocalAetherPalette.current.coral
    val Rose: Color @Composable @ReadOnlyComposable get() = LocalAetherPalette.current.rose
}
