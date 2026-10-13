package io.github.mhammaddawudi.freeport.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import io.github.mhammaddawudi.freeport.data.AppLanguage
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

private fun darkScheme(p: AetherPalette) = darkColorScheme(
    primary = p.cyan,
    onPrimary = p.void,
    secondary = p.mint,
    onSecondary = p.void,
    tertiary = p.violet,
    background = p.void,
    onBackground = p.ink,
    surface = p.surface,
    onSurface = p.ink,
    surfaceVariant = p.surfaceRaised,
    onSurfaceVariant = p.inkMuted,
    surfaceContainerHigh = p.surfaceRaised,
    outline = p.hairlineStrong,
    outlineVariant = p.hairline,
    error = p.rose,
)

/**
 * Always dark. The glow the connect button is built on only reads on a dark
 * ground, so there is no light variant to fall out of step with it.
 */
@Composable
fun AetherTheme(
    language: AppLanguage = AppLanguage.Kurdish,
    content: @Composable () -> Unit,
) {
    val palette = DarkPalette
    // Kurdish reads right to left; English does not.
    CompositionLocalProvider(
        LocalAetherPalette provides palette,
        LocalLayoutDirection provides if (language == AppLanguage.Kurdish) {
            LayoutDirection.Rtl
        } else {
            LayoutDirection.Ltr
        },
    ) {
        MaterialTheme(
            colorScheme = darkScheme(palette),
            typography = AetherTypography,
            content = content,
        )
    }
}
