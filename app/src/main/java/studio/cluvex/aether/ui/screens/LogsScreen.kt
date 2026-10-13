package studio.cluvex.aether.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.core.CoreLog
import studio.cluvex.aether.ui.components.GlassCard
import studio.cluvex.aether.ui.components.Glyph
import studio.cluvex.aether.ui.components.GlyphKind
import studio.cluvex.aether.ui.theme.AetherColors
import studio.cluvex.aether.ui.theme.MonoStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogsScreen(
    lines: List<CoreLog.Line>,
    onClear: () -> Unit,
    contentPadding: PaddingValues,
) {
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    var follow by rememberSaveable { mutableStateOf(true) }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    LaunchedEffect(lines.size, follow) {
        if (follow && lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 20.dp),
    ) {
        ScreenHeader(stringResource(R.string.logs_title), stringResource(R.string.logs_subtitle, lines.size))
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolPill(GlyphKind.Down, stringResource(R.string.logs_follow), active = follow, onClick = { follow = !follow })
            ToolPill(GlyphKind.Copy, stringResource(R.string.logs_copy), enabled = lines.isNotEmpty(), onClick = {
                clipboard.setText(
                    AnnotatedString(lines.joinToString("\n") { "${timeFormat.format(Date(it.timeMillis))} ${it.text}" }),
                )
            })
            ToolPill(GlyphKind.Trash, stringResource(R.string.logs_clear), enabled = lines.isNotEmpty(), onClick = onClear)
        }
        Spacer(Modifier.height(14.dp))

        GlassCard(
            modifier = Modifier.fillMaxWidth().weight(1f),
            shape = RoundedCornerShape(22.dp),
            contentPadding = PaddingValues(0.dp),
        ) {
            if (lines.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Glyph(GlyphKind.Terminal, AetherColors.InkFaint, Modifier.size(34.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.logs_empty), style = MaterialTheme.typography.titleSmall, color = AetherColors.InkMuted)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.logs_empty_hint), style = MaterialTheme.typography.bodySmall, color = AetherColors.InkFaint)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(vertical = 12.dp),
                ) {
                    items(lines, key = { it.id }) { line ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp)) {
                            Text(timeFormat.format(Date(line.timeMillis)), style = MonoStyle, color = AetherColors.InkFaint)
                            Spacer(Modifier.width(10.dp))
                            Text(line.text, style = MonoStyle, color = colorOf(line.level))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun colorOf(level: CoreLog.Level): Color = when (level) {
    CoreLog.Level.Info -> AetherColors.InkMuted
    CoreLog.Level.Good -> AetherColors.Mint
    CoreLog.Level.Warn -> AetherColors.Amber
    CoreLog.Level.Error -> AetherColors.Rose
}

@Composable
private fun ToolPill(
    glyph: GlyphKind,
    label: String,
    onClick: () -> Unit,
    active: Boolean = false,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(50)
    val tint = if (active) AetherColors.Control else AetherColors.InkMuted
    Row(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(if (active) AetherColors.Control.copy(alpha = 0.14f) else AetherColors.Ink.copy(alpha = 0.05f))
            .border(1.dp, if (active) AetherColors.Control.copy(alpha = 0.4f) else AetherColors.Hairline, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(glyph, tint, Modifier.size(15.dp))
        Spacer(Modifier.width(7.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (active) AetherColors.Control else AetherColors.Ink)
    }
}
