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
import io.github.mhammaddawudi.freeport.core.SpeedReport
import io.github.mhammaddawudi.freeport.core.SpeedTester
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

/** Where the speed test is, and what it last measured. */
sealed interface SpeedTestUi {
    data object Idle : SpeedTestUi
    data class Running(val report: SpeedReport) : SpeedTestUi
    data class Done(val report: SpeedReport) : SpeedTestUi
    data class Failed(val message: String, val last: SpeedReport?) : SpeedTestUi
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

    private val _speedTest = MutableStateFlow<SpeedTestUi>(SpeedTestUi.Idle)
    val speedTest: StateFlow<SpeedTestUi> = _speedTest.asStateFlow()
    private var speedJob: Job? = null
    private val speedTester = SpeedTester()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        viewModelScope.launch { _coreVersion.value = CoreProvider.core.version() }
        viewModelScope.launch { meterTraffic() }
        viewModelScope.launch { pingOnConnect() }
    }

    /**
     * Ping, download and upload. Through the tunnel when a session is up,
     * otherwise over the bare line — useful for telling a slow tunnel from a
     * slow network.
     */
    fun runSpeedTest() {
        if (speedJob?.isActive == true) return
        speedJob = viewModelScope.launch {
            var last: SpeedReport? = null
            val result = runCatching {
                speedTester.run(tunnelProxyPort()) { report ->
                    last = report
                    _speedTest.value = SpeedTestUi.Running(report)
                }
            }
            ensureActive()
            _speedTest.value = result.fold(
                onSuccess = { SpeedTestUi.Done(it) },
                onFailure = { failure ->
                    CoreLog.warn("[-] speed test: ${failure.message}")
                    SpeedTestUi.Failed(failure.message ?: failure.javaClass.simpleName, last)
                },
            )
        }
    }

    fun cancelSpeedTest() {
        speedJob?.cancel()
        speedJob = null
        val running = _speedTest.value
        _speedTest.value = if (running is SpeedTestUi.Running) {
            SpeedTestUi.Done(running.report.copy(phase = SpeedReport.Phase.Done))
        } else {
            running
        }
    }

    /** The live session's proxy, or null to test the bare line. */
    private fun tunnelProxyPort(): Int? {
        val current = state.value as? ConnectionState.Connected ?: return null
        if (!isNativeCore) return null
        return current.socksPort.takeIf { it > 0 } ?: settings.value.socksPort
    }

    /**
     * Earlier results describe a path that no longer exists once the session
     * changes, so they are cleared; a fresh connection gets a ping straight
     * away so the number is there before anyone asks for it.
     */
    private suspend fun pingOnConnect() {
        state.map { (it as? ConnectionState.Connected)?.sinceMillis }
            .distinctUntilChanged()
            .collectLatest { since ->
                speedJob?.cancel()
                _speedTest.value = SpeedTestUi.Idle
                if (since == null) return@collectLatest
                delay(PING_SETTLE_MS)
                if (speedJob?.isActive == true) return@collectLatest
                runCatching { speedTester.ping(tunnelProxyPort()) }
                    .onSuccess { report ->
                        if (_speedTest.value is SpeedTestUi.Idle) _speedTest.value = SpeedTestUi.Done(report)
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
                    _traffic.value = TrafficUi(
                        downRate = (current.rxBytes - previous.rxBytes).coerceAtLeast(0),
                        upRate = (current.txBytes - previous.txBytes).coerceAtLeast(0),
                        downTotal = (current.rxBytes - base.rxBytes).coerceAtLeast(0),
                        upTotal = (current.txBytes - base.txBytes).coerceAtLeast(0),
                    )
                    previous = current
                }
            }
    }
}
