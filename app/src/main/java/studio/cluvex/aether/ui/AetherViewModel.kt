package studio.cluvex.aether.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
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
import studio.cluvex.aether.AetherApp
import studio.cluvex.aether.R
import studio.cluvex.aether.core.ConnectionState
import studio.cluvex.aether.core.CoreLog
import studio.cluvex.aether.core.CoreProvider
import studio.cluvex.aether.core.Identity
import studio.cluvex.aether.core.isEngaged
import studio.cluvex.aether.data.AetherSettings
import studio.cluvex.aether.data.AppLanguage
import studio.cluvex.aether.data.NoizeProfile
import studio.cluvex.aether.data.Protocol
import studio.cluvex.aether.vpn.AetherVpnService

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

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        viewModelScope.launch { _coreVersion.value = CoreProvider.core.version() }
        viewModelScope.launch { meterTraffic() }
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


    fun clearLogs() = CoreLog.clear()

    fun onVpnPermissionDenied() {
        _messages.tryEmit(getApplication<Application>().getString(R.string.vpn_permission_denied))
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
