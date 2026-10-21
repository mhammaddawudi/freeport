package io.github.mhammaddawudi.freeport.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mhammaddawudi.freeport.core.ConnectionState
import io.github.mhammaddawudi.freeport.core.isEngaged
import io.github.mhammaddawudi.freeport.ui.components.AetherTab
import io.github.mhammaddawudi.freeport.ui.components.AuroraBackground
import io.github.mhammaddawudi.freeport.ui.components.FloatingTabBar
import io.github.mhammaddawudi.freeport.ui.components.UpdateBanner
import io.github.mhammaddawudi.freeport.data.AppLanguage
import io.github.mhammaddawudi.freeport.ui.screens.AppPickerScreen
import io.github.mhammaddawudi.freeport.ui.screens.HomeScreen
import io.github.mhammaddawudi.freeport.ui.screens.LogsScreen
import io.github.mhammaddawudi.freeport.ui.screens.SettingsScreen
import io.github.mhammaddawudi.freeport.ui.theme.AetherColors
import io.github.mhammaddawudi.freeport.ui.theme.Tones

@Composable
fun AetherRoot(viewModel: AetherViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val coreVersion by viewModel.coreVersion.collectAsStateWithLifecycle()
    val iphoneExport by viewModel.iphoneExport.collectAsStateWithLifecycle()
    val traffic by viewModel.traffic.collectAsStateWithLifecycle()
    val pingMs by viewModel.pingMs.collectAsStateWithLifecycle()
    val update by viewModel.update.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(AetherTab.Home) }
    var appPickerOpen by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val tone = Tones.of(state)

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    // Consent is asked in sequence — notifications first, then VPN — because
    // two system dialogs launched together cancel one another.
    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.connect() else viewModel.onVpnPermissionDenied()
    }
    val startVpn: () -> Unit = {
        val consent = if (viewModel.needsVpnPermission) VpnService.prepare(context) else null
        if (consent != null) vpnLauncher.launch(consent) else viewModel.connect()
    }
    var askedNotifications by rememberSaveable { mutableStateOf(false) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        startVpn()
    }
    val requestConnect: () -> Unit = {
        val needsNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsNotifications && !askedNotifications) {
            askedNotifications = true
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startVpn()
        }
    }
    val onToggle: () -> Unit = {
        val current = viewModel.state.value
        when {
            current is ConnectionState.Disconnecting -> Unit
            current.isEngaged -> viewModel.disconnect()
            else -> requestConnect()
        }
    }

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Room for the update card too while it floats over the content.
    val floating = if (update is UpdateUi.Hidden) 104.dp else 184.dp
    val contentPadding = PaddingValues(top = top + 8.dp, bottom = bottom + floating)

    Box(Modifier.fillMaxSize()) {
        AuroraBackground(
            glow = when (state) {
                is ConnectionState.Connected -> AetherColors.Mint
                is ConnectionState.Failed -> AetherColors.Rose
                ConnectionState.Idle, ConnectionState.Disconnecting -> AetherColors.Ink
                else -> AetherColors.Amber
            },
        )

        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                (fadeIn(tween(280)) + scaleIn(tween(280), initialScale = 0.985f)) togetherWith fadeOut(tween(160))
            },
            label = "tab",
        ) { current ->
            when (current) {
                AetherTab.Home -> HomeScreen(
                    state = state,
                    settings = settings,
                    isNativeCore = viewModel.isNativeCore,
                    traffic = traffic,
                    pingMs = pingMs,
                    onToggle = onToggle,
                    onSelectProtocol = viewModel::selectProtocol,
                    onRegionUnlock = viewModel::setRegionUnlock,
                    onSelectExitCountry = viewModel::selectExitCountry,
                    contentPadding = contentPadding,
                )
                AetherTab.Settings -> SettingsScreen(
                    state = state,
                    settings = settings,
                    identity = identity,
                    coreVersion = coreVersion,
                    isNativeCore = viewModel.isNativeCore,
                    bridgeName = viewModel.bridgeName,
                    onUpdateSettings = viewModel::updateSettings,
                    onSelectLanguage = { language ->
                        viewModel.selectLanguage(language)
                        // Resources are bound at creation, so the screen has
                        // to be built again in the new language.
                        (context as? android.app.Activity)?.recreate()
                    },
                    onReconnect = viewModel::reconnect,
                    onOpenAppPicker = { appPickerOpen = true },
                    iphoneExport = iphoneExport,
                    onStartIphoneExport = viewModel::startIphoneExport,
                    onResetIphoneExport = viewModel::resetIphoneExport,
                    contentPadding = contentPadding,
                )
                AetherTab.Logs -> LogsScreen(
                    lines = logs,
                    onClear = viewModel::clearLogs,
                    contentPadding = contentPadding,
                )
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = if (update is UpdateUi.Hidden) 92.dp else 176.dp),
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = AetherColors.SurfaceRaised,
                contentColor = AetherColors.Ink,
            )
        }

        // The picker is a full screen of its own: it covers the tab bar, and
        // the back gesture closes it rather than leaving the app.
        if (appPickerOpen) {
            BackHandler { appPickerOpen = false }
            AppPickerScreen(
                selected = settings.tunneledApps,
                onToggle = viewModel::toggleApp,
                onClose = { appPickerOpen = false },
                contentPadding = contentPadding,
            )
        }

        if (!appPickerOpen) {
            UpdateBanner(
                update = update,
                onInstall = viewModel::installUpdate,
                onLater = viewModel::postponeUpdate,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 14.dp, end = 14.dp, bottom = 92.dp),
            )
            FloatingTabBar(
                selected = tab,
                onSelect = { tab = it },
                accent = AetherColors.Control,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
            )
        }
    }
}
