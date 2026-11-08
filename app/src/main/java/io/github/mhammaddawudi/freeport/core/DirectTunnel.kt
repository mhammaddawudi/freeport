package io.github.mhammaddawudi.freeport.core

import android.content.Context

/**
 * A protocol that is a whole tunnel of its own, outside Cloudflare: it runs
 * as a separate client, serves SOCKS5 on a local port, and picks its own
 * servers. Psiphon and Tor are these. The engine starts one, points
 * tun2socks at its port, and watches it; there is no edge to scan or rank.
 */
interface DirectTunnel {
    /** For log lines. */
    val name: String
    val isAvailable: Boolean
    val health: PsiphonRelay.Health

    /** Starts it serving SOCKS5 on [socksPort]; null once it carries traffic, else why not. */
    suspend fun open(socksPort: Int, timeoutMs: Long): String?

    fun close()
}

/** Psiphon on its own, straight over the line, every protocol it knows. */
class PsiphonDirect(context: Context, log: CoreLog = CoreLog) : DirectTunnel {
    private val client = PsiphonRelay(context, log, folder = "psiphon-direct", label = "psiphon")

    override val name = "Psiphon"
    override val isAvailable: Boolean get() = client.isAvailable
    override val health: PsiphonRelay.Health get() = client.health

    override suspend fun open(socksPort: Int, timeoutMs: Long): String? =
        client.start(upstreamSocksPort = null, localPort = socksPort, country = "", timeoutMs = timeoutMs)

    override fun close() = client.stop()
}
