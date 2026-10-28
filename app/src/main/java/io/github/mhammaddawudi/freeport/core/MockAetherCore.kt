package io.github.mhammaddawudi.freeport.core

import kotlinx.coroutines.delay
import io.github.mhammaddawudi.freeport.data.AetherSettings
import io.github.mhammaddawudi.freeport.data.IpVersion
import io.github.mhammaddawudi.freeport.data.Protocol
import io.github.mhammaddawudi.freeport.data.ScanMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * A stand-in for the Rust core so the whole app — UI, service, notification,
 * reconnect — can be exercised before `libaether.so` exists.
 *
 * It reports the same kinds of values the real core does (edges in the
 * published MASQUE and WARP ranges, the documented ports, realistic RTTs) and
 * paces itself by scan mode, so the interface behaves the way it will against
 * the real thing. It never opens a socket.
 */
class MockAetherCore(
    private val random: Random = Random.Default,
) : AetherCore {

    private val ids = AtomicLong(1)
    private val tunnels = ConcurrentHashMap<Long, Boolean>()

    override suspend fun applyEnvironment(settings: AetherSettings) = Unit

    override suspend fun version(): String = "1.6.0-mock"

    override suspend fun openIdentity(path: String, transport: String): CoreResult<Identity> {
        delay(350)
        val hex = (1..8).joinToString("") { "%02x".format(random.nextInt(256)) }
        return CoreResult.Ok(
            Identity(
                handle = ids.getAndIncrement(),
                deviceId = "t.$hex",
                ipv4 = "172.16.0.2",
                ipv6 = "2606:4700:110:8a3c::${random.nextInt(0x1000, 0xffff).toString(16)}",
                organization = "",
                assignedEndpoint = if (transport == "masque") "162.159.198.1:443" else "162.159.192.1:2408",
            ),
        )
    }

    override suspend fun scan(
        identity: Identity,
        settings: AetherSettings,
        onProgress: (ScanProgress) -> Unit,
    ): CoreResult<Endpoint> {
        CorePayloads.parsePeer(settings.pinnedPeer)?.let { (host, port) ->
            onProgress(ScanProgress(ScanProgress.Phase.Settled, 1, 1))
            return CoreResult.Ok(Endpoint(host, port, rttMs = 0))
        }

        val (total, stepMs) = when (settings.scanMode) {
            ScanMode.Turbo -> 24 to 35L
            ScanMode.Swift -> 40 to 28L
            ScanMode.Balanced -> 64 to 30L
            ScanMode.Thorough -> 180 to 18L
            ScanMode.Stealth -> 40 to 110L
            ScanMode.Ironclad -> 48 to 70L
        }

        var best: Long? = null
        for (probed in 1..total) {
            delay(stepMs)
            if (random.nextInt(100) < 38) {
                val rtt = random.nextLong(58, 340)
                best = best?.coerceAtMost(rtt) ?: rtt
            }
            onProgress(ScanProgress(ScanProgress.Phase.Probing, probed, total, best))
            if (settings.scanMode == ScanMode.Turbo && best != null) break
        }

        if (settings.scanMode == ScanMode.Ironclad) {
            onProgress(ScanProgress(ScanProgress.Phase.Verifying, total, total, best))
            delay(900)
        }

        val rtt = best ?: return CoreResult.Err("no reachable endpoint answered in time")
        val endpoint = pickEndpoint(settings, rtt)
        onProgress(ScanProgress(ScanProgress.Phase.Settled, total, total, rtt))
        return CoreResult.Ok(endpoint)
    }

    override suspend fun startTunnel(
        identity: Identity,
        endpoint: Endpoint,
        settings: AetherSettings,
    ): CoreResult<JobHandle> {
        // Gool opens two sessions, and the second handshake is the slow one.
        delay(if (settings.protocol == Protocol.Gool) 1200 else 600)
        val id = ids.getAndIncrement()
        tunnels[id] = true
        return CoreResult.Ok(JobHandle(id))
    }

    override suspend fun startManaged(
        settings: AetherSettings,
        configDir: String,
    ): CoreResult<JobHandle> {
        delay(400)
        val id = ids.getAndIncrement()
        tunnels[id] = true
        return CoreResult.Ok(JobHandle(id))
    }

    override suspend fun awaitProxyReady(
        job: JobHandle,
        settings: AetherSettings,
        timeoutMs: Long,
    ): String? {
        // Two scans and two handshakes: the outer hop, then the inner one.
        delay(2600)
        return if (tunnels[job.id] == true) null else "the core exited"
    }

    override suspend fun awaitProxyClosed(settings: AetherSettings, timeoutMs: Long): Boolean = true

    /** The mock carries whenever one of its tunnels is up. */
    override suspend fun carries(port: Int): Boolean = tunnels.values.any { it }

    override suspend fun stopTunnel(job: JobHandle) {
        delay(120)
        tunnels.remove(job.id)
    }

    override suspend fun pollJob(job: JobHandle): JobState =
        if (tunnels[job.id] == true) JobState.Running else JobState.Done(true, "stopped")

    override suspend fun closeIdentity(identity: Identity) = Unit

    private fun pickEndpoint(settings: AetherSettings, rtt: Long): Endpoint {
        val v6 = when (settings.ipVersion) {
            IpVersion.V4 -> false
            IpVersion.V6 -> true
            IpVersion.Dual -> random.nextBoolean()
        }
        val masque = settings.protocol.transport == "masque"
        val ip = when {
            masque && v6 -> "2606:4700:102::${random.nextInt(1, 255).toString(16)}"
            masque -> "162.159.198.${random.nextInt(1, 255)}"
            v6 -> "2606:4700:d0::${random.nextInt(1, 255).toString(16)}"
            else -> "162.159.19${random.nextInt(2, 6)}.${random.nextInt(1, 255)}"
        }
        val ports = if (masque) listOf(443, 500, 1701, 4500, 4443, 8443, 8095)
        else listOf(2408, 500, 1701, 4500, 854, 859, 864, 878)
        return Endpoint(ip, ports[random.nextInt(ports.size)], rtt)
    }
}
