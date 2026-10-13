package io.github.mhammaddawudi.freeport.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mhammaddawudi.freeport.R
import io.github.mhammaddawudi.freeport.core.ConnectionState
import io.github.mhammaddawudi.freeport.core.CorePayloads
import io.github.mhammaddawudi.freeport.core.Identity
import io.github.mhammaddawudi.freeport.core.isEngaged
import io.github.mhammaddawudi.freeport.data.AetherSettings
import io.github.mhammaddawudi.freeport.data.AppLanguage
import io.github.mhammaddawudi.freeport.data.Protocol
import io.github.mhammaddawudi.freeport.data.TunnelMode
import io.github.mhammaddawudi.freeport.ui.components.FieldRow
import io.github.mhammaddawudi.freeport.ui.components.GlassCard
import io.github.mhammaddawudi.freeport.ui.components.Glyph
import io.github.mhammaddawudi.freeport.ui.components.GlyphKind
import io.github.mhammaddawudi.freeport.ui.components.InfoRow
import io.github.mhammaddawudi.freeport.ui.components.PortRow
import io.github.mhammaddawudi.freeport.ui.components.RowDivider
import io.github.mhammaddawudi.freeport.ui.components.SectionLabel
import io.github.mhammaddawudi.freeport.ui.components.SegmentedPicker
import io.github.mhammaddawudi.freeport.ui.components.SliderRow
import io.github.mhammaddawudi.freeport.ui.components.SwitchRow
import io.github.mhammaddawudi.freeport.ui.components.TuningSheet
import io.github.mhammaddawudi.freeport.ui.components.TuningSheetHost
import io.github.mhammaddawudi.freeport.ui.theme.AetherColors

/**
 * Four sections, in the order someone actually needs them: what gets routed,
 * how the tunnel behaves, then everything else folded away, then what the app
 * currently is.
 *
 * The three tunnel choices open the same sheets the connect screen uses, so
 * no option is presented twice in two different shapes.
 */
@Composable
fun SettingsScreen(
    state: ConnectionState,
    settings: AetherSettings,
    identity: Identity?,
    coreVersion: String?,
    isNativeCore: Boolean,
    bridgeName: String,
    onUpdateSettings: ((AetherSettings) -> AetherSettings) -> Unit,
    onSelectLanguage: (AppLanguage) -> Unit,
    onReconnect: () -> Unit,
    onOpenAppPicker: () -> Unit,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val protocol = settings.protocol
    val cardPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
    var sheet by remember { mutableStateOf<TuningSheet?>(null) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val peerHint = stringResource(R.string.advanced_peer_error)
    val batteryUnrestricted = remember {
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 20.dp),
    ) {
        ScreenHeader(stringResource(R.string.settings_title))

        if (state.isEngaged && state !is ConnectionState.Disconnecting) {
            Spacer(Modifier.height(16.dp))
            GlassCard(Modifier.fillMaxWidth(), accent = AetherColors.Amber, contentPadding = PaddingValues(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Glyph(GlyphKind.Bolt, AetherColors.Amber, Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.settings_changes_pending),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AetherColors.Ink,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(R.string.action_reconnect),
                        style = MaterialTheme.typography.labelLarge,
                        color = AetherColors.Amber,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onReconnect)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }

        // ---- what gets routed ------------------------------------------------
        SectionLabel(stringResource(R.string.section_routing))
        GlassCard(Modifier.fillMaxWidth(), contentPadding = cardPadding) {
            Spacer(Modifier.height(6.dp))
            SegmentedPicker(
                options = TunnelMode.entries,
                selected = settings.tunnelMode,
                label = { stringResource(it.labelRes) },
                onSelect = { mode -> onUpdateSettings { it.copy(tunnelMode = mode) } },
                accent = AetherColors.Control,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(settings.tunnelMode.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = AetherColors.InkMuted,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
            AnimatedVisibility(visible = settings.tunnelMode == TunnelMode.SelectedApps) {
                Column {
                    RowDivider(Modifier.padding(vertical = 8.dp))
                    InfoRow(
                        title = stringResource(R.string.routing_apps_title),
                        value = settings.tunneledApps.size.let { count ->
                            if (count == 0) {
                                stringResource(R.string.routing_apps_none)
                            } else {
                                stringResource(R.string.routing_apps_count, count)
                            }
                        },
                        glyph = GlyphKind.Stack,
                        valueColor = if (settings.tunneledApps.isEmpty()) AetherColors.Amber else AetherColors.InkMuted,
                        onClick = onOpenAppPicker,
                    )
                }
            }
        }

        // ---- how the tunnel behaves -----------------------------------------
        SectionLabel(stringResource(R.string.section_tunnel), trailing = protocol.displayName)
        GlassCard(Modifier.fillMaxWidth(), contentPadding = cardPadding) {
            InfoRow(
                title = stringResource(R.string.tunnel_scan),
                value = stringResource(settings.scanMode.labelRes),
                glyph = GlyphKind.Radar,
                onClick = { sheet = TuningSheet.Scan },
            )
            RowDivider()
            InfoRow(
                title = stringResource(R.string.tunnel_noize),
                value = stringResource(settings.effectiveNoize.labelRes),
                glyph = GlyphKind.Mask,
                onClick = { sheet = TuningSheet.Noize },
            )
            RowDivider()
            InfoRow(
                title = stringResource(R.string.tunnel_ip),
                value = stringResource(settings.ipVersion.labelRes),
                glyph = GlyphKind.Globe,
                onClick = { sheet = TuningSheet.Ip },
            )
        }

        // ---- language --------------------------------------------------------
        SectionLabel(stringResource(R.string.section_appearance))
        GlassCard(Modifier.fillMaxWidth(), contentPadding = cardPadding) {
            Spacer(Modifier.height(6.dp))
            SegmentedPicker(
                options = AppLanguage.entries,
                selected = settings.language,
                label = { stringResource(it.labelRes) },
                onSelect = onSelectLanguage,
                accent = AetherColors.Control,
            )
            Spacer(Modifier.height(8.dp))
        }

        // ---- everything else -------------------------------------------------
        SectionLabel(stringResource(R.string.section_advanced))
        GlassCard(Modifier.fillMaxWidth(), contentPadding = cardPadding) {
            val turn by animateFloatAsState(if (advanced) 90f else 0f, tween(220), label = "advancedChevron")
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { advanced = !advanced }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.advanced_title), style = MaterialTheme.typography.titleSmall, color = AetherColors.Ink)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.advanced_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = AetherColors.InkMuted,
                    )
                }
                Glyph(GlyphKind.Chevron, AetherColors.InkFaint, Modifier.size(16.dp).rotate(turn))
            }

            AnimatedVisibility(visible = advanced) {
                Column {
                    RowDivider(Modifier.padding(bottom = 4.dp))
                    PortRow(
                        title = stringResource(R.string.advanced_socks_port),
                        port = settings.socksPort,
                        onPortChange = { port -> onUpdateSettings { it.copy(socksPort = port) } },
                        conflictsWith = settings.httpProxyPort.takeIf { settings.httpProxyEnabled },
                    )
                    SwitchRow(
                        title = stringResource(R.string.advanced_http_proxy),
                        checked = settings.httpProxyEnabled,
                        onCheckedChange = { on -> onUpdateSettings { it.copy(httpProxyEnabled = on) } },
                        subtitle = stringResource(R.string.advanced_http_proxy_desc),
                    )
                    AnimatedVisibility(visible = settings.httpProxyEnabled) {
                        PortRow(
                            title = stringResource(R.string.advanced_http_port),
                            port = settings.httpProxyPort,
                            onPortChange = { port -> onUpdateSettings { it.copy(httpProxyPort = port) } },
                            conflictsWith = settings.socksPort,
                        )
                    }
                    RowDivider(Modifier.padding(vertical = 6.dp))
                    SwitchRow(
                        title = stringResource(R.string.advanced_fragment),
                        checked = settings.fragmentEnabled,
                        onCheckedChange = { on -> onUpdateSettings { it.copy(fragmentEnabled = on) } },
                        subtitle = if (protocol.isTcp) {
                            stringResource(R.string.advanced_fragment_desc)
                        } else {
                            stringResource(R.string.advanced_fragment_tcp_only)
                        },
                        enabled = protocol.isTcp,
                        accent = AetherColors.Control,
                    )
                    AnimatedVisibility(visible = settings.fragmentEnabled && protocol.isTcp) {
                        Column {
                            SliderRow(
                                title = stringResource(R.string.advanced_fragment_size),
                                value = settings.fragmentSize,
                                range = 10..200,
                                unit = "bytes",
                                onChange = { size -> onUpdateSettings { it.copy(fragmentSize = size) } },
                                step = 5,
                                accent = AetherColors.Control,
                            )
                            SliderRow(
                                title = stringResource(R.string.advanced_fragment_delay),
                                value = settings.fragmentDelayMs,
                                range = 0..200,
                                unit = "ms",
                                onChange = { delay -> onUpdateSettings { it.copy(fragmentDelayMs = delay) } },
                                step = 5,
                                accent = AetherColors.Control,
                            )
                        }
                    }
                    RowDivider(Modifier.padding(vertical = 6.dp))
                    SwitchRow(
                        title = stringResource(R.string.advanced_ech),
                        checked = settings.fetchEch,
                        onCheckedChange = { on -> onUpdateSettings { it.copy(fetchEch = on) } },
                        subtitle = stringResource(R.string.advanced_ech_desc),
                        enabled = protocol == Protocol.MasqueH3,
                    )
                    val canExclude = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    SwitchRow(
                        title = stringResource(R.string.advanced_bypass_lan),
                        checked = settings.bypassLan && canExclude,
                        onCheckedChange = { on -> onUpdateSettings { it.copy(bypassLan = on) } },
                        subtitle = if (canExclude) stringResource(R.string.advanced_bypass_lan_desc) else stringResource(R.string.advanced_bypass_lan_needs13),
                        enabled = canExclude,
                    )
                    SliderRow(
                        title = stringResource(R.string.advanced_keepalive),
                        value = settings.keepaliveSeconds,
                        range = 0..60,
                        unit = "s",
                        onChange = { seconds -> onUpdateSettings { it.copy(keepaliveSeconds = seconds) } },
                        enabled = protocol == Protocol.WireGuard || protocol == Protocol.Gool,
                        accent = AetherColors.Control,
                    )
                    val peerError = settings.pinnedPeer
                        .takeIf { it.isNotBlank() && CorePayloads.parsePeer(it) == null }
                        ?.let { peerHint }
                    FieldRow(
                        title = stringResource(R.string.advanced_peer),
                        value = settings.pinnedPeer,
                        onValueChange = { value -> onUpdateSettings { it.copy(pinnedPeer = value.trim()) } },
                        placeholder = "162.159.198.1:443",
                        helper = stringResource(R.string.advanced_peer_helper),
                        error = peerError,
                    )
                    RowDivider(Modifier.padding(vertical = 6.dp))
                    InfoRow(
                        title = stringResource(R.string.advanced_battery),
                        value = stringResource(R.string.advanced_battery_value),
                        glyph = GlyphKind.Bolt,
                        valueColor = if (batteryUnrestricted) AetherColors.Mint else AetherColors.Amber,
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                    )
                    InfoRow(
                        title = stringResource(R.string.advanced_system_vpn),
                        value = stringResource(R.string.advanced_system_vpn_value),
                        glyph = GlyphKind.Shield,
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(android.provider.Settings.ACTION_VPN_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                    )
                }
            }
        }

        // ---- what the app currently is ---------------------------------------
        SectionLabel(stringResource(R.string.section_about))
        GlassCard(Modifier.fillMaxWidth(), contentPadding = cardPadding) {
            InfoRow(
                title = stringResource(R.string.about_core),
                value = if (isNativeCore) "libaether ${coreVersion.orEmpty()}".trim() else stringResource(R.string.about_core_mock),
                glyph = GlyphKind.Bolt,
                valueColor = if (isNativeCore) AetherColors.Mint else AetherColors.Amber,
            )
            RowDivider()
            InfoRow(
                title = stringResource(R.string.about_capture),
                value = if (bridgeName == "proxy-only") stringResource(R.string.about_capture_proxy) else bridgeName,
                glyph = GlyphKind.Plug,
                valueColor = if (bridgeName == "proxy-only") AetherColors.Amber else AetherColors.Mint,
            )
            RowDivider()
            InfoRow(stringResource(R.string.about_device), identity?.deviceId ?: stringResource(R.string.about_device_none), glyph = GlyphKind.Key, mono = identity != null)
            RowDivider()
            InfoRow(
                title = stringResource(R.string.about_developer),
                value = "mhammaddawudi",
                glyph = GlyphKind.Key,
                valueColor = AetherColors.Cyan,
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/mhammaddawudi"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            )
            RowDivider()
            InfoRow(stringResource(R.string.about_license), "AGPL-3.0", glyph = GlyphKind.Stack)
        }

        Spacer(Modifier.height(16.dp))
    }

    TuningSheetHost(
        sheet = sheet,
        settings = settings,
        onUpdateSettings = onUpdateSettings,
        onDismiss = { sheet = null },
    )
}

@Composable
internal fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            // One line only. Not softWrap = false: in a cursive script that
            // let the last letter render outside the measured width, where it
            // was clipped.
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                color = AetherColors.Ink,
                maxLines = 1,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = AetherColors.InkMuted)
            }
        }
        trailing?.invoke(this)
    }
}
