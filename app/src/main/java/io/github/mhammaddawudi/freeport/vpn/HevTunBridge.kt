package io.github.mhammaddawudi.freeport.vpn

import android.content.Context
import hev.htproxy.TProxyService
import io.github.mhammaddawudi.freeport.core.CoreLog
import java.io.File

/**
 * Turns the TUN interface into SOCKS5 connections, using hev-socks5-tunnel.
 *
 * The library terminates TCP and UDP from the raw IP packets and replays each
 * flow against the core's local proxy. UDP goes over SOCKS5 UDP ASSOCIATE,
 * which Aether's `socks.rs` implements — without it DNS inside the tunnel
 * would have nowhere to go.
 */
class HevTunBridge(private val context: Context) : TunBridge {

    override val name = "hev-socks5-tunnel"

    override val isAvailable: Boolean by lazy {
        runCatching { System.loadLibrary("hev-socks5-tunnel") }
            .onFailure { CoreLog.error("[!] tun2socks could not be loaded: ${it.message}") }
            .isSuccess
    }

    /**
     * With [mappedDns], placeholder addresses come from 198.19.0.0/16, the
     * half of the benchmark range the resolver itself does not sit in:
     * nothing real lives there, so they never shadow a real destination.
     */
    override fun start(fd: Int, mtu: Int, socksPort: Int, mappedDns: Boolean) {
        if (!isAvailable) return
        // A previous session that ended badly can leave the library running,
        // and it refuses to start twice. Clear that first.
        if (runCatching { TProxyService.TProxyIsRunning() }.getOrDefault(false)) {
            CoreLog.warn("[-] tun2socks was still running from an earlier session, stopping it first")
            runCatching { TProxyService.TProxyStopService() }
        }
        val config = File(context.cacheDir, "tun2socks.yml")
        val mapped = if (mappedDns) {
            """
            |mapdns:
            |  address: $MAPPED_DNS
            |  port: 53
            |  network: 198.19.0.0
            |  netmask: 255.255.0.0
            |  cache-size: 10000
            |
            """.trimMargin()
        } else {
            ""
        }
        config.writeText(
            """
            |tunnel:
            |  mtu: $mtu
            |socks5:
            |  address: 127.0.0.1
            |  port: $socksPort
            |  udp: 'udp'
            |$mapped
            |misc:
            |  task-stack-size: 20480
            |  log-level: warn
            """.trimMargin(),
        )
        val started = runCatching { TProxyService.TProxyStartService(config.absolutePath, fd) }
            .getOrElse {
                CoreLog.error("[!] tun2socks refused to start: ${it.message}")
                false
            }
        if (!started) CoreLog.warn("[-] tun2socks did not start; device traffic is not being carried")
    }

    /** Safe to call at any time, including when nothing is running. */
    override fun stop() {
        if (!isAvailable) return
        runCatching { TProxyService.TProxyStopService() }
            .onFailure { CoreLog.warn("[-] tun2socks did not stop cleanly: ${it.message}") }
    }
}
