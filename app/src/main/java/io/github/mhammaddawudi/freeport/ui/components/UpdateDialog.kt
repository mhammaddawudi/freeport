package io.github.mhammaddawudi.freeport.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mhammaddawudi.freeport.R
import io.github.mhammaddawudi.freeport.core.AppUpdate
import io.github.mhammaddawudi.freeport.core.ReleaseNotes
import io.github.mhammaddawudi.freeport.ui.UpdateUi
import io.github.mhammaddawudi.freeport.ui.theme.WarpColors

/**
 * A newer release, asked about in the middle of the screen: what it is, a
 * couple of lines on what changed, and two answers. The app behind it is
 * blurred by the caller ([isShown]) and does not take touches until one of
 * them is chosen; back counts as "not now".
 */
@Composable
fun UpdateDialog(
    update: UpdateUi,
    onDownload: () -> Unit,
    onLater: () -> Unit,
) {
    // Keeps the last content while the dialog fades away.
    var shown by remember { mutableStateOf<AppUpdate?>(null) }
    update.info()?.let { shown = it }
    val visible = isShown(update)
    if (visible) BackHandler(onBack = onLater)

    AnimatedVisibility(visible = visible, enter = fadeIn(tween(220)), exit = fadeOut(tween(180))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                // Swallows touches meant for the app underneath.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = scaleIn(spring(dampingRatio = 0.75f, stiffness = 420f), initialScale = 0.9f),
                exit = scaleOut(tween(160), targetScale = 0.95f),
            ) {
                shown?.let { Card(it, update, onDownload, onLater) }
            }
        }
    }
}

/** Whether the dialog is up, so the caller can blur what is behind it. */
fun isShown(update: UpdateUi): Boolean = update !is UpdateUi.Hidden

private fun UpdateUi.info(): AppUpdate? = when (this) {
    is UpdateUi.Available -> update
    is UpdateUi.Downloading -> update
    is UpdateUi.Ready -> update
    is UpdateUi.Failed -> update
    UpdateUi.Hidden -> null
}

@Composable
private fun Card(info: AppUpdate, stage: UpdateUi, onDownload: () -> Unit, onLater: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    val language = LocalConfiguration.current.locales[0].language
    val notes = remember(info.notes, language) { ReleaseNotes.summary(info.notes, language) }
    val title = stringResource(R.string.update_title, info.version)

    Column(
        Modifier
            .padding(horizontal = 28.dp)
            .widthIn(max = 380.dp)
            .fillMaxWidth()
            .shadow(32.dp, shape, ambientColor = WarpColors.Shadow, spotColor = WarpColors.Shadow)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(WarpColors.SheetTop, WarpColors.SurfaceRaised)))
            .border(1.dp, Brush.verticalGradient(listOf(WarpColors.Ink.copy(alpha = 0.22f), WarpColors.Ink.copy(alpha = 0.05f))), shape)
            .semantics {
                paneTitle = title
                liveRegion = LiveRegionMode.Polite
            }
            .padding(horizontal = 22.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(WarpColors.Void)
                .border(1.dp, WarpColors.Hairline, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(R.mipmap.ic_launcher_foreground), contentDescription = null, Modifier.size(84.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = WarpColors.Ink, textAlign = TextAlign.Center)
        if (notes.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                notes,
                style = MaterialTheme.typography.bodyMedium,
                color = WarpColors.InkMuted,
                textAlign = TextAlign.Center,
                maxLines = 5,
            )
        }
        Spacer(Modifier.height(14.dp))
        Status(info, stage)
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(
                text = stringResource(R.string.update_later),
                emphasised = false,
                onClick = onLater,
                modifier = Modifier.weight(1f),
            )
            val action = when (stage) {
                is UpdateUi.Downloading -> stringResource(R.string.update_downloading, (stage.progress * 100).toInt())
                is UpdateUi.Ready -> stringResource(R.string.update_ready)
                is UpdateUi.Failed -> stringResource(R.string.update_retry)
                else -> stringResource(R.string.update_download)
            }
            PillButton(
                text = action,
                emphasised = stage !is UpdateUi.Downloading,
                onClick = onDownload,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** The size before the download, a bar during it, then what to do next. */
@Composable
private fun Status(info: AppUpdate, stage: UpdateUi) {
    when (stage) {
        is UpdateUi.Downloading -> {
            val progress by animateFloatAsState(stage.progress, tween(240), label = "updateProgress")
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(WarpColors.Ink.copy(alpha = 0.08f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0.02f, 1f))
                        .height(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Brush.horizontalGradient(listOf(WarpColors.Mint.copy(alpha = 0.7f), WarpColors.Mint))),
                )
            }
            if (info.apkBytes > 0) {
                Spacer(Modifier.height(8.dp))
                Caption("${Formats.bytes((info.apkBytes * stage.progress).toLong())} / ${Formats.bytes(info.apkBytes)}")
            }
        }
        is UpdateUi.Ready -> Caption(stringResource(R.string.update_ready_subtitle), WarpColors.Mint)
        is UpdateUi.Failed -> Caption(stringResource(R.string.update_failed, stage.message), WarpColors.Rose)
        else -> if (info.apkBytes > 0) Caption(stringResource(R.string.update_size, Formats.bytes(info.apkBytes)))
    }
}

@Composable
private fun Caption(text: String, color: Color = WarpColors.InkMuted.copy(alpha = 0.75f)) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = color, textAlign = TextAlign.Center)
}
