package io.github.mhammaddawudi.freeport

import android.app.Application
import io.github.mhammaddawudi.freeport.core.AetherEngine
import io.github.mhammaddawudi.freeport.core.CoreLog
import io.github.mhammaddawudi.freeport.core.CoreProvider
import io.github.mhammaddawudi.freeport.core.IphoneExporter
import io.github.mhammaddawudi.freeport.core.NoEndpointCache
import io.github.mhammaddawudi.freeport.core.PrefsEndpointCache
import io.github.mhammaddawudi.freeport.core.PsiphonRelay
import io.github.mhammaddawudi.freeport.core.SimulatedTrafficMeter
import io.github.mhammaddawudi.freeport.core.TrafficMeter
import io.github.mhammaddawudi.freeport.core.UidTrafficMeter
import io.github.mhammaddawudi.freeport.data.SettingsRepository
import io.github.mhammaddawudi.freeport.vpn.HevTunBridge
import io.github.mhammaddawudi.freeport.vpn.ProxyOnlyBridge
import io.github.mhammaddawudi.freeport.vpn.TunBridge
import io.github.mhammaddawudi.freeport.vpn.VpnNotifications

class AetherApp : Application() {

    /**
     * Whether any screen of the app is on display.
     *
     * The core keeps its proxy and tunnel tasks alive even after its job is
     * cancelled, and only the process ending really clears them. So when a
     * session ends with nobody looking at the app, the process is allowed to
     * go — the next connection then starts from a clean core.
     */
    @Volatile
    var isUiVisible: Boolean = false
        private set

    lateinit var settings: SettingsRepository
        private set
    lateinit var engine: AetherEngine
        private set
    lateinit var trafficMeter: TrafficMeter
        private set

    /** Makes WireGuard configs for iPhones, each on a device of its own. */
    val iphoneExporter: IphoneExporter by lazy {
        IphoneExporter(java.io.File(filesDir, "identity"), CoreProvider.core)
    }

    /**
     * Falls back to [ProxyOnlyBridge] when the tun2socks library is not in
     * the apk, so the app still runs — it just cannot capture the device.
     */
    val tunBridge: TunBridge by lazy {
        val hev = HevTunBridge(this)
        if (hev.isAvailable) hev else ProxyOnlyBridge
    }

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(io.github.mhammaddawudi.freeport.data.Locales.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        val cache = if (CoreProvider.isNative) PrefsEndpointCache(this) else NoEndpointCache
        val relay = if (CoreProvider.isNative) PsiphonRelay(this) else null
        engine = AetherEngine(this, settings, CoreProvider.core, cache, relay)
        trafficMeter = if (CoreProvider.isNative) UidTrafficMeter() else SimulatedTrafficMeter()
        VpnNotifications.ensureChannel(this)
        registerActivityLifecycleCallbacks(
            object : android.app.Application.ActivityLifecycleCallbacks {
                private var started = 0
                override fun onActivityStarted(activity: android.app.Activity) {
                    started++
                    isUiVisible = true
                }

                override fun onActivityStopped(activity: android.app.Activity) {
                    started--
                    if (started <= 0) isUiVisible = false
                }

                override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) = Unit
                override fun onActivityResumed(activity: android.app.Activity) = Unit
                override fun onActivityPaused(activity: android.app.Activity) = Unit
                override fun onActivitySaveInstanceState(activity: android.app.Activity, out: android.os.Bundle) = Unit
                override fun onActivityDestroyed(activity: android.app.Activity) = Unit
            },
        )
        CoreLog.info("[*] Aether mobile · ${if (CoreProvider.isNative) "native core" else "mock core"}")
    }
}
