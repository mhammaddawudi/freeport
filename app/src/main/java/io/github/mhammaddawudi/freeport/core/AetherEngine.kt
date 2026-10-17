package io.github.mhammaddawudi.freeport.core

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import io.github.mhammaddawudi.freeport.data.AetherSettings
import io.github.mhammaddawudi.freeport.data.Protocol
import io.github.mhammaddawudi.freeport.data.ScanMode
import io.github.mhammaddawudi.freeport.data.SettingsRepository
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

/** Whether device traffic is captured, or only the local proxy is up. */
enum class TunMode { FullDevice, ProxyOnly }

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data class Preparing(val protocol: Protocol) : ConnectionState
    data class Scanning(val protocol: Protocol, val progress: ScanProgress) : ConnectionState
    data class Connecting(val protocol: Protocol, val endpoint: Endpoint?) : ConnectionState
    data class Connected(
        val protocol: Protocol,
        /** Null for a managed session, where the core picks the edge privately. */
        val endpoint: Endpoint?,
        val sinceMillis: Long,
        val mode: TunMode,
    ) : ConnectionState
    data class Reconnecting(val protocol: Protocol, val attempt: Int, val reason: String) : ConnectionState
    data class Failed(val protocol: Protocol, val message: String) : ConnectionState
    data object Disconnecting : ConnectionState
}

/** A session is running — the button should offer to stop it. */
val ConnectionState.isEngaged: Boolean
    get() = this !is ConnectionState.Idle && this !is ConnectionState.Failed

val ConnectionState.isTransitioning: Boolean
    get() = this is ConnectionState.Preparing || this is ConnectionState.Scanning ||
        this is ConnectionState.Connecting || this is ConnectionState.Reconnecting ||
        this is ConnectionState.Disconnecting

/**
 * Drives one connection session: identity, route discovery, tunnel, and the
 * reconnect loop around them.
 *
 * The engine owns no Android surface. The VpnService passes itself in as the
 * [TunnelHost], and is told when the tunnel is up (so it can establish the
 * TUN interface) and when it is gone (so it can tear it down).
 *
 * Control calls are serialised through [control], so a double tap or a
 * protocol switch in the middle of a scan cannot leave two sessions alive.
 */
class AetherEngine(
    context: Context,
    private val settings: SettingsRepository,
    private val core: AetherCore,
    private val log: CoreLog = CoreLog,
) {

    interface TunnelHost {
        /** The core's proxy is up. Capture traffic if possible and say how. */
        fun onTunnelUp(settings: AetherSettings): TunMode

        /** The tunnel is gone. Must be safe to call when nothing is up. */
        fun onTunnelDown()
    }

    private val identityDir = File(context.filesDir, "identity").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val control = Mutex()

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _identity = MutableStateFlow<Identity?>(null)
    val identity: StateFlow<Identity?> = _identity.asStateFlow()

    private val identities = ConcurrentHashMap<String, Identity>()

    private var session: Job? = null
    private var host: TunnelHost? = null

    fun connect(host: TunnelHost) {
        scope.launch {
            control.withLock {
                if (session?.isActive == true) return@withLock
                this@AetherEngine.host = host
                session = scope.launch { runSession(host) }
            }
        }
    }

    /** Stops the session and waits for the tunnel to close before [onStopped]. */
    fun disconnect(onStopped: () -> Unit = {}) {
        scope.launch {
            control.withLock { stopSession() }
            onStopped()
        }
    }

    /** Restarts with whatever the settings are now — used when the protocol changes mid-session. */
    fun reconnect() {
        scope.launch {
            control.withLock {
                val current = host ?: return@withLock
                if (session?.isActive != true) return@withLock
                log.info("[*] settings changed, reconnecting")
                stopSession()
                session = scope.launch { runSession(current) }
            }
        }
    }

    private suspend fun stopSession() {
        val running = session
        if (running != null && running.isActive) {
            _state.value = ConnectionState.Disconnecting
            running.cancelAndJoin()
            log.info("[*] disconnected")
        }
        session = null
        _state.value = ConnectionState.Idle
    }

    private suspend fun runSession(host: TunnelHost) {
        var attempt = 0
        var fallback = 0
        try {
            while (true) {
                val chosen = settings.current()
                // After a few failures in a row, try another transport. A
                // network that blocks one of them usually still carries
                // another, and the person cannot be expected to know which.
                val current = if (fallback == 0) chosen else chosen.copy(protocol = alternatives(chosen.protocol)[fallback - 1])
                if (fallback > 0) {
                    log.info("[*] ${chosen.protocol.displayName} is not getting through, trying ${current.protocol.displayName}")
                }
                val outcome = attemptOnce(current, host)
                host.onTunnelDown()

                if (outcome.connectedForMs >= STABLE_MS) {
                    attempt = 0
                    fallback = 0
                }
                attempt++
                if (attempt % ATTEMPTS_PER_TRANSPORT == 0) {
                    fallback = (fallback + 1) % (alternatives(chosen.protocol).size + 1)
                }
                // Never give up on its own. A tunnel usually drops because the
                // screen went off, the network changed or coverage came back —
                // all of which fix themselves. The session ends when the user
                // ends it, not after three tries.
                val wait = (BACKOFF_MS * (1L shl (attempt - 1).coerceAtMost(6)))
                    .coerceAtMost(MAX_BACKOFF_MS)
                log.warn("[-] ${outcome.reason} — retrying in ${wait / 1000}s (attempt $attempt)")
                _state.value = ConnectionState.Reconnecting(current.protocol, attempt, outcome.reason)
                delay(wait)
            }
        } finally {
            withContext(NonCancellable) { host.onTunnelDown() }
        }
    }

    /** Why an attempt ended, and how long it had been carrying traffic. */
    private data class Outcome(val reason: String, val connectedForMs: Long = 0)

    private suspend fun attemptOnce(requested: AetherSettings, host: TunnelHost): Outcome {
        if (requested.protocol == Protocol.Auto) return attemptAuto(requested, host)
        val protocol = requested.protocol
        clearGatewayCache()
        core.applyEnvironment(requested)

        // Cancelling the core's job does not always bring its listener down
        // with it. Rather than fight a port that may never be released — and
        // hand the user a tunnel with nothing behind it — this session takes
        // the next free one and tells tun2socks to use that.
        val settings = withFreePort(requested)

        _state.value = ConnectionState.Preparing(protocol)
        log.info(
            "[*] ${protocol.displayName} · scan ${settings.scanMode.wire} · " +
                "noize ${settings.effectiveNoize.wire} · ipv${settings.ipVersion.wire}",
        )

        if (protocol == Protocol.Gool) return runManaged(settings, host)

        val identity = when (val opened = identityFor(settings)) {
            is CoreResult.Ok -> opened.value
            is CoreResult.Err -> return Outcome("identity: ${opened.message}")
        }

        _state.value = ConnectionState.Scanning(protocol, ScanProgress(ScanProgress.Phase.Provisioning))
        log.info("[*] scanning for a reachable edge")
        val endpoint = when (
            val scanned = core.scan(identity, settings) { progress ->
                _state.value = ConnectionState.Scanning(protocol, progress)
            }
        ) {
            is CoreResult.Ok -> scanned.value
            is CoreResult.Err -> return Outcome("scan: ${scanned.message}")
        }
        log.good("[+] edge ${endpoint.socket} answered in ${endpoint.rttMs}ms")

        return runTunnel(identity, endpoint, settings, host)
    }

    /**
     * Pings MASQUE, MASQUE H2 and WireGuard at the same time with a fast scan
     * each and connects with the one whose edge answered quickest.
     *
     * The two MASQUE carriers cannot scan side by side: the core reads
     * HTTP/2 or HTTP/3 from one process-wide variable. So they share a lane,
     * H2 first (TCP is the one most often left open) with a time limit of its
     * own, while WireGuard, which never reads that variable, runs beside them.
     * Once the first edge answers, the others get a short grace period to
     * beat it, so a fast network is not held up by a blocked transport.
     */
    private suspend fun attemptAuto(requested: AetherSettings, host: TunnelHost): Outcome {
        clearGatewayCache()
        val base = withFreePort(requested)
        _state.value = ConnectionState.Preparing(Protocol.Auto)
        log.info("[*] automatic: pinging ${AUTO_CANDIDATES.joinToString { it.displayName }}")

        val candidates = AUTO_CANDIDATES.associateWith { base.copy(protocol = it, scanMode = ScanMode.Turbo) }
        // Identities are opened one at a time: MASQUE and WireGuard keep
        // theirs in sibling files the core writes on first use.
        val identities = buildMap {
            for ((protocol, settings) in candidates) {
                when (val opened = identityFor(settings)) {
                    is CoreResult.Ok -> put(protocol, opened.value)
                    is CoreResult.Err -> log.warn("[-] ${protocol.displayName} identity: ${opened.message}")
                }
            }
        }

        val found = ConcurrentHashMap<Protocol, Endpoint>()
        _state.value = ConnectionState.Scanning(Protocol.Auto, ScanProgress(ScanProgress.Phase.Probing))

        suspend fun ping(protocol: Protocol) {
            val identity = identities[protocol] ?: return
            val settings = candidates.getValue(protocol)
            if (protocol.transport == "masque") core.applyEnvironment(settings)
            when (val scanned = core.scan(identity, settings) {}) {
                is CoreResult.Ok -> {
                    found[protocol] = scanned.value
                    log.info("[+] ${protocol.displayName} answered in ${scanned.value.rttMs}ms")
                }
                is CoreResult.Err -> log.warn("[-] ${protocol.displayName}: ${scanned.message}")
            }
        }

        coroutineScope {
            val lanes = listOf(
                launch {
                    withTimeoutOrNull(AUTO_LANE_MS) { ping(Protocol.MasqueH2) }
                    ping(Protocol.MasqueH3)
                },
                launch { ping(Protocol.WireGuard) },
            )
            val all = launch { lanes.joinAll() }
            withTimeoutOrNull(AUTO_PING_MS) {
                while (found.isEmpty() && all.isActive) delay(100)
                if (found.isNotEmpty()) withTimeoutOrNull(AUTO_GRACE_MS) { all.join() }
            }
            all.cancel()
            lanes.forEach { it.cancel() }
        }

        val best = AUTO_CANDIDATES
            .mapNotNull { protocol -> found[protocol]?.let { protocol to it } }
            .minByOrNull { (_, endpoint) -> endpoint.rttMs }
        if (best == null) {
            log.warn("[-] automatic: nothing answered, handing over to ${Protocol.Gool.displayName}")
            val gool = base.copy(protocol = Protocol.Gool)
            core.applyEnvironment(gool)
            return runManaged(gool, host)
        }

        val (protocol, endpoint) = best
        val chosen = base.copy(protocol = protocol)
        log.good("[+] automatic: ${protocol.displayName} is the fastest (${endpoint.rttMs}ms)")
        core.applyEnvironment(chosen)
        return runTunnel(identities.getValue(protocol), endpoint, chosen, host)
    }

    private suspend fun runTunnel(
        identity: Identity,
        endpoint: Endpoint,
        settings: AetherSettings,
        host: TunnelHost,
    ): Outcome {
        _state.value = ConnectionState.Connecting(settings.protocol, endpoint)
        log.info("[*] opening ${settings.protocol.displayName} tunnel to ${endpoint.socket}")

        val job = when (val started = core.startTunnel(identity, endpoint, settings)) {
            is CoreResult.Ok -> started.value
            is CoreResult.Err -> return Outcome("tunnel: ${started.message}")
        }

        try {
            // Wait for the core's proxy to accept connections rather than for
            // a fixed moment: tun2socks started against a listener that is not
            // up yet gives a tunnel that reports success and carries nothing.
            core.awaitProxyReady(job, settings, TUNNEL_STARTUP_MS)?.let { reason ->
                return Outcome("tunnel: $reason")
            }

            return holdConnected(job, endpoint, settings, host)
        } finally {
            withContext(NonCancellable) {
                core.stopTunnel(job)
                core.awaitProxyClosed(settings, PORT_RELEASE_MS)
            }
        }
    }

    private suspend fun runManaged(settings: AetherSettings, host: TunnelHost): Outcome {
        _state.value = ConnectionState.Connecting(settings.protocol, null)
        log.info("[*] handing the double hop to the core")

        if (!core.awaitProxyClosed(settings, PORT_RELEASE_MS)) {
            log.warn("[-] the previous session is still holding 127.0.0.1:${settings.socksPort}")
        }

        val job = when (val started = core.startManaged(settings, identityDir.absolutePath)) {
            is CoreResult.Ok -> started.value
            is CoreResult.Err -> return Outcome("core: ${started.message}")
        }

        try {
            core.awaitProxyReady(job, settings, MANAGED_STARTUP_MS)?.let { reason ->
                return Outcome("core: $reason")
            }
            return holdConnected(job, null, settings, host)
        } finally {
            // Wait for the listener to actually go, so the next attempt scans
            // instead of finding this session's proxy still up and calling it
            // a connection.
            withContext(NonCancellable) {
                core.stopTunnel(job)
                core.awaitProxyClosed(settings, PORT_RELEASE_MS)
            }
        }
    }

    /** Marks the session up, captures traffic, and watches the job until it ends. */
    private suspend fun holdConnected(
        job: JobHandle,
        endpoint: Endpoint?,
        settings: AetherSettings,
        host: TunnelHost,
    ): Outcome {
        val mode = host.onTunnelUp(settings)
        val since = System.currentTimeMillis()
        _state.value = ConnectionState.Connected(settings.protocol, endpoint, since, mode)

        log.good("[+] connected${endpoint?.let { " via ${it.socket}" } ?: ""}")
        log.info("[*] SOCKS5 listening on 127.0.0.1:${settings.socksPort}")
        if (settings.httpProxyEnabled) log.info("[*] HTTP CONNECT on 127.0.0.1:${settings.httpProxyPort}")
        if (mode == TunMode.ProxyOnly) {
            log.warn("[-] no tun bridge packaged: device traffic is not captured, point apps at the proxy")
        }

        while (true) {
            delay(POLL_MS)
            val polled = core.pollJob(job)
            if (polled is JobState.Done) {
                val reason = polled.detail.takeIf { it.isNotEmpty() && it != "closed" } ?: "the core closed the tunnel"
                return Outcome(reason, connectedForMs = System.currentTimeMillis() - since)
            }
        }
    }

    /**
     * The other transports, in the order worth trying when one is blocked.
     *
     * Deliberately starts with the one that looks least like the chosen one:
     * if MASQUE over QUIC is being dropped, the next thing to try is not the
     * other MASQUE but a different protocol entirely.
     */
    private fun alternatives(chosen: Protocol): List<Protocol> = when (chosen) {
        // Every automatic attempt already tries them all.
        Protocol.Auto -> emptyList()
        Protocol.Gool -> listOf(Protocol.MasqueH3, Protocol.WireGuard, Protocol.MasqueH2)
        Protocol.MasqueH3 -> listOf(Protocol.WireGuard, Protocol.MasqueH2, Protocol.Gool)
        Protocol.MasqueH2 -> listOf(Protocol.MasqueH3, Protocol.WireGuard, Protocol.Gool)
        Protocol.WireGuard -> listOf(Protocol.MasqueH3, Protocol.MasqueH2, Protocol.Gool)
    }

    /** Picks a local port nothing is sitting on, starting from the chosen one. */
    private fun withFreePort(settings: AetherSettings): AetherSettings {
        val loopback = InetAddress.getByName("127.0.0.1")
        for (port in settings.socksPort..(settings.socksPort + 40)) {
            val free = runCatching { ServerSocket(port, 1, loopback).close() }.isSuccess
            if (free) {
                if (port != settings.socksPort) {
                    log.warn("[-] 127.0.0.1:${settings.socksPort} is still busy, using $port instead")
                }
                return if (port == settings.socksPort) settings else settings.copy(socksPort = port)
            }
        }
        log.error("[!] no free local port near ${settings.socksPort}")
        return settings
    }

    /**
     * Deletes the core's own `*-lastconn.toml` before every attempt.
     *
     * Reusing the last working gateway made the first connection instant but
     * pinned the session to whatever edge happened to answer first, which on
     * warp-in-warp was regularly a slow one. Scanning every time costs a few
     * seconds and picks the route on merit instead.
     */
    private fun clearGatewayCache() {
        identityDir.listFiles()
            ?.filter { it.isFile && it.name.contains("lastconn") }
            ?.forEach { it.delete() }
    }

    private suspend fun identityFor(settings: AetherSettings): CoreResult<Identity> {
        val transport = settings.protocol.transport
        identities[transport]?.let {
            _identity.value = it
            return CoreResult.Ok(it)
        }
        log.info("[*] opening $transport identity")
        val opened = core.openIdentity(File(identityDir, "aether.toml").absolutePath, transport)
        if (opened is CoreResult.Ok) {
            val identity = opened.value
            identities[transport] = identity
            _identity.value = identity
            log.good("[+] identity ready: device=${identity.deviceId} ipv4=${identity.ipv4}")
        }
        return opened
    }

    private companion object {
        const val BACKOFF_MS = 1_000L

        /** Long enough to stop draining the battery while out of coverage. */
        const val MAX_BACKOFF_MS = 60_000L

        /** Failures on one transport before the next one is worth a try. */
        const val ATTEMPTS_PER_TRANSPORT = 2
        /** A direct tunnel should be serving within this long, or it is stuck. */
        const val TUNNEL_STARTUP_MS = 45_000L
        const val POLL_MS = 1_000L
        const val STABLE_MS = 30_000L
        const val MANAGED_STARTUP_MS = 90_000L

        /**
         * A brief grace period for a finished session to release its port.
         * Short on purpose: the next attempt just moves to another port.
         */
        const val PORT_RELEASE_MS = 1_500L

        /** What automatic pings, and in what order a tie is settled. */
        val AUTO_CANDIDATES = listOf(Protocol.MasqueH3, Protocol.MasqueH2, Protocol.WireGuard)

        /** The whole automatic ping round. */
        const val AUTO_PING_MS = 15_000L

        /** How long MASQUE H2 may hold the shared lane before H3 gets it. */
        const val AUTO_LANE_MS = 7_000L

        /** How long the others may still beat the first edge that answered. */
        const val AUTO_GRACE_MS = 2_500L

    }
}
