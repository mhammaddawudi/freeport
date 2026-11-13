package io.github.mhammaddawudi.freeport.core

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import io.github.mhammaddawudi.freeport.data.WarpSettings
import io.github.mhammaddawudi.freeport.data.Protocol
import io.github.mhammaddawudi.freeport.data.ScanMode
import io.github.mhammaddawudi.freeport.data.SettingsRepository
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

/** Whether device traffic is captured, or only the local proxy is up. */
enum class TunMode { FullDevice, ProxyOnly }

/**
 * A WireGuard tunnel that takes the device's packets itself: the tun carries
 * the tunnel's own addresses, and [attach] hands the core a descriptor it
 * then owns. No tun2socks and no second TCP stack in between.
 */
class PacketPath(val ipv4: String, val ipv6: String, val attach: (fd: Int) -> Boolean)

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data class Preparing(val protocol: Protocol) : ConnectionState
    data class Scanning(val protocol: Protocol, val progress: ScanProgress) : ConnectionState
    data class Connecting(
        val protocol: Protocol,
        val endpoint: Endpoint?,
        /** Automatic chose [protocol]; the person selected Automatic. */
        val viaAuto: Boolean = false,
        /** The tunnel is up and the region-unlock exit is being set up behind it. */
        val relaying: Boolean = false,
    ) : ConnectionState
    data class Connected(
        /** The protocol carrying the session — never [Protocol.Auto]. */
        val protocol: Protocol,
        /** Null for a managed session, where the core picks the edge privately. */
        val endpoint: Endpoint?,
        val sinceMillis: Long,
        val mode: TunMode,
        /** Automatic chose [protocol]; the person selected Automatic. */
        val viaAuto: Boolean = false,
        /** Where this session's SOCKS5 proxy listens; may differ from the setting. */
        val socksPort: Int = 0,
        /** Traffic leaves through the Psiphon exit rather than Cloudflare's. */
        val relayed: Boolean = false,
        /** The country sites see, once looked up; null until then. */
        val exitCountry: String? = null,
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
class WarpEngine(
    context: Context,
    private val settings: SettingsRepository,
    private val core: WarpCore,
    private val cache: EndpointCache = NoEndpointCache,
    private val relay: PsiphonRelay? = null,
    private val board: EdgeBoard = NoEdgeBoard,
    private val networkOf: () -> String = { NetworkKey.UNKNOWN },
    private val scout: BackgroundScout? = null,
    private val traffic: TunnelTraffic = TunnelTraffic(null),
    /** Psiphon and Tor: tunnels of their own, outside Cloudflare. See [DirectTunnel]. */
    private val direct: Map<Protocol, DirectTunnel> = emptyMap(),
    private val log: CoreLog = CoreLog,
    private val tuner: Tuner = Tuner(MemoryTuningStore()),
) {

    interface TunnelHost {
        /**
         * The proxy at [WarpSettings.socksPort] is up. Capture traffic if
         * possible and say how. [mappedDns] asks for DNS to be answered
         * locally, so connections reach the proxy by name — needed by a proxy
         * that carries no UDP.
         */
        fun onTunnelUp(settings: WarpSettings, mappedDns: Boolean = false, packet: PacketPath? = null): TunMode

        /** The tunnel is gone. Must be safe to call when nothing is up. */
        fun onTunnelDown()

        /**
         * A fresh tunnel replaced the old one under a capture that is still
         * up: point the bridge at its proxy without rebuilding the interface,
         * so the phone never sees the VPN go down. Null when there is no
         * capture to keep, and the caller sets one up from scratch.
         */
        fun onTunnelSwapped(settings: WarpSettings, mappedDns: Boolean = false, packet: PacketPath? = null): TunMode? = null
    }

    private val identityDir = File(context.filesDir, "identity").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val control = Mutex()

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /**
     * How each protocol answered on this network lately: the edge's round
     * trip in ms, or [NO_ANSWER]. Shown beside the protocols so a manual
     * choice can go to one that works here.
     */
    private val _reach = MutableStateFlow<Map<Protocol, Long>>(emptyMap())
    val reach: StateFlow<Map<Protocol, Long>> = _reach.asStateFlow()

    private val _probing = MutableStateFlow(false)
    val probing: StateFlow<Boolean> = _probing.asStateFlow()

    @Volatile private var reachNetwork = ""
    private var probeJob: Job? = null

    /** Set while Automatic measures a protocol it has not measured here, before choosing. */
    @Volatile private var auditioning = false

    private val _identity = MutableStateFlow<Identity?>(null)
    val identity: StateFlow<Identity?> = _identity.asStateFlow()

    private val identities = ConcurrentHashMap<String, Identity>()

    private var session: Job? = null
    private var host: TunnelHost? = null

    /** The operator this attempt runs on; edges are remembered and shared per operator. */
    @Volatile private var network: String = NetworkKey.UNKNOWN

    /** Edges this session found too slow; scans skip them. */
    private val slowEdges = ConcurrentHashMap.newKeySet<String>()

    /** How many times this session has dropped a slow edge for another. */
    @Volatile private var rotations = 0

    /** What the current edge delivered when it was chosen, in KB/s; 0 when unmeasured. */
    @Volatile private var baseline = 0L

    /**
     * Set while a slowed-down session is being replaced under a capture that
     * stays up: the person keeps seeing the session they had, timer and all.
     */
    @Volatile private var carryOver: CarryOver? = null

    /** When this session refreshed its tunnel, to keep that to a few times an hour. */
    private val refreshes = ArrayDeque<Long>()

    private data class CarryOver(val sinceMillis: Long)

    /** Set while Automatic leaves Psiphon or Tor to see whether WARP carries again. */
    @Volatile private var returningToWarp = false

    /** How long Automatic stays on Psiphon or Tor before looking at WARP again; doubles after each failed look. */
    @Volatile private var warpReturnMs = WARP_RETURN_MS

    /** Publishes [next], unless it is a step of a refresh the person should not see. */
    private fun show(next: ConnectionState) {
        if (carryOver != null && next.isTransitioning) return
        _state.value = next
    }


    fun connect(host: TunnelHost) {
        scope.launch {
            probeJob?.cancelAndJoin()
            control.withLock {
                if (session?.isActive == true) return@withLock
                this@WarpEngine.host = host
                session = scope.launch { runSession(host) }
            }
        }
    }

    /**
     * Pings every WARP protocol on this network while nothing is connected,
     * the way Automatic scans, so each one's round trip can be shown.
     */
    fun probe() {
        if (session?.isActive == true || probeJob?.isActive == true) return
        probeJob = scope.launch {
            _probing.value = true
            try {
                network = networkOf()
                val base = settings.current()
                val tries = AUTO_CANDIDATES.associateWith { tuned(base.copy(protocol = it)) }
                // One at a time: MASQUE and WireGuard keep sibling identity files.
                val opened = tries.mapValues { (_, chosen) -> (identityFor(chosen) as? CoreResult.Ok)?.value }
                suspend fun one(protocol: Protocol) {
                    val chosen = tries.getValue(protocol)
                    val identity = opened[protocol] ?: return noteReach(protocol, NO_ANSWER)
                    if (protocol.transport == "masque") core.applyEnvironment(chosen)
                    val rtt = withTimeoutOrNull(AUTO_LANE_MS) {
                        when (val scanned = core.scan(identity, chosen, emptySet(), onRunnersUp = { pool(chosen, it) }) {}) {
                            is CoreResult.Ok -> scanned.value.rttMs
                            is CoreResult.Err -> null
                        }
                    }
                    noteReach(protocol, rtt ?: NO_ANSWER)
                }
                coroutineScope {
                    launch {
                        one(Protocol.MasqueH2)
                        one(Protocol.MasqueH3)
                    }
                    launch { one(Protocol.WireGuard) }
                }
            } finally {
                _probing.value = false
            }
        }
    }

    private fun noteReach(protocol: Protocol, rttMs: Long) {
        val here = network
        val before = if (reachNetwork == here) _reach.value else emptyMap()
        reachNetwork = here
        _reach.value = before + (protocol to rttMs)
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
        slowEdges.clear()
        rotations = 0
        carryOver = null
        refreshes.clear()
        returningToWarp = false
        warpReturnMs = WARP_RETURN_MS
        try {
            while (true) {
                network = networkOf()
                val chosen = settings.current()
                // After a few failures in a row, try another transport. A
                // network that blocks one of them usually still carries
                // another, and the person cannot be expected to know which.
                val current = if (fallback == 0) chosen else chosen.copy(protocol = alternatives(chosen.protocol)[fallback - 1])
                if (fallback > 0) {
                    log.info("[*] ${chosen.protocol.displayName} is not getting through, trying ${current.protocol.displayName}")
                }
                val outcome = attemptOnce(current, host)
                if (outcome.refresh) {
                    log.info("[*] ${outcome.reason}")
                    rotations = 0
                    continue
                }
                carryOver = null
                host.onTunnelDown()
                if (outcome.rotate) {
                    log.warn("[-] ${outcome.reason}")
                    continue
                }

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

    /** Why an attempt ended, whether it ever came up, and for how long. */
    private data class Outcome(
        val reason: String,
        val connectedForMs: Long = 0,
        val connected: Boolean = false,
        /** The edge worked but was too slow; move to another at once, no backoff. */
        val rotate: Boolean = false,
        /** The session slowed down; a fresh tunnel replaces it under the same capture. */
        val refresh: Boolean = false,
        /** An audition's measured speed; the device was never handed over. */
        val measuredKbps: Long? = null,
    )

    /** [settings] with the obfuscation this network has carried fastest, when tuning is on. */
    private fun tuned(settings: WarpSettings): WarpSettings {
        if (!settings.autoTune || settings.protocol.noizeProfiles.isEmpty() || settings.protocol == Protocol.Auto) return settings
        val recipe = tuner.recipeFor(network, settings.protocol, settings.effectiveNoize)
        if (recipe != Recipe(settings.effectiveNoize)) log.info("[*] tuning: ${settings.protocol.displayName} with ${recipe.id} on this network")
        return settings.copy(noize = recipe.noize, masqueMtu = recipe.masqueMtu)
    }

    /** What Automatic expects of [protocol] here: its measured best, or a prior. */
    private fun expectedKbps(protocol: Protocol): Long =
        tuner.speedOf(network, protocol) ?: if (protocol.warmsUp) UNMEASURED_WARM_KBPS else UNMEASURED_KBPS

    private suspend fun attemptOnce(chosen: WarpSettings, host: TunnelHost): Outcome {
        if (chosen.protocol == Protocol.Auto) return attemptAuto(chosen, host)
        val requested = tuned(chosen)
        val protocol = requested.protocol
        clearGatewayCache()
        core.applyEnvironment(requested)

        // Cancelling the core's job does not always bring its listener down
        // with it. Rather than fight a port that may never be released — and
        // hand the user a tunnel with nothing behind it — this session takes
        // the next free one and tells tun2socks to use that.
        val settings = withFreePort(requested)
        if (protocol.isDirect) return runDirect(settings, host)

        show(ConnectionState.Preparing(protocol))
        log.info(
            "[*] ${protocol.displayName} · scan ${settings.scanMode.wire} · " +
                "noize ${settings.effectiveNoize.wire} · ipv${settings.ipVersion.wire}",
        )

        if (protocol == Protocol.Gool) return runManaged(settings, host)

        val shared = sharedEdgesAsync(settings)
        val identity = when (val opened = identityFor(settings)) {
            is CoreResult.Ok -> opened.value
            is CoreResult.Err -> return Outcome("identity: ${opened.message}")
        }

        if (settings.pinnedPeer.isBlank()) {
            tryKnown(identity, settings, shared.await(), host, viaAuto = false)?.let { return it }
        }

        // A refresh that has to scan takes long enough for the person to notice; say so.
        carryOver = null
        show(ConnectionState.Scanning(protocol, ScanProgress(ScanProgress.Phase.Provisioning)))
        log.info("[*] scanning for a reachable edge")
        val endpoint = when (
            val scanned = core.scan(identity, settings, slowEdges.toSet(), onRunnersUp = { pool(settings, it) }) { progress ->
                show(ConnectionState.Scanning(protocol, progress))
            }
        ) {
            is CoreResult.Ok -> scanned.value
            is CoreResult.Err -> {
                noteReach(protocol, NO_ANSWER)
                return Outcome("scan: ${scanned.message}")
            }
        }
        noteReach(protocol, endpoint.rttMs)
        log.good("[+] edge ${endpoint.socket} answered in ${endpoint.rttMs}ms")

        return runTunnel(identity, endpoint, settings, host)
    }

    /**
     * Picks whatever carries the most, whichever protocol that is.
     *
     * First the edges measured fastest on this operator — by this phone or by
     * others — across every protocol, best download first. Only when none of
     * them comes up does it scan, and then all protocols get the same chance:
     * the edge that answers quickest wins, and its real speed is checked once
     * connected ([judge]) like any other.
     */
    private suspend fun attemptAuto(requested: WarpSettings, host: TunnelHost): Outcome {
        clearGatewayCache()
        val base = withFreePort(requested)
        show(ConnectionState.Preparing(Protocol.Auto))
        val candidates = AUTO_CANDIDATES.associateWith { protocol ->
            tuned(base.copy(protocol = protocol))
        }
        val shared = candidates.mapValues { (_, settings) -> sharedEdgesAsync(settings) }
        val now = System.currentTimeMillis()
        val stalledAt = candidates.mapValues { (_, settings) -> cache.stalledAt(cacheKey(settings)) }
            .filterValues { StallMemory.isRecent(it, now) }
        val stalled = stalledAt.keys
        if (stalled.isNotEmpty()) {
            val names = stalledAt.entries.sortedBy { it.value }.joinToString { it.key.displayName }
            log.info("[*] automatic: $names stalled on this network lately, trying ${if (stalled.size > 1) "them" else "it"} last")
        }

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

        val returning = returningToWarp
        returningToWarp = false
        // A network that drops everything for a moment — a stall, a handover —
        // should not send the session to Psiphon, which is far slower: look
        // once more before leaving Cloudflare. Coming back from Psiphon, only
        // the known edges are tried, so a failed look costs seconds, not a scan.
        val rounds = if (returning) 1 else AUTO_ROUNDS
        for (round in 1..rounds) {
            if (round > 1) {
                log.info("[*] automatic: nothing answered; looking once more before leaving Cloudflare")
                delay(AUTO_RECHECK_MS)
            }
            findWarp(base, host, candidates, shared, identities, stalledAt, scan = !returning)?.let { outcome ->
                if (outcome.connected) warpReturnMs = WARP_RETURN_MS
                return outcome
            }
        }
        if (returning) {
            warpReturnMs = (warpReturnMs * 2).coerceAtMost(MAX_WARP_RETURN_MS)
            log.info("[*] automatic: WARP still does not carry here, back to the fallback")
        }

        // Cloudflare is out of reach on this network: try the tunnels
        // that do not go through it. Warp in warp is left to whoever
        // picks it by hand.
        for (protocol in AUTO_FALLBACK) {
            if (direct[protocol]?.isAvailable != true) continue
            log.info("[*] automatic: nothing answered on MASQUE or WireGuard, trying ${protocol.displayName}")
            val outcome = runDirect(base.copy(protocol = protocol), host, viaAuto = true)
            if (outcome.connected) return outcome
            log.warn("[-] ${outcome.reason}")
        }
        return Outcome("automatic: nothing answered on MASQUE, WireGuard, Psiphon or Tor")
    }

    /**
     * One look for a WARP edge: the known ones first, then, when [scan] is
     * set, a scan of every protocol. Returns the session's outcome once a
     * tunnel was opened, or null when nothing answered.
     */
    private suspend fun findWarp(
        base: WarpSettings,
        host: TunnelHost,
        candidates: Map<Protocol, WarpSettings>,
        shared: Map<Protocol, Deferred<List<SharedEdge>>>,
        identities: Map<Protocol, Identity>,
        stalledAt: Map<Protocol, Long>,
        scan: Boolean,
    ): Outcome? {
        val stalled = stalledAt.keys
        // Until every protocol has been measured on this network, a known
        // edge that is not fast does not end the search: the scan measures
        // the others first, so the choice rests on speeds, not on a guess.
        val unmeasured = identities.keys.filter { it !in stalled && tuner.speedOf(network, it) == null }
        val bestKnown = identities.keys.maxOfOrNull { tuner.speedOf(network, it) ?: 0L } ?: 0L
        val settled = !scan || unmeasured.isEmpty() || bestKnown >= Tuner.GOOD_KBPS
        if (!settled) log.info("[*] automatic: ${unmeasured.joinToString { it.displayName }} not measured on this network yet")
        if (base.pinnedPeer.isBlank() && settled) {
            val known = StallMemory.stalledLast(
                fastestKnown(candidates, shared.mapValues { it.value.await() })
                    .filter { (protocol, _) -> protocol in identities },
                stalledAt,
            ) { it.first }.sortedWith(
                compareBy<Pair<Protocol, SharedEdge>> { (protocol, _) -> protocol in stalled }
                    .thenByDescending { (protocol, _) -> expectedKbps(protocol) },
            )
            if (known.isNotEmpty()) log.info("[*] automatic: ${known.size} edge(s) known to be fast here, fastest first")
            for ((protocol, edge) in known.take(MAX_KNOWN_TRIES)) {
                val settings = candidates.getValue(protocol)
                core.applyEnvironment(settings)
                val outcome = tryRemembered(identities.getValue(protocol), edge.endpoint, settings, host, viaAuto = true)
                if (outcome.connected) return outcome
            }
            if (scan) cache.forgetAuto()
        }
        if (!scan) return null

        log.info("[*] automatic: scanning every protocol")
        carryOver = null
        val found = ConcurrentHashMap<Protocol, Endpoint>()
        show(ConnectionState.Scanning(Protocol.Auto, ScanProgress(ScanProgress.Phase.Probing)))

        suspend fun ping(protocol: Protocol) {
            val identity = identities[protocol] ?: return
            val settings = candidates.getValue(protocol)
            if (protocol.transport == "masque") core.applyEnvironment(settings)
            when (val scanned = core.scan(identity, settings, slowEdges.toSet(), onRunnersUp = { pool(settings, it) }) {}) {
                is CoreResult.Ok -> {
                    found[protocol] = scanned.value
                    noteReach(protocol, scanned.value.rttMs)
                    log.info("[+] ${protocol.displayName} answered in ${scanned.value.rttMs}ms")
                }
                is CoreResult.Err -> {
                    noteReach(protocol, NO_ANSWER)
                    log.warn("[-] ${protocol.displayName}: ${scanned.message}")
                }
            }
        }

        coroutineScope {
            // The two MASQUE carriers cannot scan side by side — the core reads
            // HTTP/2 or HTTP/3 from one process-wide variable — so they take
            // turns, each with a bounded slot, while WireGuard runs beside them.
            val lanes = listOf(
                launch {
                    withTimeoutOrNull(AUTO_LANE_MS) { ping(Protocol.MasqueH2) }
                    withTimeoutOrNull(AUTO_LANE_MS) { ping(Protocol.MasqueH3) }
                },
                launch { ping(Protocol.WireGuard) },
            )
            val all = launch { lanes.joinAll() }
            withTimeoutOrNull(AUTO_PING_MS) {
                while (all.isActive) {
                    // Something answered: give the rest a moment to beat it.
                    if (found.isNotEmpty()) {
                        withTimeoutOrNull(AUTO_GRACE_MS) { all.join() }
                        break
                    }
                    delay(100)
                }
            }
            all.cancel()
            lanes.forEach { it.cancel() }
        }

        // WireGuard carries the most once warm, so it goes first whenever it
        // answered; the rest follow, quickest first, stalled ones last. An
        // answer to the scan does not mean the tunnel gets through (some
        // networks let WireGuard's handshake by and drop its traffic), so a
        // protocol that does not come up hands over to the next one found,
        // and is marked so the next round tries it last.
        val ranked = found.entries.sortedWith(
            compareBy<Map.Entry<Protocol, Endpoint>> { it.key in stalled }
                .thenByDescending { if (it.key in stalled) 0L else expectedKbps(it.key) }
                .thenBy { if (it.key in stalled) stalledAt[it.key] ?: 0L else it.value.rttMs },
        ).map { it.key to it.value }
        if (ranked.isEmpty()) return null
        // What did not answer counts as measured, for a while, so it does not
        // hold every later connection back from the edges known here.
        for (protocol in identities.keys - found.keys) {
            if (tuner.speedOf(network, protocol) == null) {
                val missed = candidates.getValue(protocol)
                tuner.record(network, protocol, Recipe(missed.effectiveNoize, missed.masqueMtu), 0)
            }
        }
        val order = if (ranked.size > 1 && ranked.any { (protocol, _) -> protocol !in stalled && tuner.speedOf(network, protocol) == null }) {
            audition(ranked.filter { (protocol, _) -> protocol !in stalled }, candidates, identities, host)
            ranked.sortedWith(
                compareBy<Pair<Protocol, Endpoint>> { (protocol, _) -> protocol in stalled }
                    .thenByDescending { (protocol, _) -> expectedKbps(protocol) },
            )
        } else {
            ranked
        }

        var last: Outcome? = null
        for ((protocol, endpoint) in order) {
            val chosen = candidates.getValue(protocol)
            log.good("[+] automatic: ${protocol.displayName} chosen (${endpoint.rttMs}ms)")
            core.applyEnvironment(chosen)
            val outcome = runTunnel(identities.getValue(protocol), endpoint, chosen, host, viaAuto = true)
            if (outcome.connected || outcome.rotate || outcome.refresh) return outcome
            log.warn("[-] automatic: ${protocol.displayName} answered but did not carry (${outcome.reason}), trying the next")
            cache.markStalled(cacheKey(chosen), System.currentTimeMillis())
            tuner.record(network, protocol, Recipe(chosen.effectiveNoize, chosen.masqueMtu), 0)
            last = outcome
        }
        return last
    }

    /**
     * Opens each protocol not measured on this network yet and measures its
     * download, without handing the device over, so Automatic picks by speed.
     */
    private suspend fun audition(
        found: List<Pair<Protocol, Endpoint>>,
        candidates: Map<Protocol, WarpSettings>,
        identities: Map<Protocol, Identity>,
        host: TunnelHost,
    ) {
        log.info("[*] automatic: measuring ${found.joinToString { it.first.displayName }} to pick the fastest here")
        for ((protocol, endpoint) in found) {
            if (tuner.speedOf(network, protocol) != null) continue
            val chosen = candidates.getValue(protocol)
            core.applyEnvironment(chosen)
            auditioning = true
            val outcome = try {
                runTunnel(identities.getValue(protocol), endpoint, chosen, host, REMEMBERED_STARTUP_MS, viaAuto = true)
            } finally {
                auditioning = false
            }
            if (outcome.measuredKbps == null) {
                log.warn("[-] automatic: ${protocol.displayName} did not come up (${outcome.reason})")
                tuner.record(network, protocol, Recipe(chosen.effectiveNoize, chosen.masqueMtu), 0)
            }
        }
    }

    /** Measures an auditioned tunnel and keeps the result for this network. */
    private suspend fun measureAudition(settings: WarpSettings, endpoint: Endpoint): Long {
        val warms = settings.protocol.warmsUp
        val measured = ThroughputProbe.measure(
            settings.socksPort,
            if (warms) WARM_JUDGE_BYTES else JUDGE_BYTES,
            if (warms) WARM_JUDGE_WINDOW_MS else JUDGE_WINDOW_MS,
        )
        val kbps = measured?.kbps ?: 0L
        log.info("[*] automatic: ${settings.protocol.displayName} via ${endpoint.socket} carries $kbps KB/s")
        tuner.record(network, settings.protocol, Recipe(settings.effectiveNoize, settings.masqueMtu), kbps)
        if (measured != null && kbps >= SLOW_KBPS) {
            cache.addToPool(cacheKey(settings), listOf(SharedEdge(endpoint.copy(rttMs = measured.firstByteMs), kbps)))
        }
        return kbps
    }

    /**
     * Known edges across protocols, by measured download, fastest first.
     * This phone's own last good edge per protocol counts too, after the
     * measured ones; TCP edges that do not even accept a connection are dropped.
     */
    private suspend fun fastestKnown(
        candidates: Map<Protocol, WarpSettings>,
        shared: Map<Protocol, List<SharedEdge>>,
    ): List<Pair<Protocol, SharedEdge>> {
        val measured = candidates.flatMap { (protocol, settings) ->
            ranked(settings, shared[protocol].orEmpty()).map { protocol to it }
        }.sortedByDescending { (_, edge) -> edge.kbps }
        val remembered = candidates.mapNotNull { (protocol, settings) ->
            cache.recall(cacheKey(settings))?.let { protocol to SharedEdge(it, 0) }
        }
        val known = (measured + remembered)
            .distinctBy { (protocol, edge) -> "${protocol.id}/${edge.endpoint.socket}" }
            .filter { (_, edge) -> edge.endpoint.socket !in slowEdges }
        val open = EdgeReach.reachable(known.filter { (protocol, _) -> protocol.isTcp }.map { it.second.endpoint })
            .map { it.socket }
            .toSet()
        return known.filter { (protocol, edge) -> !protocol.isTcp || edge.endpoint.socket in open }
    }

    /** Starts fetching the shared edges at once; the identity opens meanwhile. */
    private fun sharedEdgesAsync(settings: WarpSettings): Deferred<List<SharedEdge>> = scope.async {
        withTimeoutOrNull(SHARED_FETCH_MS) {
            board.fetch(settings.protocol, settings.ipVersion.wire, network)
        }.orEmpty()
    }

    /**
     * Tries the edges known to carry traffic well on this operator before
     * any scan: this phone's last good one, then the fastest others shared.
     * TCP edges are knocked on together first, so dead ones cost nothing.
     * Returns null when none of them came up.
     */
    private suspend fun tryKnown(
        identity: Identity,
        settings: WarpSettings,
        shared: List<SharedEdge>,
        host: TunnelHost,
        viaAuto: Boolean,
    ): Outcome? {
        val key = cacheKey(settings)
        val pooled = cache.pool(key).count { it.measured }
        val known = (ranked(settings, shared).map { it.endpoint } + listOfNotNull(cache.recall(key)))
            .distinctBy { it.socket }
            .filter { it.socket !in slowEdges }
        if (known.isEmpty()) return null
        log.info("[*] ${known.size} known edge(s): $pooled measured on this phone, ${shared.size} shared by others")
        val reachable = if (settings.protocol.isTcp) EdgeReach.reachable(known) else known
        for (edge in reachable.take(MAX_KNOWN_TRIES)) {
            val outcome = tryRemembered(identity, edge, settings, host, viaAuto)
            if (outcome.connected) return outcome
        }
        cache.forget(key)
        return null
    }

    /**
     * Opens a tunnel straight to an edge that worked recently, skipping the
     * scan, but gives it only [REMEMBERED_STARTUP_MS] to come up. A dead edge
     * costs a few seconds instead of a full tunnel timeout.
     */
    private suspend fun tryRemembered(
        identity: Identity,
        endpoint: Endpoint,
        settings: WarpSettings,
        host: TunnelHost,
        viaAuto: Boolean,
    ): Outcome {
        log.info("[*] trying the last good ${settings.protocol.displayName} edge ${endpoint.socket} first")
        val outcome = runTunnel(identity, endpoint, settings, host, REMEMBERED_STARTUP_MS, viaAuto)
        if (!outcome.connected && !outcome.rotate) {
            log.warn("[-] ${endpoint.socket} did not come back (${outcome.reason}), trying the next")
            cache.dropFromPool(cacheKey(settings), endpoint.socket)
        }
        return outcome
    }

    private suspend fun runTunnel(
        identity: Identity,
        endpoint: Endpoint,
        settings: WarpSettings,
        host: TunnelHost,
        startupMs: Long = TUNNEL_STARTUP_MS,
        viaAuto: Boolean = false,
    ): Outcome {
        show(ConnectionState.Connecting(settings.protocol, endpoint, viaAuto))
        log.info("[*] opening ${settings.protocol.displayName} tunnel to ${endpoint.socket}")

        val job = when (val started = core.startTunnel(identity, endpoint, settings)) {
            is CoreResult.Ok -> started.value
            is CoreResult.Err -> return Outcome("tunnel: ${started.message}")
        }

        try {
            // Wait for the core's proxy to accept connections rather than for
            // a fixed moment: tun2socks started against a listener that is not
            // up yet gives a tunnel that reports success and carries nothing.
            core.awaitProxyReady(job, settings, startupMs)?.let { reason ->
                return Outcome("tunnel: $reason")
            }

            val packet = if (CorePayloads.packetMode(settings)) {
                PacketPath(identity.ipv4, identity.ipv6) { fd -> core.attachTun(job, fd) }
            } else {
                null
            }
            return holdConnected(job, endpoint, settings, host, viaAuto, packet)
        } finally {
            withContext(NonCancellable) {
                core.stopTunnel(job)
                core.awaitProxyClosed(settings, PORT_RELEASE_MS)
            }
        }
    }

    private suspend fun runManaged(settings: WarpSettings, host: TunnelHost): Outcome {
        show(ConnectionState.Connecting(settings.protocol, null))
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

    /**
     * Runs Psiphon or Tor: starts its client on the session's port, hands the
     * device to it, and watches it. Names are resolved at the far end
     * (neither carries UDP), and there is no edge to judge or remember.
     */
    private suspend fun runDirect(settings: WarpSettings, host: TunnelHost, viaAuto: Boolean = false): Outcome {
        val protocol = settings.protocol
        val tunnel = direct[protocol]?.takeIf { it.isAvailable }
            ?: return Outcome("${protocol.displayName} is not in this build")
        show(ConnectionState.Connecting(protocol, null, viaAuto))
        val startup = when (protocol) {
            Protocol.Tor -> TOR_STARTUP_MS
            Protocol.Configs -> CONFIGS_STARTUP_MS
            else -> PSIPHON_STARTUP_MS
        }
        // Closed however this ends, a disconnect while it is still starting
        // included: a client left running would hold the port and the line.
        return try {
            tunnel.open(settings.socksPort, startup)?.let { reason ->
                return Outcome("${tunnel.name}: $reason")
            }
            holdDirect(tunnel, settings, host, viaAuto)
        } finally {
            withContext(NonCancellable) { tunnel.close() }
        }
    }

    private suspend fun holdDirect(
        tunnel: DirectTunnel,
        settings: WarpSettings,
        host: TunnelHost,
        viaAuto: Boolean,
    ): Outcome = coroutineScope {
        val carry = carryOver
        carryOver = null
        val mode = carry?.let { host.onTunnelSwapped(settings, mappedDns = true) }
            ?: host.onTunnelUp(settings, mappedDns = true)
        val since = carry?.sinceMillis ?: System.currentTimeMillis()
        val connected = ConnectionState.Connected(settings.protocol, null, since, mode, viaAuto, settings.socksPort)
        _state.value = connected
        launch { reportExit(connected) }
        if (settings.protocol != Protocol.Configs) launch { warmConfigs(settings.socksPort) }
        log.good("[+] connected through ${tunnel.name}")
        log.info("[*] SOCKS5 listening on 127.0.0.1:${settings.socksPort}")
        watchDirect(tunnel, settings, since, viaAuto)
    }

    /**
     * GitHub, where the public V2Ray configs live, is filtered on many
     * networks: a session that is up fetches them, so the V2Ray protocol
     * has a fresh list to test when it is chosen.
     */
    private suspend fun warmConfigs(socksPort: Int) {
        delay(CONFIGS_WARM_DELAY_MS)
        while (true) {
            runCatching { direct[Protocol.Configs]?.warm(socksPort) }
            delay(CONFIGS_WARM_EVERY_MS)
        }
    }

    /** Watches a running Psiphon or Tor until it stops carrying traffic. */
    private suspend fun watchDirect(tunnel: DirectTunnel, settings: WarpSettings, since: Long, viaAuto: Boolean): Outcome {
        var misses = 0
        var nextProbe = SystemClock.elapsedRealtime() + DIRECT_PROBE_MS
        var nextWarpLook = SystemClock.elapsedRealtime() + warpReturnMs
        while (true) {
            delay(POLL_MS)
            val heldMs = System.currentTimeMillis() - since
            if (tunnel.health == PsiphonRelay.Health.Stopped) {
                return Outcome("${tunnel.name} stopped", connectedForMs = heldMs, connected = true)
            }
            // Psiphon and Tor are the fallback, not the goal: once a known
            // WARP edge answers again, Automatic goes back to it.
            if (viaAuto && SystemClock.elapsedRealtime() >= nextWarpLook) {
                nextWarpLook = SystemClock.elapsedRealtime() + warpReturnMs
                if (warpAnswers(settings)) {
                    returningToWarp = true
                    // Swapped under the same capture, like a refresh: apps keep
                    // the VPN, and if WARP does not come up the fallback does.
                    carryOver = CarryOver(since)
                    return Outcome(
                        "a WARP edge answers again; leaving ${tunnel.name} for it",
                        connectedForMs = heldMs,
                        connected = true,
                        refresh = true,
                    )
                }
            }
            if (SystemClock.elapsedRealtime() < nextProbe) continue
            nextProbe = SystemClock.elapsedRealtime() + DIRECT_PROBE_MS
            // Traffic still arriving means it is alive; a test connection
            // under load could only time out and blame it.
            if (traffic.moved() || core.carries(settings.socksPort)) {
                misses = 0
                continue
            }
            misses++
            log.warn("[-] ${tunnel.name} did not carry a test connection ($misses/$DIRECT_MISSES)")
            if (misses >= DIRECT_MISSES) {
                return Outcome(
                    "${tunnel.name} stopped carrying traffic",
                    connectedForMs = heldMs,
                    connected = true,
                )
            }
        }
    }

    /**
     * Whether a known MASQUE edge accepts a connection again. The app's own
     * sockets bypass the VPN, so this knocks on the edge over the bare line.
     */
    private suspend fun warpAnswers(settings: WarpSettings): Boolean {
        val now = System.currentTimeMillis()
        val edges = AUTO_CANDIDATES.filter { it.isTcp }.flatMap { protocol ->
            val candidate = settings.copy(protocol = protocol)
            if (StallMemory.isRecent(cache.stalledAt(cacheKey(candidate)), now)) return@flatMap emptyList()
            ranked(candidate, emptyList()).map { it.endpoint } + listOfNotNull(cache.recall(cacheKey(candidate)))
        }.distinctBy { it.socket }.filter { it.socket !in slowEdges }.take(WARP_LOOK_EDGES)
        if (edges.isEmpty()) return false
        return EdgeReach.reachable(edges).isNotEmpty()
    }

    /** Marks the session up, captures traffic, and watches the job until it ends. */
    private suspend fun holdConnected(
        job: JobHandle,
        endpoint: Endpoint?,
        settings: WarpSettings,
        host: TunnelHost,
        viaAuto: Boolean = false,
        packet: PacketPath? = null,
    ): Outcome = coroutineScope {
        // Speed is checked before the device is handed over, so a slow edge
        // is swapped while the button still says connecting — the person
        // never sees a connection drop and come back.
        if (endpoint != null && auditioning) {
            val kbps = measureAudition(settings, endpoint)
            return@coroutineScope Outcome("measured", measuredKbps = kbps)
        }
        if (endpoint != null) {
            judge(settings, endpoint, viaAuto)?.let { reason ->
                return@coroutineScope Outcome(reason, rotate = true)
            }
            // It carried real data, so it is worth coming back to even if
            // Psiphon does not come up behind it.
            remember(settings, endpoint, viaAuto)
        }
        val relayPort = when (val relay = startRelay(settings, endpoint, viaAuto)) {
            is RelayStart.Off -> null
            is RelayStart.Up -> relay.port
            is RelayStart.Failed -> return@coroutineScope Outcome(relay.reason, connected = true)
        }
        try {
            val carried = if (relayPort != null) settings.copy(socksPort = relayPort) else settings
            val carry = carryOver
            carryOver = null
            val direct = packet.takeIf { relayPort == null }
            val mode = carry?.let { host.onTunnelSwapped(carried, mappedDns = relayPort != null, direct) }
                ?: host.onTunnelUp(carried, mappedDns = relayPort != null, direct)
            val since = carry?.sinceMillis ?: System.currentTimeMillis()
            val connected = ConnectionState.Connected(
                settings.protocol, endpoint, since, mode, viaAuto, carried.socksPort, relayed = relayPort != null,
            )
            _state.value = connected
            launch { reportExit(connected) }
            launch { warmConfigs(carried.socksPort) }
            if (endpoint != null && settings.pinnedPeer.isBlank() && scout != null) {
                launch {
                    scout.watch(
                        settings, endpoint, network,
                        avoid = { slowEdges.toSet() },
                        pool = { tested -> cache.pool(cacheKey(tested)) },
                        onMeasured = { tested, edge ->
                            val key = cacheKey(tested)
                            if (edge.kbps >= SLOW_KBPS) {
                                cache.addToPool(key, listOf(edge))
                                log.good(
                                    "[+] scout: ${tested.protocol.displayName} ${edge.endpoint.socket} joins the pool " +
                                        "(${edge.kbps} KB/s, ${cache.pool(key).count { it.measured }} measured)",
                                )
                            } else {
                                cache.dropFromPool(key, edge.endpoint.socket)
                            }
                        },
                        onFound = { tested, found -> pool(tested, found) },
                    )
                }
            }

            log.good("[+] connected${endpoint?.let { " via ${it.socket}" } ?: ""}")
            log.info("[*] SOCKS5 listening on 127.0.0.1:${carried.socksPort}")
            if (settings.httpProxyEnabled) log.info("[*] HTTP CONNECT on 127.0.0.1:${settings.httpProxyPort}")
            if (mode == TunMode.ProxyOnly) {
                log.warn("[-] no tun bridge packaged: device traffic is not captured, point apps at the proxy")
            }

            val health = SessionHealth(
                tunnelPort = settings.socksPort,
                relayPort = relayPort,
                relay = relay,
                restartRelay = { country ->
                    relay?.start(settings.socksPort, relayPort ?: 0, country, RELAY_STARTUP_MS)
                        ?: "this build has no psiphon client"
                },
                carries = core::carries,
                moving = traffic::moved,
                onUnlockDown = {
                    _state.value = ConnectionState.Connecting(settings.protocol, endpoint, viaAuto, relaying = true)
                },
                onUnlockBack = {
                    // The same session as before, uptime and all; the exit may have moved.
                    _state.value = connected
                    launch { reportExit(connected) }
                },
                log = log,
            )
            // Only a measured edge on the tunnel's own exit can be judged; an
            // unlocked session would have to bring Psiphon up again as well.
            val guard = if (endpoint != null && relayPort == null && baseline > 0 && settings.pinnedPeer.isBlank()) {
                SpeedGuard(
                    baseline,
                    now = SystemClock::elapsedRealtime,
                    graceMs = if (settings.protocol.warmsUp) SpeedGuard.WARMUP_GRACE_MS else SpeedGuard.GRACE_MS,
                )
            } else {
                null
            }
            var polls = 0L
            while (true) {
                delay(POLL_MS)
                val polled = core.pollJob(job)
                if (guard != null && polled !is JobState.Done) {
                    traffic.rxBytes()?.let(guard::feed)
                    // A line a minute while in use, so a copied log shows how
                    // the session ran when someone says it stalled.
                    if (++polls % SPEED_LOG_POLLS == 0L) {
                        guard.averageKbps()?.takeIf { it >= SpeedGuard.IN_USE_KBPS }?.let { average ->
                            log.info("[*] session: $average KB/s on average, bursts up to ${guard.bestBurstKbps()} KB/s")
                        }
                    }
                    slowedDown(guard, settings, endpoint)?.let { reason ->
                        carryOver = CarryOver(since)
                        coroutineContext.cancelChildren()
                        return@coroutineScope Outcome(
                            reason,
                            connectedForMs = System.currentTimeMillis() - since,
                            connected = true,
                            refresh = true,
                        )
                    }
                }
                val unhealthy = if (polled is JobState.Done) null else health.check()
                if (polled is JobState.Done || unhealthy != null) {
                    val reason = when {
                        polled is JobState.Done ->
                            polled.detail.takeIf { it.isNotEmpty() && it != "closed" } ?: "the core closed the tunnel"
                        else -> unhealthy ?: "the session stopped"
                    }
                    val heldMs = System.currentTimeMillis() - since
                    // An edge that drops a session within seconds is not one to
                    // hurry back to.
                    if (endpoint != null && heldMs < STABLE_MS && polled is JobState.Done) {
                        forget(settings, viaAuto)
                        cache.dropFromPool(cacheKey(settings), endpoint.socket)
                    }
                    coroutineContext.cancelChildren()
                    return@coroutineScope Outcome(reason, connectedForMs = heldMs, connected = true)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            Outcome("unreachable")
        } finally {
            if (relayPort != null) withContext(NonCancellable) { relay?.stop() }
        }
    }

    /**
     * Measures what the edge really delivers, shares it, and asks for another
     * edge when it is too slow for video. Returns the reason to move on, or
     * null to stay. Only [MAX_ROTATIONS] moves per session, so a network that
     * is slow everywhere still ends up connected.
     */
    private suspend fun judge(settings: WarpSettings, endpoint: Endpoint, viaAuto: Boolean): String? {
        baseline = 0
        // WireGuard ramps up over its first seconds, and in packet mode this
        // probe takes the core's slower SOCKS path rather than the device's,
        // so it gets a longer look and a lower bar before it is swapped out.
        val warms = settings.protocol.warmsUp
        val measured = ThroughputProbe.measure(
            settings.socksPort,
            if (warms) WARM_JUDGE_BYTES else JUDGE_BYTES,
            if (warms) WARM_JUDGE_WINDOW_MS else JUDGE_WINDOW_MS,
        ) ?: return null
        val kbps = measured.kbps
        log.info("[*] ${endpoint.socket} delivers $kbps KB/s, first byte in ${measured.firstByteMs}ms")
        val tested = endpoint.copy(rttMs = measured.firstByteMs)
        // Through the tunnel: the board is usually blocked on the open network.
        // Fetching here also keeps this operator's list on the phone for next time.
        // In the background, so the connection does not wait for it.
        val shared = network
        val key = cacheKey(settings)
        if (kbps >= SLOW_KBPS) cache.addToPool(key, listOf(SharedEdge(tested, kbps)))
        scope.launch {
            val up = ThroughputProbe.upload(settings.socksPort, UPLOAD_BYTES) ?: 0
            if (up > 0) log.info("[*] ${endpoint.socket} uploads $up KB/s")
            val edge = SharedEdge(tested, kbps, up)
            if (kbps >= SLOW_KBPS) cache.addToPool(key, listOf(edge))
            board.report(settings.protocol, settings.ipVersion.wire, shared, edge, settings.socksPort)
            board.fetch(settings.protocol, settings.ipVersion.wire, shared, settings.socksPort)
        }
        baseline = kbps
        tuner.record(network, settings.protocol, Recipe(settings.effectiveNoize, settings.masqueMtu), kbps)
        val bar = if (warms) WARM_SLOW_KBPS else SLOW_KBPS
        if (kbps >= bar || rotations >= MAX_ROTATIONS || settings.pinnedPeer.isNotBlank()) return null
        rotations++
        slowEdges += endpoint.socket
        forget(settings, viaAuto)
        cache.dropFromPool(key, endpoint.socket)
        return "${endpoint.socket} carries only $kbps KB/s, moving to a faster edge"
    }

    /**
     * Asks the guard whether the session looks slow and, if so, confirms it
     * with a short download. The tunnel's own receive counter is read across
     * the download, so the person's traffic running beside it counts towards
     * the speed rather than against it. Returns why to refresh, or null.
     */
    private suspend fun slowedDown(guard: SpeedGuard, settings: WarpSettings, endpoint: Endpoint?): String? {
        if (!guard.suspicious()) return null
        val now = SystemClock.elapsedRealtime()
        while (refreshes.isNotEmpty() && now - refreshes.first() > REFRESH_WINDOW_MS) refreshes.removeFirst()
        if (refreshes.size >= MAX_REFRESHES) return null
        val before = traffic.rxBytes() ?: return null
        val started = SystemClock.elapsedRealtime()
        ThroughputProbe.measure(settings.socksPort, GUARD_BYTES, GUARD_WINDOW_MS)
        val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1)
        val after = traffic.rxBytes() ?: return null
        val kbps = (after - before) * 1000 / elapsed / 1024
        if (!guard.confirms(kbps)) {
            log.info("[*] speed check: $kbps KB/s, still fine")
            return null
        }
        refreshes.addLast(SystemClock.elapsedRealtime())
        // A WireGuard slowdown is refreshed in place, never held against the
        // protocol: automatic would otherwise leave the one that works best.
        if (kbps < StallMemory.STALL_KBPS && !settings.protocol.warmsUp) {
            cache.markStalled(cacheKey(settings), System.currentTimeMillis())
            // Its connect-time speed was not what it delivers under video;
            // left in the pool, that figure would put it back on top.
            endpoint?.let {
                cache.dropFromPool(cacheKey(settings), it.socket)
                slowEdges += it.socket
            }
            log.warn("[-] ${settings.protocol.displayName} stalled on this network; automatic prefers another protocol here for a while")
        }
        return "the session slowed to $kbps KB/s (it started at $baseline KB/s); refreshing the tunnel"
    }

    private fun cacheKey(settings: WarpSettings): String =
        "$network.${PrefsEndpointCache.keyOf(settings.protocol, settings.ipVersion.wire)}"

    /** Keeps a scan's runners-up as candidates for the scout to measure. */
    private fun pool(settings: WarpSettings, found: List<Endpoint>) {
        if (settings.pinnedPeer.isNotBlank() || found.isEmpty()) return
        val key = cacheKey(settings)
        cache.addToPool(key, found.map { SharedEdge(it, 0) })
        log.info("[*] ${found.size} more ${settings.protocol.displayName} edge(s) kept for later (${cache.pool(key).size} in the pool)")
    }

    /**
     * Every edge known to carry video for [settings]: this phone's own
     * measurements and the shared board, fastest download first. An edge
     * measured here, on this operator, is worth more than one measured
     * anywhere, so the board's figures are discounted by [BOARD_HANDICAP].
     */
    private fun ranked(settings: WarpSettings, shared: List<SharedEdge>): List<SharedEdge> {
        val own = cache.pool(cacheKey(settings)).filter { it.measured && it.edge.kbps >= SLOW_KBPS }.map { it.edge }
        val ownSockets = own.map { it.endpoint.socket }.toSet()
        val board = shared.filter { it.kbps >= SLOW_KBPS && it.endpoint.socket !in ownSockets }
        return (own.map { it to it.kbps } + board.map { it to it.kbps * 100 / BOARD_HANDICAP })
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private sealed interface RelayStart {
        data object Off : RelayStart
        data class Up(val port: Int) : RelayStart
        data class Failed(val reason: String) : RelayStart
    }

    /**
     * Starts Psiphon behind the tunnel when region unlock is on. Fails
     * closed: with region unlock on, a Psiphon that does not come up ends the
     * attempt rather than letting traffic leave from Cloudflare's exit, which
     * sites see as Iran.
     */
    private suspend fun startRelay(settings: WarpSettings, endpoint: Endpoint?, viaAuto: Boolean): RelayStart {
        if (!settings.regionUnlock) return RelayStart.Off
        val relay = relay ?: return RelayStart.Failed("region unlock is on, but this build has no psiphon client")
        if (!relay.isAvailable) return RelayStart.Failed("region unlock is on, but this build has no psiphon client")
        show(ConnectionState.Connecting(settings.protocol, endpoint, viaAuto, relaying = true))
        val port = freePortFrom(settings.socksPort + RELAY_PORT_OFFSET)
        val failure = relay.start(settings.socksPort, port, settings.exitCountry, RELAY_STARTUP_MS)
            ?: return RelayStart.Up(port)
        relay.stop()
        return RelayStart.Failed("region unlock did not come up: $failure")
    }

    /** Looks up the exit country and adds it to the state, if the session is still the same one. */
    private suspend fun reportExit(connected: ConnectionState.Connected) {
        val country = ExitLocator.country(connected.socksPort) ?: return
        log.info("[*] exit country: $country${if (connected.relayed) " (region unlock)" else ""}")
        val current = _state.value
        if (current is ConnectionState.Connected && current.sinceMillis == connected.sinceMillis) {
            _state.value = current.copy(exitCountry = country)
        }
    }

    private fun freePortFrom(start: Int): Int {
        val loopback = InetAddress.getByName("127.0.0.1")
        return (start..start + 40).firstOrNull { port ->
            runCatching { ServerSocket(port, 1, loopback).close() }.isSuccess
        } ?: start
    }

    private fun remember(settings: WarpSettings, endpoint: Endpoint, viaAuto: Boolean) {
        if (settings.pinnedPeer.isNotBlank() || settings.protocol == Protocol.Gool) return
        cache.remember(cacheKey(settings), endpoint)
        if (viaAuto) cache.rememberAuto(settings.protocol, endpoint)
    }

    private fun forget(settings: WarpSettings, viaAuto: Boolean) {
        cache.forget(cacheKey(settings))
        if (viaAuto) cache.forgetAuto()
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
        Protocol.Gool -> listOf(Protocol.MasqueH3, Protocol.WireGuard, Protocol.MasqueH2, Protocol.Psiphon)
        Protocol.MasqueH3 -> listOf(Protocol.WireGuard, Protocol.MasqueH2, Protocol.Gool, Protocol.Psiphon)
        Protocol.MasqueH2 -> listOf(Protocol.MasqueH3, Protocol.WireGuard, Protocol.Gool, Protocol.Psiphon)
        Protocol.WireGuard -> listOf(Protocol.MasqueH3, Protocol.MasqueH2, Protocol.Gool, Protocol.Psiphon)
        // When the network outside Cloudflare will not answer, Cloudflare may.
        Protocol.Psiphon -> listOf(Protocol.MasqueH2, Protocol.MasqueH3, Protocol.WireGuard, Protocol.Tor)
        Protocol.Tor -> listOf(Protocol.Psiphon, Protocol.MasqueH2, Protocol.MasqueH3, Protocol.WireGuard)
        Protocol.Configs -> listOf(Protocol.MasqueH2, Protocol.MasqueH3, Protocol.WireGuard, Protocol.Psiphon)
    }

    /** Picks a local port nothing is sitting on, starting from the chosen one. */
    private fun withFreePort(settings: WarpSettings): WarpSettings {
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

    private suspend fun identityFor(settings: WarpSettings): CoreResult<Identity> {
        val transport = settings.protocol.transport
        identities[transport]?.let {
            _identity.value = it
            return CoreResult.Ok(it)
        }
        log.info("[*] opening $transport identity")
        val opened = core.openIdentity(File(identityDir, "warp.toml").absolutePath, transport)
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

        /** The region-unlock exit listens this far above the tunnel's own port. */
        const val RELAY_PORT_OFFSET = 10

        /** Psiphon's first start downloads its server list; later ones take ~20s. */
        const val RELAY_STARTUP_MS = 75_000L

        /** How long a remembered edge gets before the engine scans instead. */
        const val REMEMBERED_STARTUP_MS = 7_000L

        /** Known edges tried before scanning; each costs up to [REMEMBERED_STARTUP_MS]. */
        const val MAX_KNOWN_TRIES = 3

        /**
         * The shared list is a head start, never worth waiting long for. Above
         * the board's own direct timeouts, so a blocked board still falls back
         * to the list kept from the last connection.
         */
        const val SHARED_FETCH_MS = 4_000L

        /** The speed check before connecting: short, at most ~2 MB. */
        const val JUDGE_BYTES = 2_000_000
        const val JUDGE_WINDOW_MS = 2_500L

        /** The session edge's upload, measured once it is up. */
        const val UPLOAD_BYTES = 384 * 1024

        /** Below this (about 2.4 Mbit/s) Instagram video stalls; such an edge is swapped. */
        const val SLOW_KBPS = 300L

        /** WireGuard's first look: longer, and only a dead edge is swapped. */
        const val WARM_JUDGE_BYTES = 4_000_000
        const val WARM_JUDGE_WINDOW_MS = 6_000L
        const val WARM_SLOW_KBPS = 120L

        /** A shared edge's score is worth this much (percent) of one measured on this phone. */
        const val BOARD_HANDICAP = 130L

        /** The confirming download: small, and short even on a crawling line. */
        const val GUARD_BYTES = 1_000_000
        const val GUARD_WINDOW_MS = 2_000L

        /** Refreshes allowed per [REFRESH_WINDOW_MS]; past that the line is just slow today. */
        const val MAX_REFRESHES = 4
        const val REFRESH_WINDOW_MS = 60 * 60_000L

        /** Slow edges dropped per session before the engine settles for what it has. */
        const val MAX_ROTATIONS = 2
        const val POLL_MS = 1_000L
        const val SPEED_LOG_POLLS = 60L
        const val STABLE_MS = 30_000L
        const val MANAGED_STARTUP_MS = 90_000L

        /**
         * A brief grace period for a finished session to release its port.
         * Short on purpose: the next attempt just moves to another port.
         */
        const val PORT_RELEASE_MS = 1_500L

        /** What automatic tries; none is preferred, speed decides. */
        /** [reach] for a protocol that did not answer. */
        const val NO_ANSWER = -1L
        val AUTO_CANDIDATES = listOf(Protocol.MasqueH2, Protocol.MasqueH3, Protocol.WireGuard)

        /** What Automatic assumes of a protocol not yet measured on a network; WireGuard leads until measured. */
        const val UNMEASURED_WARM_KBPS = 1_000L
        const val UNMEASURED_KBPS = 800L

        /** Tried in this order when no Cloudflare edge answers at all. */
        val AUTO_FALLBACK = listOf(Protocol.Psiphon, Protocol.Tor)

        /** Psiphon usually connects in 10–30 s; Tor through Snowflake can take two minutes. */
        const val PSIPHON_STARTUP_MS = 60_000L
        const val TOR_STARTUP_MS = 150_000L
        /** The first connection on a network fetches and tests the public configs. */
        const val CONFIGS_STARTUP_MS = 150_000L
        /** A session settles before the config lists are fetched through it. */
        const val CONFIGS_WARM_DELAY_MS = 30_000L
        /** How often a long session looks again; the pool itself decides whether it is old. */
        const val CONFIGS_WARM_EVERY_MS = 30 * 60_000L
        const val DIRECT_PROBE_MS = 30_000L
        const val DIRECT_MISSES = 3

        /** The whole automatic ping round. */
        const val AUTO_PING_MS = 20_000L

        /** Looks for WARP before Automatic falls back to Psiphon or Tor. */
        const val AUTO_ROUNDS = 2
        const val AUTO_RECHECK_MS = 3_000L

        /** On Psiphon or Tor through Automatic, how often to look for WARP again, and the most it backs off to. */
        const val WARP_RETURN_MS = 3 * 60_000L
        const val MAX_WARP_RETURN_MS = 30 * 60_000L
        const val WARP_LOOK_EDGES = 4

        /** Each MASQUE carrier's scan slot; they cannot scan at the same time. */
        const val AUTO_LANE_MS = 10_000L

        /** How long the others may still beat the first edge that answered. */
        const val AUTO_GRACE_MS = 2_500L

    }
}
