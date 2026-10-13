package studio.cluvex.aether.ui

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
import studio.cluvex.aether.core.ConnectionState
import studio.cluvex.aether.core.isEngaged
import studio.cluvex.aether.ui.components.AetherTab
import studio.cluvex.aether.ui.components.AuroraBackground
import studio.cluvex.aether.ui.components.FloatingTabBar
import studio.cluvex.aether.data.AppLanguage
import studio.cluvex.aether.ui.screens.AppPickerScreen
import studio.cluvex.aether.ui.screens.HomeScreen
import studio.cluvex.aether.ui.screens.LogsScreen
import studio.cluvex.aether.ui.screens.SettingsScreen
import studio.cluvex.aether.ui.theme.AetherColors
import studio.cluvex.aether.ui.theme.Tones

@Composable
fun AetherRoot(viewModel: AetherViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val identity by viewModel.identity.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val coreVersion by viewModel.coreVersion.collectAsStateWithLifecycle()

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
    val contentPadding = PaddingValues(top = top + 8.dp, bottom = bottom + 104.dp)

    Box(Modifier.fillMaxSize()) {
        AuroraBackground()

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
                    onToggle = onToggle,
                    onSelectProtocol = viewModel::selectProtocol,
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
                .padding(start = 16.dp, end = 16.dp, bottom = 92.dp),
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
