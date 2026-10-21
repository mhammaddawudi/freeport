package io.github.mhammaddawudi.freeport.vpn

/**
 * Moves packets between the TUN interface and the core's SOCKS5 listener.
 *
 * Aether is a proxy, not a packet tunnel: it accepts SOCKS5 connections on
 * 127.0.0.1. To capture a whole device something has to read raw IP packets
 * from the TUN file descriptor, terminate TCP and UDP, and replay each flow
 * as a SOCKS5 connection. That is a tun2socks.
 *
 * The app ships without one so the core choice stays yours. When
 * [isAvailable] is false the service skips the TUN interface entirely and
 * runs in proxy-only mode — the SOCKS5 and HTTP proxies still work for any
 * app pointed at them, and nothing black-holes the device's traffic.
 *
 * See README.md for wiring hev-socks5-tunnel in behind this interface.
 */
interface TunBridge {
    val name: String
    val isAvailable: Boolean

    /**
     * Starts forwarding. [fd] stays owned by the caller. With [mappedDns],
     * DNS sent to [MAPPED_DNS] is answered locally with placeholder
     * addresses, and connections to those reach the proxy by hostname.
     */
    fun start(fd: Int, mtu: Int, socksPort: Int, mappedDns: Boolean = false)

    /** Stops forwarding. Safe to call when not started. */
    fun stop()
}

object ProxyOnlyBridge : TunBridge {
    override val name = "proxy-only"
    override val isAvailable = false
    override fun start(fd: Int, mtu: Int, socksPort: Int, mappedDns: Boolean) = Unit
    override fun stop() = Unit
}

/** Where the mapped resolver answers, inside the 198.18.0.0/15 benchmark range. */
const val MAPPED_DNS = "198.18.0.2"
