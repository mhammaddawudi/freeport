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
 * touched, and only runs while their traffic is quiet: the line is not
 * shared with a download of theirs.
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
     * proxy. [onGood] receives every edge, on any protocol, fast enough for
     * video ([MIN_KBPS]), whatever the current edge does.
     */
    suspend fun watch(
        session: WarpSettings,
        current: Endpoint,
        network: String,
        avoid: () -> Set<String>,
        onGood: (WarpSettings, Endpoint, Long) -> Unit,
    ) {
        if (meter == null) return
        delay(FIRST_ROUND_MS)
        val tried = mutableSetOf("${session.protocol.id}/${current.socket}")
        repeat(ROUNDS) { round ->
            // Every protocol, every round. Even rounds re-check the best the
            // others shared; odd ones scan the line for edges nobody has yet.
            val fresh = round % 2 == 1
            for (protocol in PROTOCOLS) {
                if (!awaitQuiet()) return@repeat
                val settings = session.copy(protocol = protocol)
                try {
                    // MASQUE reads HTTP/2 or HTTP/3 from the environment when a
                    // tunnel or scan starts; the person's tunnel already read it.
                    if (protocol.transport == "masque") core.applyEnvironment(settings)
                    test(settings, session, network, tried, avoid(), fresh, onGood)
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
        onGood: (WarpSettings, Endpoint, Long) -> Unit,
    ) {
        val protocol = settings.protocol
        val identity = probeIdentity(settings) ?: return
        val skip = tried.filter { it.startsWith("${protocol.id}/") }.map { it.substringAfter('/') }.toSet() + avoid
        val candidate = nextCandidate(identity, settings, session, network, skip, fresh) ?: return
        tried += "${protocol.id}/${candidate.socket}"
        val measured = trial(identity, candidate, settings)
        if (measured == null) {
            log.info("[*] scout: ${protocol.displayName} ${candidate.socket} did not carry anything")
            return
        }
        val kbps = measured.kbps
        log.info(
            "[*] scout: ${protocol.displayName} ${candidate.socket} · down $kbps KB/s · " +
                "up ${measured.upKbps} KB/s · first byte ${measured.firstByteMs}ms",
        )
        val tested = candidate.copy(rttMs = measured.firstByteMs)
        board.report(protocol, settings.ipVersion.wire, network, SharedEdge(tested, kbps, measured.upKbps), session.socksPort)
        if (kbps >= MIN_KBPS) onGood(settings, tested, kbps)
    }

    /**
     * A shared edge not tried this session, or with [fresh] a scan of the
     * line that skips everything already known, so new edges keep joining
     * the pool. Either falls back to the other when it finds nothing.
     */
    private suspend fun nextCandidate(
        identity: Identity,
        settings: WarpSettings,
        session: WarpSettings,
        network: String,
        skip: Set<String>,
        fresh: Boolean,
    ): Endpoint? {
        val shared = board.fetch(settings.protocol, settings.ipVersion.wire, network, session.socksPort)
        if (fresh) {
            val known = skip + shared.map { it.endpoint.socket }
            core.scan(identity, settings, known) {}.valueOrNull()?.takeIf { it.socket !in known }?.let { return it }
        }
        shared
            .sortedBy { it.reelMs }
            .firstOrNull { it.endpoint.socket !in skip }
            ?.let { return it.endpoint }
        return core.scan(identity, settings, skip) {}.valueOrNull()?.takeIf { it.socket !in skip }
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

    /** Waits for a quiet stretch; false when none came within [QUIET_WAIT_MS]. */
    private suspend fun awaitQuiet(): Boolean {
        val meter = meter ?: return false
        val until = System.currentTimeMillis() + QUIET_WAIT_MS
        while (System.currentTimeMillis() < until) {
            val before = meter.totals() ?: return false
            delay(QUIET_SAMPLE_MS)
            val after = meter.totals() ?: return false
            val bytes = (after.rxBytes - before.rxBytes) + (after.txBytes - before.txBytes)
            if (bytes * 1000 / QUIET_SAMPLE_MS < QUIET_BYTES_PER_S) return true
            delay(QUIET_RETRY_MS)
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

        const val FIRST_ROUND_MS = 2 * 60_000L
        const val ROUND_INTERVAL_MS = 10 * 60_000L

        /** Each round tests one edge per protocol: up to 12 edges a session. */
        const val ROUNDS = 4

        /** Under ~40 KB/s on the tunnel the person is reading, not streaming. */
        const val QUIET_BYTES_PER_S = 40 * 1024L
        const val QUIET_SAMPLE_MS = 5_000L
        const val QUIET_RETRY_MS = 30_000L
        const val QUIET_WAIT_MS = 10 * 60_000L

        /** Trial ports sit above the region-unlock exit's. */
        const val PORT_OFFSET = 20
        const val STARTUP_MS = 8_000L
        const val CLOSE_MS = 1_500L

        /** At most ~1.4 MB per edge: 1 MB down, a little under 0.4 MB up. */
        const val TRIAL_BYTES = 1_000_000
        const val TRIAL_WINDOW_MS = 2_500L
        const val TRIAL_UP_BYTES = 384 * 1024

        /** Enough for Instagram video; anything at or above it joins the pool. */
        const val MIN_KBPS = 300L
    }
}
