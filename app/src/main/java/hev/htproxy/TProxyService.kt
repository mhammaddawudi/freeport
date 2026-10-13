package hev.htproxy

/**
 * The JNI seat for hev-socks5-tunnel.
 *
 * The library's `JNI_OnLoad` looks up this exact class — package and name are
 * compiled into `hev-jni.c` as `hev/htproxy/TProxyService` — and registers
 * its four natives onto it. So the class has to live here, under this name,
 * even though nothing else in the app uses that package.
 *
 * Wrapped by [io.github.mhammaddawudi.freeport.vpn.HevTunBridge]; call that instead.
 */
object TProxyService {
    external fun TProxyStartService(configPath: String, fd: Int): Boolean
    external fun TProxyStopService(): Boolean
    external fun TProxyIsRunning(): Boolean

    /** Four counters: tx packets, tx bytes, rx packets, rx bytes. */
    external fun TProxyGetStats(): LongArray
}
