package io.github.mhammaddawudi.freeport.vpn

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import io.github.mhammaddawudi.freeport.AetherApp
import io.github.mhammaddawudi.freeport.MainActivity
import io.github.mhammaddawudi.freeport.R
import io.github.mhammaddawudi.freeport.core.AetherEngine
import io.github.mhammaddawudi.freeport.core.ConnectionState
import io.github.mhammaddawudi.freeport.core.CoreLog
import io.github.mhammaddawudi.freeport.core.CoreProvider
import io.github.mhammaddawudi.freeport.core.TunMode
import io.github.mhammaddawudi.freeport.data.AetherSettings
import io.github.mhammaddawudi.freeport.data.TunnelMode
import java.net.InetAddress

/**
 * Hosts a session: keeps the process in the foreground, owns the TUN
 * interface, and hands both to [AetherEngine].
 *
 * The app's own uid is excluded from the VPN. That is what lets the core's
 * sockets to the edge leave over the real network instead of looping back
 * into the tunnel, without the Rust side needing to call `protect()`.
 */
class AetherVpnService : VpnService(), AetherEngine.TunnelHost {

    private val app: AetherApp get() = application as AetherApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private val tunLock = Any()
    private var tun: ParcelFileDescriptor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var activeNetwork: Network? = null
    private var networkWatch: ConnectivityManager.NetworkCallback? = null

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(io.github.mhammaddawudi.freeport.data.Locales.wrap(base))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            // Tear the interface down first. The core can take seconds to let
            // go of its proxy, and the user should not keep being tunnelled —
            // or keep looking at the notification — while that happens.
            onTunnelDown()
            stopWatchingNetwork()
            releaseCpu()
            stopForeground(STOP_FOREGROUND_REMOVE)
            app.engine.disconnect { scope.launch { shutDown() } }
            return START_NOT_STICKY
        }

        // ACTION_CONNECT from the app, or android.net.VpnService from the
        // system when the user has made Aether the always-on VPN.
        goForeground(app.engine.state.value)
        holdCpuAwake()
        watchNetwork()
        watchState()
        app.engine.connect(this)
        return START_STICKY
    }

    /**
     * Another VPN took over, or the user revoked consent.
     *
     * The interface is already gone at this point, so tear the tunnel down
     * synchronously before handing the session to the engine — waiting for
     * the engine's coroutine would leave tun2socks alive in between.
     */
    override fun onRevoke() {
        CoreLog.warn("[-] another VPN took over, or permission was revoked")
        onTunnelDown()
        app.engine.disconnect { scope.launch { shutDown() } }
        super.onRevoke()
    }

    override fun onDestroy() {
        watcher?.cancel()
        scope.cancel()
        stopWatchingNetwork()
        releaseCpu()
        onTunnelDown()
        super.onDestroy()
    }

    override fun onTunnelUp(settings: AetherSettings, mappedDns: Boolean): TunMode {
        val bridge = app.tunBridge
        if (!bridge.isAvailable) return TunMode.ProxyOnly

        // Capturing the device while the core is the mock would send every
        // packet at a proxy that is not listening, and the phone would simply
        // lose its network. Proxy mode is the honest state until the real
        // core is linked in.
        if (!CoreProvider.isNative) {
            CoreLog.warn("[-] mock core: leaving device traffic alone, only the local proxy is simulated")
            return TunMode.ProxyOnly
        }

        val builder = Builder()
            .setSession("${getString(R.string.app_name)} · ${settings.protocol.displayName}")
            .setMtu(TUN_MTU)
            .addAddress(TUN_V4, 30)
            .addRoute("0.0.0.0", 0)
            // Capturing v6 even when the tunnel only carries v4 is deliberate:
            // unrouted v6 fails fast and apps fall back, where uncaptured v6
            // would quietly leave outside the tunnel.
            .addAddress(TUN_V6, 126)
            .addRoute("::", 0)
            .setConfigureIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
                ),
            )

        // The region-unlock exit carries no UDP, so DNS is answered by
        // tun2socks itself and every name is resolved at the exit abroad.
        if (mappedDns) {
            builder.addDnsServer(MAPPED_DNS)
        } else {
            builder.addDnsServer("1.1.1.1").addDnsServer("1.0.0.1")
        }

        if (settings.bypassLan && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            LAN_PREFIXES.forEach { (address, length) ->
                builder.excludeRoute(IpPrefix(InetAddress.getByName(address), length))
            }
        }
        applyRouting(builder, settings)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        val established = try {
            builder.establish()
        } catch (e: RuntimeException) {
            CoreLog.error("[!] could not create the tun interface: ${e.message}")
            null
        } ?: return TunMode.ProxyOnly

        synchronized(tunLock) {
            tun?.close()
            tun = established
        }
        bridge.start(established.fd, TUN_MTU, settings.socksPort, mappedDns)
        CoreLog.good("[+] capturing device traffic through ${bridge.name}")
        return TunMode.FullDevice
    }

    /**
     * Whole device, or only the packages the user picked.
     *
     * Either way this app's own uid stays outside the tunnel: with an allow
     * list it is simply never added, and with the whole device it is excluded
     * explicitly. That is what lets the core's sockets reach the edge instead
     * of looping back into the tunnel they are carrying.
     */
    private fun applyRouting(builder: Builder, settings: AetherSettings) {
        when (settings.tunnelMode) {
            TunnelMode.WholeDevice ->
                runCatching { builder.addDisallowedApplication(packageName) }
                    .onFailure { CoreLog.warn("[-] could not exclude this app from the tunnel: ${it.message}") }

            TunnelMode.SelectedApps -> {
                var routed = 0
                settings.tunneledApps.forEach { name ->
                    if (name == packageName) return@forEach
                    // A package uninstalled since it was ticked throws here.
                    runCatching { builder.addAllowedApplication(name) }
                        .onSuccess { routed++ }
                        .onFailure { CoreLog.warn("[-] skipping ${name}: ${it.message}") }
                }
                if (routed == 0) {
                    CoreLog.warn("[-] no app selected for the tunnel, so nothing is being routed through it")
                } else {
                    CoreLog.info("[*] routing ${routed} app${if (routed == 1) "" else "s"} through the tunnel")
                }
            }
        }
    }

    /**
     * Always stops the tunnel engine, whether or not this service still holds
     * a descriptor.
     *
     * The earlier version bailed out when [tun] was already null, which left
     * hev-socks5-tunnel running against a closed fd: traffic kept being
     * captured after a disconnect, and the next connect was refused because
     * the library was still marked as running.
     */
    override fun onTunnelDown() {
        synchronized(tunLock) {
            runCatching { app.tunBridge.stop() }
            tun?.let { runCatching { it.close() } }
            tun = null
        }
    }

    /**
     * Holds a partial wake lock for as long as the tunnel is up.
     *
     * With the screen off the device sleeps the CPU, and the core's timers
     * and keepalives stop firing long enough for the tunnel to die — which is
     * why it used to need a manual reconnect after every screen-off. A
     * foreground service alone does not prevent that; a wake lock does.
     */
    private fun holdCpuAwake() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "helo:tunnel")
            ?.apply {
                setReferenceCounted(false)
                runCatching { acquire() }
            }
    }

    private fun releaseCpu() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    /**
     * Follows whichever real network is carrying traffic.
     *
     * It only records the network and points the VPN at it. An earlier
     * version also reconnected whenever the default network changed, which
     * looped forever: bringing the tunnel up *is* a network change, so the
     * callback tore down the tunnel it had just built, over and over. A
     * genuine network switch drops the tunnel on its own, and the retry loop
     * brings it back.
     */
    private fun watchNetwork() {
        if (networkWatch != null) return
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Ignore our own tunnel; it is not something to ride on.
                val notVpn = manager.getNetworkCapabilities(network)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) ?: true
                if (!notVpn) return

                if (activeNetwork != network) {
                    activeNetwork = network
                    CoreLog.info("[*] carrying the tunnel over a new network")
                }
                runCatching { setUnderlyingNetworks(arrayOf(network)) }
            }

            override fun onLost(network: Network) {
                if (network == activeNetwork) {
                    activeNetwork = null
                    CoreLog.warn("[-] the network went away, waiting for another")
                }
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkWatch = callback }
    }

    private fun stopWatchingNetwork() {
        val callback = networkWatch ?: return
        runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
        networkWatch = null
        activeNetwork = null
    }

    private fun watchState() {
        if (watcher?.isActive == true) return
        watcher = scope.launch {
            app.engine.state.drop(1).collect { state ->
                when (state) {
                    is ConnectionState.Failed -> shutDown()
                    else -> getSystemService(NotificationManager::class.java)
                        .notify(VpnNotifications.NOTIFICATION_ID, VpnNotifications.build(this@AetherVpnService, state))
                }
            }
        }
    }

    private fun goForeground(state: ConnectionState) {
        val notification = VpnNotifications.build(this, state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                VpnNotifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(VpnNotifications.NOTIFICATION_ID, notification)
        }
    }

    private fun shutDown() {
        watcher?.cancel()
        watcher = null
        stopWatchingNetwork()
        releaseCpu()
        onTunnelDown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        // Nothing on screen: let the process end so the core's leftover tasks
        // go with it. With the app open we leave it be — the next session
        // simply takes another port.
        if (!app.isUiVisible) {
            CoreLog.info("[*] session over and the app is closed, clearing the core")
            scope.launch {
                kotlinx.coroutines.delay(400)
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    companion object {
        const val ACTION_CONNECT = "io.github.mhammaddawudi.freeport.CONNECT"
        const val ACTION_DISCONNECT = "io.github.mhammaddawudi.freeport.DISCONNECT"

        private const val TUN_MTU = 1500
        private const val TUN_V4 = "10.18.19.1"
        private const val TUN_V6 = "fd18:1900::1"

        private val LAN_PREFIXES = listOf(
            "10.0.0.0" to 8,
            "172.16.0.0" to 12,
            "192.168.0.0" to 16,
            "169.254.0.0" to 16,
            "fe80::" to 10,
            "fc00::" to 7,
        )

        fun connect(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AetherVpnService::class.java).setAction(ACTION_CONNECT),
            )
        }

        fun disconnect(context: Context) {
            context.startService(Intent(context, AetherVpnService::class.java).setAction(ACTION_DISCONNECT))
        }
    }
}
