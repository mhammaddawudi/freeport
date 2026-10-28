package io.github.mhammaddawudi.freeport.core

import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Asks a local SOCKS5 proxy to open a connection, and reports whether it did.
 *
 * Since 2.3.4 the core binds its port before the tunnel is up and holds it for
 * the whole run, so a port that accepts says nothing about the tunnel. A
 * granted CONNECT does: the proxy only grants one once it has reached the
 * host through the tunnel. Only the CONNECT is made; nothing is sent to the
 * host.
 */
object SocksProbe {
    const val HOST = "www.cloudflare.com"
    const val TIMEOUT_MS = 8_000

    /** Blocking; call it off the main thread. */
    fun carries(port: Int, host: String = HOST, timeoutMs: Int = TIMEOUT_MS): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            socket.soTimeout = timeoutMs
            val output = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream())

            output.write(byteArrayOf(0x05, 0x01, 0x00))
            val greeting = ByteArray(2).also { input.readFully(it) }
            if (greeting[0] != 0x05.toByte() || greeting[1] != 0x00.toByte()) return@use false

            val name = host.toByteArray(Charsets.US_ASCII)
            output.write(byteArrayOf(0x05, 0x01, 0x00, 0x03, name.size.toByte()) + name + byteArrayOf(0x01, 0xBB.toByte()))
            val reply = ByteArray(4).also { input.readFully(it) }
            reply[1] == 0x00.toByte()
        }
    }.getOrDefault(false)
}
