package io.github.mhammaddawudi.freeport.ui.components

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.mhammaddawudi.freeport.R
import io.github.mhammaddawudi.freeport.ui.theme.Aurora
import io.github.mhammaddawudi.freeport.ui.theme.WarpColors
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.lerp

enum class WarpTab(@StringRes val label: Int, val glyph: GlyphKind) {
    Home(R.string.tab_home, GlyphKind.Shield),
    Settings(R.string.tab_settings, GlyphKind.Sliders),
    Logs(R.string.tab_logs, GlyphKind.Terminal),
}

/** A floating pill; the active tab expands to show its name. */
@Composable
fun FloatingTabBar(
    selected: WarpTab,
    onSelect: (WarpTab) -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier = modifier
            .shadow(24.dp, shape, ambientColor = WarpColors.Shadow, spotColor = WarpColors.Shadow.copy(alpha = if (WarpColors.IsDark) 1f else 0.25f))
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(WarpColors.SurfaceRaised.copy(alpha = 0.92f), WarpColors.Surface.copy(alpha = 0.92f)),
                ),
            )
            .border(
                1.dp,
                Brush.linearGradient(listOf(WarpColors.Violet.copy(alpha = 0.5f), WarpColors.Ink.copy(alpha = 0.06f), WarpColors.Aqua.copy(alpha = 0.35f))),
                shape,
            )
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WarpTab.entries.forEach { tab ->
            val active = tab == selected
            // A solid pill with dark content, rather than a translucent white
            // wash under white text: the old pair sat a few percent apart and
            // read as washed out instead of selected.
            // The chosen tab is a pill of aurora with dark text on it.
            val fill by animateFloatAsState(if (active) 1f else 0f, tween(260), label = "tabFill")
            val pill = Aurora.colors().map { lerp(it, Color.White, 0.35f).copy(alpha = fill) }
            val foreground by animateColorAsState(
                if (active) Color(0xFF0A0A18) else WarpColors.InkMuted, tween(240), label = "tabFg",
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .background(Brush.horizontalGradient(pill))
                    .clickable(role = Role.Tab) { onSelect(tab) }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph(tab.glyph, foreground, Modifier.size(20.dp))
                AnimatedVisibility(
                    visible = active,
                    enter = expandHorizontally() + fadeIn(),
                    exit = shrinkHorizontally() + fadeOut(),
                ) {
                    Row {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(tab.label),
                            style = MaterialTheme.typography.labelLarge,
                            color = foreground,
                            maxLines = 1,
                                    )
                    }
                }
            }
        }
    }
}
