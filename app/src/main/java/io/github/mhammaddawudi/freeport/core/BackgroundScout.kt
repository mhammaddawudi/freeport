package io.github.mhammaddawudi.freeport.core

import io.github.mhammaddawudi.freeport.data.WarpSettings
import io.github.mhammaddawudi.freeport.data.Protocol
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Tries other edges in the background while a session is up, so the next
 * connection — this phone's and everyone else's on the same operator —
 * starts from a measured fast edge.
 *
 * Each round tests one edge per protocol — MASQUE H2, MASQUE H3 and
 * WireGuard, whatever the session runs on — by opening a short second tunnel
 * to it, moving a little data each way and closing it. So every protocol on
 * every operator keeps getting fresh measurements from the people using it.
 * The core's sockets belong to this app, which is outside its own VPN, so the trial goes straight over the real line
 * and its speed is the real one, not a figure seen through the running
 * tunnel. It uses its own probe identity, so the person's session is never
 * touched, and only runs once their traffic has been quiet for a while: a
 * pause between two reels is not quiet, and a trial there would take the line
 * from the next one. Scanning the line for new edges, a burst of hundreds of
 * probes, waits for a much longer silence, when the phone is most likely
 * not in use at all.
 */
class BackgroundScout(
    private val core: WarpCore,
    private val board: EdgeBoard,
    private val meter: TrafficMeter?,
    private val probeDir: File,
    private val log: CoreLog = CoreLog,
) {

    private val identities = HashMap<String, Identity>()

    /**
     * Runs a few rounds for the life of the session; cancelled with it.
     * [session] is what the person is connected with; reports go through its
     * proxy. [pool] is what this phone already keeps per protocol;
     * [onMeasured] receives every edge it measured, fast or slow, and
     * [onFound] the runners-up of its scans, to be measured in later rounds.
     */
    suspend fun watch(
        session: WarpSettings,
        current: Endpoint,
        network: String,
        avoid: () -> Set<String>,
        pool: (WarpSettings) -> List<PooledEdge>,
        onMeasured: (WarpSettings, SharedEdge) -> Unit,
        onFound: (WarpSettings, List<Endpoint>) -> Unit,
    ) {
        if (meter == null) return
        delay(FIRST_ROUND_MS)
        val tried = mutableSetOf("${session.protocol.id}/${current.socket}")
        repeat(ROUNDS) { round ->
            // Every protocol, every round. Even rounds re-check the best the
            // others shared; odd ones scan the line for edges nobody has yet.
            val fresh = round % 2 == 1
            for (protocol in PROTOCOLS) {
                if (!awaitQuiet(QUIET_HOLD_MS)) return@repeat
                val settings = session.copy(protocol = protocol)
                try {
                    // MASQUE reads HTTP/2 or HTTP/3 from the environment when a
                    // tunnel or scan starts; the person's tunnel already read it.
                    if (protocol.transport == "masque") core.applyEnvironment(settings)
                    test(settings, session, network, tried, avoid(), fresh, pool(settings), onMeasured, onFound)
                } finally {
                    withContext(NonCancellable) {
                        if (protocol.transport == "masque") core.applyEnvironment(session)
                    }
                }
            }
            delay(ROUND_INTERVAL_MS)
        }
    }

    private suspend fun test(
        settings: WarpSettings,
        session: WarpSettings,
        network: String,
        tried: MutableSet<String>,
        avoid: Set<String>,
        fresh: Boolean,
        pooled: List<PooledEdge>,
        onMeasured: (WarpSettings, SharedEdge) -> Unit,
        onFound: (WarpSettings, List<Endpoint>) -> Unit,
    ) {
        val protocol = settings.protocol
        val identity = probeIdentity(settings) ?: return
        val skip = tried.filter { it.startsWith("${protocol.id}/") }.map { it.substringAfter('/') }.toSet() + avoid
        val candidate = nextCandidate(identity, settings, session, network, skip, fresh, pooled) {
            onFound(settings, it)
        } ?: return
        tried += "${protocol.id}/${candidate.socket}"
        val measured = trial(identity, candidate, settings)
        if (measured == null) {
            log.info("[*] scout: ${protocol.displayName} ${candidate.socket} did not carry anything")
            onMeasured(settings, SharedEdge(candidate, 0))
            return
        }
        val kbps = measured.kbps
        log.info(
            "[*] scout: ${protocol.displayName} ${candidate.socket} · down $kbps KB/s · " +
                "up ${measured.upKbps} KB/s · first byte ${measured.firstByteMs}ms",
        )
        val tested = candidate.copy(rttMs = measured.firstByteMs)
        val edge = SharedEdge(tested, kbps, measured.upKbps)
        board.report(protocol, settings.ipVersion.wire, network, edge, session.socksPort)
        onMeasured(settings, edge)
    }

    /**
     * What to measure next. Fresh rounds grow the pool: first a scan
     * runner-up still waiting to be measured, else a scan of the line that
     * leaves out every edge already known, so it can only find new ones. The
     * other rounds re-check the best known edges, this phone's and the
     * board's. Each falls back to the other when it finds nothing.
     */
    private suspend fun nextCandidate(
        identity: Identity,
        settings: WarpSettings,
        session: WarpSettings,
        network: String,
        skip: Set<String>,
        fresh: Boolean,
        pooled: List<PooledEdge>,
        onFound: (List<Endpoint>) -> Unit,
    ): Endpoint? {
        val shared = board.fetch(settings.protocol, settings.ipVersion.wire, network, session.socksPort)
        val known = skip + shared.map { it.endpoint.socket } + pooled.map { it.socket }
        val waiting = pooled.firstOrNull { !it.measured && it.socket !in skip }?.edge?.endpoint
        val recheck = (pooled.filter { it.measured }.map { it.edge } + shared)
            .sortedByDescending { it.kbps }
            .firstOrNull { it.endpoint.socket !in skip }
            ?.endpoint
        suspend fun discover(): Endpoint? =
            if (!awaitQuiet(SCAN_QUIET_HOLD_MS)) null else core.scan(identity, settings, known, onRunnersUp = { found ->
                found.filter { it.socket !in known }.takeIf { it.isNotEmpty() }?.let(onFound)
            }) {}.valueOrNull()?.takeIf { it.socket !in known }
        return if (fresh) {
            waiting ?: discover() ?: recheck
        } else {
            recheck ?: waiting ?: discover()
        }
    }

    /** Opens the candidate on a spare port, measures it, and always closes it. */
    private suspend fun trial(identity: Identity, edge: Endpoint, settings: WarpSettings): ThroughputProbe.Measurement? {
        val trial = settings.copy(socksPort = freePort(settings.socksPort + PORT_OFFSET), httpProxyEnabled = false)
        val job = core.startTunnel(identity, edge, trial).valueOrNull() ?: return null
        return try {
            if (core.awaitProxyReady(job, trial, STARTUP_MS) != null) return null
            ThroughputProbe.measure(trial.socksPort, TRIAL_BYTES, TRIAL_WINDOW_MS, TRIAL_UP_BYTES)
        } finally {
            withContext(NonCancellable) {
                core.stopTunnel(job)
                core.awaitProxyClosed(trial, CLOSE_MS)
            }
        }
    }

    /**
     * Waits until the person's traffic has stayed under [QUIET_BYTES_PER_S]
     * for [holdMs] without a break; false when that did not happen within
     * [QUIET_WAIT_MS].
     */
    private suspend fun awaitQuiet(holdMs: Long): Boolean {
        val meter = meter ?: return false
        val until = System.currentTimeMillis() + QUIET_WAIT_MS
        var quietSince = System.currentTimeMillis()
        var last = meter.totals() ?: return false
        while (System.currentTimeMillis() < until) {
            delay(QUIET_SAMPLE_MS)
            val now = meter.totals() ?: return false
            val bytes = (now.rxBytes - last.rxBytes) + (now.txBytes - last.txBytes)
            last = now
            if (bytes * 1000 / QUIET_SAMPLE_MS >= QUIET_BYTES_PER_S) {
                quietSince = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - quietSince >= holdMs) {
                return true
            }
        }
        return false
    }

    /**
     * A second identity, kept beside the main one. Two sessions on one
     * identity would fight over the same address at Cloudflare and could
     * drop the person's tunnel.
     */
    private suspend fun probeIdentity(settings: WarpSettings): Identity? {
        val transport = settings.protocol.transport
        identities[transport]?.let { return it }
        probeDir.mkdirs()
        return when (val opened = core.openIdentity(File(probeDir, "warp.toml").absolutePath, transport)) {
            is CoreResult.Ok -> opened.value.also { identities[transport] = it }
            is CoreResult.Err -> {
                log.warn("[-] scout: no probe identity (${opened.message}), skipping background checks")
                null
            }
        }
    }

    private fun freePort(start: Int): Int {
        val loopback = InetAddress.getByName("127.0.0.1")
        return (start..start + 40).firstOrNull { port ->
            runCatching { ServerSocket(port, 1, loopback).close() }.isSuccess
        } ?: start
    }

    private companion object {
        val PROTOCOLS = listOf(Protocol.MasqueH2, Protocol.MasqueH3, Protocol.WireGuard)

        const val FIRST_ROUND_MS = 90_000L
        const val ROUND_INTERVAL_MS = 6 * 60_000L

        /** Each round tests one edge per protocol: up to 24 edges a session, ~29 MB at most. */
        const val ROUNDS = 8

        /** Under ~15 KB/s the person is reading, not watching. */
        const val QUIET_BYTES_PER_S = 15 * 1024L
        const val QUIET_SAMPLE_MS = 2_000L

        /** Longer than any pause between reels. */
        const val QUIET_HOLD_MS = 45_000L

        /** A scan needs the line to itself: two minutes of near silence. */
        const val SCAN_QUIET_HOLD_MS = 120_000L
        const val QUIET_WAIT_MS = 10 * 60_000L

        /** Trial ports sit above the region-unlock exit's. */
        const val PORT_OFFSET = 20
        const val STARTUP_MS = 8_000L
        const val CLOSE_MS = 1_500L

        /** At most ~1.2 MB per edge: 1 MB down, under 0.2 MB up. */
        const val TRIAL_BYTES = 1_000_000
        const val TRIAL_WINDOW_MS = 2_500L
        const val TRIAL_UP_BYTES = 192 * 1024

    }
}
