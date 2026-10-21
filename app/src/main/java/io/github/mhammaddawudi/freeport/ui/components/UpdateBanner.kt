package io.github.mhammaddawudi.freeport.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mhammaddawudi.freeport.R
import io.github.mhammaddawudi.freeport.ui.UpdateUi
import io.github.mhammaddawudi.freeport.ui.theme.AetherColors

/**
 * Floats above the tab bar when a newer release is out, the way Telegram
 * offers its own updates: what it is, how big, and two choices — now or
 * later. Downloading happens in place, with the progress across the card.
 */
@Composable
fun UpdateBanner(
    update: UpdateUi,
    onInstall: () -> Unit,
    onLater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keeps the last content while the card slides away.
    var shown by remember { mutableStateOf(update) }
    if (update !is UpdateUi.Hidden) shown = update
    AnimatedVisibility(
        visible = update !is UpdateUi.Hidden,
        enter = slideInVertically(spring(dampingRatio = 0.8f, stiffness = 380f)) { it } + fadeIn(tween(200)),
        exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(160)),
        modifier = modifier,
    ) {
        val current = shown
        if (current !is UpdateUi.Hidden) Card(current, onInstall, onLater)
    }
}

@Composable
private fun Card(update: UpdateUi, onInstall: () -> Unit, onLater: () -> Unit) {
    val info = when (update) {
        is UpdateUi.Available -> update.update
        is UpdateUi.Downloading -> update.update
        is UpdateUi.Ready -> update.update
        is UpdateUi.Failed -> update.update
        UpdateUi.Hidden -> return
    }
    val progress = (update as? UpdateUi.Downloading)?.progress
    val shownProgress by animateFloatAsState(progress ?: 0f, tween(200), label = "updateProgress")
    val shape = RoundedCornerShape(24.dp)

    Box(
        Modifier
            .fillMaxWidth()
            .shadow(24.dp, shape, spotColor = Color.Black)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF23232D), AetherColors.SurfaceRaised)))
            .border(1.dp, Brush.verticalGradient(listOf(AetherColors.Ink.copy(alpha = 0.22f), AetherColors.Ink.copy(alpha = 0.05f))), shape)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        if (progress != null) {
            // Sized to the card, not to whatever room the card was offered:
            // fillMaxHeight on its own stretched it down the whole screen.
            Box(Modifier.matchParentSize()) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(shownProgress)
                        .background(AetherColors.Mint.copy(alpha = 0.10f)),
                )
            }
        }
        Row(
            Modifier.padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(AetherColors.Void)
                    .border(1.dp, AetherColors.Hairline, RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Image(painterResource(R.mipmap.ic_launcher_foreground), contentDescription = null, Modifier.size(56.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.update_title, info.version),
                    style = MaterialTheme.typography.titleSmall,
                    color = AetherColors.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    when (update) {
                        is UpdateUi.Downloading -> stringResource(R.string.update_downloading, (update.progress * 100).toInt())
                        is UpdateUi.Ready -> stringResource(R.string.update_ready_subtitle)
                        is UpdateUi.Failed -> stringResource(R.string.update_failed, update.message)
                        else -> stringResource(R.string.update_subtitle, Formats.bytes(info.apkBytes))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (update is UpdateUi.Failed) AetherColors.Rose else AetherColors.InkMuted,
                    maxLines = if (update is UpdateUi.Failed) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (update !is UpdateUi.Downloading) {
                Text(
                    stringResource(R.string.update_later),
                    style = MaterialTheme.typography.labelLarge,
                    color = AetherColors.InkMuted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(role = Role.Button, onClick = onLater)
                        .heightIn(min = 40.dp)
                        .padding(horizontal = 10.dp, vertical = 11.dp),
                )
            }
            PillButton(
                text = stringResource(
                    when (update) {
                        is UpdateUi.Downloading -> R.string.update_later
                        is UpdateUi.Ready -> R.string.update_ready
                        is UpdateUi.Failed -> R.string.update_retry
                        else -> R.string.update_install
                    },
                ),
                emphasised = update !is UpdateUi.Downloading,
                // Mid-download the button stops the download and defers it.
                onClick = if (update is UpdateUi.Downloading) onLater else onInstall,
            )
        }
    }
}
