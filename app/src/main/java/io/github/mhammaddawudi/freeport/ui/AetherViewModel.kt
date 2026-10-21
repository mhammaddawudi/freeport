package io.github.mhammaddawudi.freeport.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import io.github.mhammaddawudi.freeport.AetherApp
import io.github.mhammaddawudi.freeport.R
import io.github.mhammaddawudi.freeport.core.ConnectionState
import io.github.mhammaddawudi.freeport.core.CoreLog
import io.github.mhammaddawudi.freeport.core.CoreProvider
import io.github.mhammaddawudi.freeport.core.CoreResult
import io.github.mhammaddawudi.freeport.core.Identity
import io.github.mhammaddawudi.freeport.core.IphoneConfig
import io.github.mhammaddawudi.freeport.core.IphoneExporter
import io.github.mhammaddawudi.freeport.core.LatencyProbe
import io.github.mhammaddawudi.freeport.core.AppUpdate
import io.github.mhammaddawudi.freeport.core.UpdateChecker
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import io.github.mhammaddawudi.freeport.core.isEngaged
import io.github.mhammaddawudi.freeport.data.AetherSettings
import io.github.mhammaddawudi.freeport.data.AppLanguage
import io.github.mhammaddawudi.freeport.data.NoizeProfile
import io.github.mhammaddawudi.freeport.data.Protocol
import io.github.mhammaddawudi.freeport.vpn.AetherVpnService

/** Where the iPhone export is. */
sealed interface IphoneExportUi {
    data object Idle : IphoneExportUi
    data class Working(val phase: IphoneExporter.Phase) : IphoneExportUi
    data class Ready(val config: IphoneConfig) : IphoneExportUi
    data class Failed(val message: String) : IphoneExportUi
}

/** The floating update card. */
sealed interface UpdateUi {
    data object Hidden : UpdateUi
    data class Available(val update: AppUpdate) : UpdateUi
    data class Downloading(val update: AppUpdate, val progress: Float) : UpdateUi
    data class Ready(val update: AppUpdate, val apk: File) : UpdateUi
    data class Failed(val update: AppUpdate, val message: String) : UpdateUi
}

/** Per-second throughput and session totals. */
data class TrafficUi(
    val downRate: Long = 0,
    val upRate: Long = 0,
    val downTotal: Long = 0,
    val upTotal: Long = 0,
)

class AetherViewModel(application: Application) : AndroidViewModel(application) {

    private val aether = application as AetherApp

    val state: StateFlow<ConnectionState> = aether.engine.state
    val settings: StateFlow<AetherSettings> = aether.settings.settings
    val identity: StateFlow<Identity?> = aether.engine.identity
    val logs: StateFlow<List<CoreLog.Line>> = CoreLog.lines

    val isNativeCore: Boolean = CoreProvider.isNative
    val bridgeName: String = aether.tunBridge.name

    /**
     * Consent is only worth asking for when the session will actually raise a
     * TUN interface: a real tun2socks *and* a real core. Asking while the
     * core is the simulator produces the worst outcome — Android shows the
     * VPN key, and nothing is carried.
     */
    val needsVpnPermission: Boolean = aether.tunBridge.isAvailable && CoreProvider.isNative

    private val _coreVersion = MutableStateFlow<String?>(null)
    val coreVersion: StateFlow<String?> = _coreVersion.asStateFlow()

    private val _traffic = MutableStateFlow(TrafficUi())
    val traffic: StateFlow<TrafficUi> = _traffic.asStateFlow()

    private val _iphoneExport = MutableStateFlow<IphoneExportUi>(IphoneExportUi.Idle)
    val iphoneExport: StateFlow<IphoneExportUi> = _iphoneExport.asStateFlow()
    private var exportJob: Job? = null

    /** The tunnel's round trip in ms, refreshed while connected; null otherwise. */
    private val _pingMs = MutableStateFlow<Long?>(null)
    val pingMs: StateFlow<Long?> = _pingMs.asStateFlow()

    private val updates = UpdateChecker(application)
    private val updatePrefs = application.getSharedPreferences("aether.updates", android.content.Context.MODE_PRIVATE)
    private val _update = MutableStateFlow<UpdateUi>(UpdateUi.Hidden)
    val update: StateFlow<UpdateUi> = _update.asStateFlow()
    private var updateJob: Job? = null

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        viewModelScope.launch { _coreVersion.value = CoreProvider.core.version() }
        viewModelScope.launch { meterTraffic() }
        viewModelScope.launch { measurePing() }
        viewModelScope.launch { watchForUpdates() }
    }

    /**
     * Checks GitHub shortly after launch and again whenever a session comes
     * up — on a blocked line the first check often only gets through once
     * the tunnel is there to carry it.
     */
    private suspend fun watchForUpdates() {
        delay(UPDATE_FIRST_CHECK_MS)
        checkForUpdate()
        state.map { it is ConnectionState.Connected }
            .distinctUntilChanged()
            .collect { connected -> if (connected) checkForUpdate() }
    }

    private suspend fun checkForUpdate() {
        if (_update.value !is UpdateUi.Hidden) return
        val found = runCatching { updates.check(tunnelProxyPort()) }
            .onFailure { CoreLog.info("[*] update check did not answer: ${it.message}") }
            .getOrNull() ?: return
        val snoozedVersion = updatePrefs.getString(KEY_SNOOZED_VERSION, null)
        val snoozedUntil = updatePrefs.getLong(KEY_SNOOZED_UNTIL, 0)
        if (snoozedVersion == found.version && System.currentTimeMillis() < snoozedUntil) return
        CoreLog.info("[*] version ${found.version} is available")
        _update.value = UpdateUi.Available(found)
    }

    /** Downloads the update, or installs it once downloaded. */
    fun installUpdate() {
        when (val current = _update.value) {
            is UpdateUi.Ready -> launchInstaller(current.apk)
            is UpdateUi.Available -> downloadUpdate(current.update)
            is UpdateUi.Failed -> downloadUpdate(current.update)
            else -> Unit
        }
    }

    /** Hides the card for a day; a newer version than this one shows at once. */
    fun postponeUpdate() {
        val update = when (val current = _update.value) {
            is UpdateUi.Available -> current.update
            is UpdateUi.Downloading -> current.update
            is UpdateUi.Ready -> current.update
            is UpdateUi.Failed -> current.update
            UpdateUi.Hidden -> return
        }
        updateJob?.cancel()
        updatePrefs.edit()
            .putString(KEY_SNOOZED_VERSION, update.version)
            .putLong(KEY_SNOOZED_UNTIL, System.currentTimeMillis() + SNOOZE_MS)
            .apply()
        _update.value = UpdateUi.Hidden
    }

    private fun downloadUpdate(update: AppUpdate) {
        if (updateJob?.isActive == true) return
        updateJob = viewModelScope.launch {
            _update.value = UpdateUi.Downloading(update, 0f)
            val result = runCatching {
                updates.download(update, tunnelProxyPort()) { progress ->
                    _update.value = UpdateUi.Downloading(update, progress)
                }
            }
            ensureActive()
            result.fold(
                onSuccess = { apk ->
                    _update.value = UpdateUi.Ready(update, apk)
                    launchInstaller(apk)
                },
                onFailure = { failure ->
                    CoreLog.warn("[-] update download: ${failure.message}")
                    _update.value = UpdateUi.Failed(update, failure.message ?: "download failed")
                },
            )
        }
    }

    /**
     * Hands the apk to Android's installer. The first time, Android needs
     * this app allowed to install updates; that screen opens instead, and the
     * card stays on "Install" for when the person comes back.
     */
    private fun launchInstaller(apk: File) {
        val app = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !app.packageManager.canRequestPackageInstalls()) {
            runCatching {
                app.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            _messages.tryEmit(app.getString(R.string.update_allow_installs))
            return
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", apk)
        runCatching {
            app.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { _messages.tryEmit(it.message ?: "installer unavailable") }
    }

    /** The release page, for the full notes. */
    fun openUpdatePage() {
        val page = (_update.value as? UpdateUi.Available)?.update?.pageUrl ?: return
        runCatching {
            getApplication<Application>().startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(page)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** The live session's proxy, or null for the bare line. */
    private fun tunnelProxyPort(): Int? {
        val current = state.value as? ConnectionState.Connected ?: return null
        if (!isNativeCore) return null
        return current.socksPort.takeIf { it > 0 } ?: settings.value.socksPort
    }

    /**
     * Pings through the tunnel shortly after it comes up and then every
     * [PING_EVERY_MS] while it stays up, so the number is always current
     * without anyone having to ask for it.
     */
    private suspend fun measurePing() {
        state.map { (it as? ConnectionState.Connected)?.sinceMillis }
            .distinctUntilChanged()
            .collectLatest { since ->
                _pingMs.value = null
                if (since == null) return@collectLatest
                delay(PING_SETTLE_MS)
                while (true) {
                    LatencyProbe.ping(tunnelProxyPort())?.let { _pingMs.value = it }
                    delay(PING_EVERY_MS)
                }
            }
    }

    fun connect() = AetherVpnService.connect(getApplication<Application>())

    fun disconnect() = AetherVpnService.disconnect(getApplication<Application>())

    fun selectProtocol(protocol: Protocol) {
        val before = settings.value
        if (before.protocol == protocol) return
        aether.settings.update {
            it.copy(
                protocol = protocol,
                noize = if (it.noize in protocol.noizeProfiles) it.noize else NoizeProfile.fromWire(protocol.defaultNoize),
            )
        }
        if (state.value.isEngaged && state.value !is ConnectionState.Disconnecting) {
            aether.engine.reconnect()
        }
    }

    fun updateSettings(transform: (AetherSettings) -> AetherSettings) = aether.settings.update(transform)

    /** Turns the foreign exit on or off; a live session picks it up at once. */
    fun setRegionUnlock(enabled: Boolean) {
        if (settings.value.regionUnlock == enabled) return
        aether.settings.update { it.copy(regionUnlock = enabled) }
        reconnectIfEngaged()
    }

    fun selectExitCountry(code: String) {
        if (settings.value.exitCountry == code) return
        aether.settings.update { it.copy(exitCountry = code) }
        if (settings.value.regionUnlock) reconnectIfEngaged()
    }

    private fun reconnectIfEngaged() {
        if (state.value.isEngaged && state.value !is ConnectionState.Disconnecting) aether.engine.reconnect()
    }

    /** Stores the language; the screen is rebuilt by the caller. */
    fun selectLanguage(language: AppLanguage) {
        if (settings.value.language == language) return
        aether.settings.update { it.copy(language = language) }
    }

    /** Adds or removes one package from the per-app tunnel list. */
    fun toggleApp(packageName: String) = aether.settings.update {
        val next = if (packageName in it.tunneledApps) {
            it.tunneledApps - packageName
        } else {
            it.tunneledApps + packageName
        }
        it.copy(tunneledApps = next)
    }

    fun reconnect() = aether.engine.reconnect()

    /** Registers a fresh device and finds an edge for it on this network. */
    fun startIphoneExport() {
        if (exportJob?.isActive == true) return
        exportJob = viewModelScope.launch {
            val result = aether.iphoneExporter.export(settings.value) { phase ->
                _iphoneExport.value = IphoneExportUi.Working(phase)
            }
            // A reset while the core was finishing up must not be overwritten.
            ensureActive()
            _iphoneExport.value = when (result) {
                is CoreResult.Ok -> IphoneExportUi.Ready(result.value)
                is CoreResult.Err -> {
                    CoreLog.warn("[-] iPhone export: ${result.message}")
                    IphoneExportUi.Failed(result.message)
                }
            }
        }
    }

    /** Stops a running export and forgets the last result. */
    fun resetIphoneExport() {
        exportJob?.cancel()
        exportJob = null
        _iphoneExport.value = IphoneExportUi.Idle
    }


    fun clearLogs() = CoreLog.clear()

    fun onVpnPermissionDenied() {
        _messages.tryEmit(getApplication<Application>().getString(R.string.vpn_permission_denied))
    }

    private companion object {
        /** Lets tun2socks finish coming up before the first ping goes through it. */
        const val PING_SETTLE_MS = 1_200L
        const val PING_EVERY_MS = 15_000L
        const val UPDATE_FIRST_CHECK_MS = 3_000L
        const val SNOOZE_MS = 24 * 60 * 60 * 1000L
        const val KEY_SNOOZED_VERSION = "snoozed_version"
        const val KEY_SNOOZED_UNTIL = "snoozed_until"
    }

    private suspend fun meterTraffic() {
        state.map { it is ConnectionState.Connected }
            .distinctUntilChanged()
            .collectLatest { connected ->
                _traffic.value = TrafficUi()
                if (!connected) return@collectLatest

                val meter = aether.trafficMeter
                val base = meter.totals() ?: return@collectLatest
                var previous = base
                while (true) {
                    delay(1_000)
                    val current = meter.totals() ?: continue
                    val down = (current.rxBytes - previous.rxBytes).coerceAtLeast(0)
                    val up = (current.txBytes - previous.txBytes).coerceAtLeast(0)
                    _traffic.value = TrafficUi(
                        downRate = down,
                        upRate = up,
                        downTotal = (current.rxBytes - base.rxBytes).coerceAtLeast(0),
                        upTotal = (current.txBytes - base.txBytes).coerceAtLeast(0),
                    )
                    previous = current
                }
            }
    }
}
