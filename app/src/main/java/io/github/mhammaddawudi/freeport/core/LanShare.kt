package io.github.mhammaddawudi.freeport.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Lets devices on the same Wi-Fi (or the phone's hotspot) use the tunnel as a
 * SOCKS5 and an HTTP proxy. SOCKS is relayed byte for byte to the session's
 * local SOCKS port; HTTP is turned into SOCKS here, so it works for every
 * protocol, Psiphon and Tor included. The app is outside its own VPN, so these
 * sockets reach the phone's own SOCKS port and the LAN directly.
 */
class LanShare {
    private var servers: List<ServerSocket> = emptyList()

    @Volatile
    private var upstreamPort = 0

    val isRunning: Boolean get() = servers.isNotEmpty()

    @Synchronized
    fun start(socksPort: Int) {
        upstreamPort = socksPort
        if (isRunning) return
        servers = runCatching {
            listOf(
                listen(SOCKS_PORT) { client -> pipe(client, connectLocal()) },
                listen(HTTP_PORT, ::serveHttp),
            )
        }.getOrElse {
            CoreLog.warn("[-] Wi-Fi sharing: ${it.message}")
            emptyList()
        }
        if (isRunning) CoreLog.info("[+] Wi-Fi sharing on ${address() ?: "?"}:$SOCKS_PORT (SOCKS5), :$HTTP_PORT (HTTP)")
    }

    @Synchronized
    fun stop() {
        servers.forEach { runCatching { it.close() } }
        servers = emptyList()
    }

    private fun listen(port: Int, serve: (Socket) -> Unit): ServerSocket {
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(port), BACKLOG)
        thread(name = "lan-share-$port", isDaemon = true) {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    runCatching { serve(client) }
                    runCatching { client.close() }
                }
            }
        }
        return server
    }

    private fun connectLocal(): Socket = Socket().apply {
        tcpNoDelay = true
        connect(InetSocketAddress("127.0.0.1", upstreamPort), CONNECT_TIMEOUT_MS)
    }

    /** CONNECT is tunnelled; a plain request is sent on in origin form. */
    private fun serveHttp(client: Socket) {
        val input = client.getInputStream()
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size < 3) return
        val (method, target, version) = parts
        val out = client.getOutputStream()
        if (method.equals("CONNECT", ignoreCase = true)) {
            val (host, port) = splitHostPort(target, 443) ?: return
            val upstream = socksConnect(host, port) ?: return reply(out, "502 Bad Gateway")
            reply(out, "200 Connection Established")
            pipe(client, upstream)
            return
        }
        val url = runCatching { java.net.URI(target) }.getOrNull()
        val host = url?.host ?: return reply(out, "400 Bad Request")
        val port = if (url.port > 0) url.port else 80
        val path = (url.rawPath?.ifEmpty { null } ?: "/") + (url.rawQuery?.let { "?$it" } ?: "")
        val headers = lines.drop(1).filter {
            it.isNotEmpty() && !it.startsWith("Proxy-", ignoreCase = true) && !it.startsWith("Connection:", ignoreCase = true)
        }
        val upstream = socksConnect(host, port) ?: return reply(out, "502 Bad Gateway")
        val rewritten = buildString {
            append("$method $path $version\r\n")
            headers.forEach { append(it).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
        upstream.getOutputStream().write(rewritten.toByteArray(Charsets.ISO_8859_1))
        pipe(client, upstream)
    }

    private fun socksConnect(host: String, port: Int): Socket? {
        val socket = runCatching { connectLocal() }.getOrNull() ?: return null
        return runCatching {
            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            out.write(byteArrayOf(5, 1, 0))
            if (readExactly(input, 2)[1].toInt() != 0) error("auth refused")
            val name = host.removePrefix("[").removeSuffix("]").toByteArray(Charsets.US_ASCII)
            out.write(byteArrayOf(5, 1, 0, 3, name.size.toByte()) + name + byteArrayOf((port shr 8).toByte(), port.toByte()))
            val reply = readExactly(input, 4)
            if (reply[1].toInt() != 0) error("connect refused ${reply[1]}")
            val skip = when (reply[3].toInt()) {
                1 -> 4
                4 -> 16
                3 -> input.read()
                else -> error("bad reply")
            }
            readExactly(input, skip + 2)
            socket
        }.getOrElse {
            runCatching { socket.close() }
            null
        }
    }

    companion object {
        const val SOCKS_PORT = 10808
        const val HTTP_PORT = 10809
        private const val BACKLOG = 64
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val MAX_HEAD = 16 * 1024

        /** The phone's IPv4 on Wi-Fi or its own hotspot, the one others type in. */
        fun address(): String? {
            val candidates = runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { nic -> nic.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nic.name to it } }
                .filter { (_, ip) -> ip.isSiteLocalAddress }
            val preferred = candidates.firstOrNull { (name, _) -> LAN_NAMES.any { name.startsWith(it) } } ?: candidates.firstOrNull()
            return preferred?.second?.hostAddress
        }

        private val LAN_NAMES = listOf("wlan", "swlan", "ap", "softap", "eth", "rndis")

        internal fun splitHostPort(target: String, fallback: Int): Pair<String, Int>? {
            val colon = target.lastIndexOf(':')
            if (colon <= 0 || target.endsWith("]")) return target.takeIf { it.isNotEmpty() }?.let { it to fallback }
            val port = target.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return target.substring(0, colon) to port
        }

        internal fun readHead(input: InputStream): String? {
            val buffer = ByteArrayOutputStream()
            var matched = 0
            val end = byteArrayOf(13, 10, 13, 10)
            while (buffer.size() < MAX_HEAD) {
                val b = input.read()
                if (b < 0) return null
                buffer.write(b)
                matched = if (b.toByte() == end[matched]) matched + 1 else if (b == 13) 1 else 0
                if (matched == 4) return buffer.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r\n\r\n")
            }
            return null
        }

        private fun readExactly(input: InputStream, n: Int): ByteArray {
            val bytes = ByteArray(n)
            var read = 0
            while (read < n) {
                val got = input.read(bytes, read, n - read)
                if (got < 0) error("closed")
                read += got
            }
            return bytes
        }

        private fun reply(out: OutputStream, status: String) {
            out.write("HTTP/1.1 $status\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }

        /** Copies both ways until either side closes. */
        private fun pipe(a: Socket, b: Socket) {
            val back = thread(isDaemon = true) { copy(b.getInputStream(), a.getOutputStream()); runCatching { a.shutdownOutput() } }
            copy(a.getInputStream(), b.getOutputStream())
            runCatching { b.shutdownOutput() }
            back.join()
            runCatching { b.close() }
        }

        private fun copy(from: InputStream, to: OutputStream) {
            runCatching {
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = from.read(buf)
                    if (n < 0) break
                    to.write(buf, 0, n)
                    to.flush()
                }
            }
        }
    }
}
