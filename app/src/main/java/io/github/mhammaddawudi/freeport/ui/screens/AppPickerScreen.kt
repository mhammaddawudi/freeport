package io.github.mhammaddawudi.freeport.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.mhammaddawudi.freeport.R
import io.github.mhammaddawudi.freeport.ui.components.Glyph
import io.github.mhammaddawudi.freeport.ui.components.GlyphKind
import io.github.mhammaddawudi.freeport.ui.theme.AetherColors
import io.github.mhammaddawudi.freeport.ui.theme.MonoStyle

private data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?,
    val system: Boolean,
)

/**
 * Picks which apps ride the tunnel.
 *
 * Only apps that asked for INTERNET are listed — the rest have nothing to
 * route — and user-installed ones sort above system packages, since that is
 * what someone is nearly always looking for.
 */
@Composable
fun AppPickerScreen(
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onClose: () -> Unit,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }

    val apps by produceState<List<AppEntry>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { loadApps(context) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(AetherColors.Void)
            .padding(contentPadding)
            .padding(horizontal = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 14.dp)) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .border(1.dp, AetherColors.Hairline, CircleShape)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Glyph(GlyphKind.Close, AetherColors.InkMuted, Modifier.size(17.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.apps_title), style = MaterialTheme.typography.titleLarge, color = AetherColors.Ink)
                Text(
                    stringResource(R.string.apps_selected, selected.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = AetherColors.InkMuted,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        val searchShape = RoundedCornerShape(14.dp)
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = AetherColors.Ink),
            cursorBrush = SolidColor(AetherColors.Cyan),
            decorationBox = { inner ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(searchShape)
                        .background(AetherColors.Ink.copy(alpha = 0.04f))
                        .border(1.dp, AetherColors.Hairline, searchShape)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph(GlyphKind.Radar, AetherColors.InkFaint, Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text(
                                stringResource(R.string.apps_search),
                                style = MaterialTheme.typography.bodyLarge,
                                color = AetherColors.InkFaint,
                            )
                        }
                        inner()
                    }
                }
            },
        )
        Spacer(Modifier.height(12.dp))

        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AetherColors.Cyan, strokeWidth = 2.dp)
            }
        } else {
            val filtered = remember(list, query) {
                if (query.isBlank()) list
                else list.filter {
                    it.label.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
                }
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(filtered, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        checked = app.packageName in selected,
                        onToggle = { onToggle(app.packageName) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: AppEntry, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Checkbox, onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(AetherColors.Ink.copy(alpha = 0.05f)),
            contentAlignment = Alignment.Center,
        ) {
            val icon = app.icon
            if (icon != null) {
                androidx.compose.foundation.Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Glyph(GlyphKind.Stack, AetherColors.InkFaint, Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                app.label,
                style = MaterialTheme.typography.titleSmall,
                color = AetherColors.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                app.packageName,
                style = MonoStyle,
                color = AetherColors.InkFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (checked) AetherColors.Control.copy(alpha = 0.18f) else AetherColors.Ink.copy(alpha = 0.04f))
                .border(
                    1.dp,
                    if (checked) AetherColors.Control else AetherColors.HairlineStrong,
                    RoundedCornerShape(8.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Glyph(GlyphKind.Check, AetherColors.Control, Modifier.size(14.dp), strokeWidth = 2.2.dp)
        }
    }
}

private fun loadApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val self = context.packageName
    @Suppress("DEPRECATION")
    val packages = runCatching { pm.getInstalledPackages(PackageManager.GET_PERMISSIONS) }.getOrDefault(emptyList())

    return packages.asSequence()
        .filter { it.packageName != self }
        .filter { it.requestedPermissions?.contains(Manifest.permission.INTERNET) == true }
        .mapNotNull { info ->
            val application = info.applicationInfo ?: return@mapNotNull null
            AppEntry(
                packageName = info.packageName,
                label = runCatching { pm.getApplicationLabel(application).toString() }.getOrDefault(info.packageName),
                icon = runCatching { pm.getApplicationIcon(application).toBitmap(96, 96).asImageBitmap() }.getOrNull(),
                system = (application.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            )
        }
        // Installed apps first, then system packages, each alphabetically.
        .sortedWith(compareBy({ it.system }, { it.label.lowercase() }))
        .toList()
}
