package io.github.mhammaddawudi.freeport.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.WindowCompat
import io.github.mhammaddawudi.freeport.data.AppLanguage
import io.github.mhammaddawudi.freeport.data.ThemeMode

private fun darkScheme(p: WarpPalette) = darkColorScheme(
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

private fun lightScheme(p: WarpPalette) = lightColorScheme(
    primary = p.cyan,
    onPrimary = p.surfaceRaised,
    secondary = p.mint,
    onSecondary = p.surfaceRaised,
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
 * Dark by default, the app's own look; day and the phone's own setting are
 * a choice in Settings. Every colour on screen comes from the palette in
 * force, so the connect button's glow follows along: on the light ground it
 * is the same light, only in deeper tones.
 */
@Composable
fun WarpTheme(
    language: AppLanguage = AppLanguage.English,
    theme: ThemeMode = ThemeMode.Dark,
    content: @Composable () -> Unit,
) {
    val dark = when (theme) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
    }
    val palette = if (dark) DarkPalette else LightPalette

    // The status and navigation bar icons have to flip with the ground under them.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    // Sorani and Persian read right to left; English and Kurmanji do not.
    CompositionLocalProvider(
        LocalWarpPalette provides palette,
        LocalLayoutDirection provides if (language.rightToLeft) {
            LayoutDirection.Rtl
        } else {
            LayoutDirection.Ltr
        },
    ) {
        MaterialTheme(
            colorScheme = if (dark) darkScheme(palette) else lightScheme(palette),
            typography = WarpTypography,
            content = content,
        )
    }
}
