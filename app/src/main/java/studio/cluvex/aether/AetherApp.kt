package studio.cluvex.aether

import android.app.Application
import studio.cluvex.aether.core.AetherEngine
import studio.cluvex.aether.core.CoreLog
import studio.cluvex.aether.core.CoreProvider
import studio.cluvex.aether.core.SimulatedTrafficMeter
import studio.cluvex.aether.core.TrafficMeter
import studio.cluvex.aether.core.UidTrafficMeter
import studio.cluvex.aether.data.SettingsRepository
import studio.cluvex.aether.vpn.HevTunBridge
import studio.cluvex.aether.vpn.ProxyOnlyBridge
import studio.cluvex.aether.vpn.TunBridge
import studio.cluvex.aether.vpn.VpnNotifications

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

    /**
     * Falls back to [ProxyOnlyBridge] when the tun2socks library is not in
     * the apk, so the app still runs — it just cannot capture the device.
     */
    val tunBridge: TunBridge by lazy {
        val hev = HevTunBridge(this)
        if (hev.isAvailable) hev else ProxyOnlyBridge
    }

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(studio.cluvex.aether.data.Locales.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        engine = AetherEngine(this, settings, CoreProvider.core)
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
