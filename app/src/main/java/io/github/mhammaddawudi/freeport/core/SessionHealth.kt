package io.github.mhammaddawudi.freeport.core

import kotlinx.coroutines.delay

/** What [SessionHealth] needs to know about the region-unlock exit. */
internal interface UnlockRelay {
    val isStarting: Boolean
    val health: PsiphonRelay.Health
    /** The country it was asked for; empty for its own pick. */
    val country: String
}

/**
 * Keeps a held session honest. A tunnel or a Psiphon exit can stop carrying
 * traffic while its process looks perfectly alive, so both are checked by
 * opening real connections through them.
 *
 * Region unlock fails closed by construction: tun2socks points at Psiphon's
 * port, so while Psiphon is down nothing is carried at all, rather than
 * leaving from Cloudflare's exit, which sites see as Iran. This brings it back.
 */
internal class SessionHealth(
    private val tunnelPort: Int,
    private val relayPort: Int?,
    private val relay: UnlockRelay?,
    private val restartRelay: suspend (country: String) -> String?,
    private val carries: suspend (port: Int) -> Boolean,
    private val onUnlockDown: () -> Unit,
    private val onUnlockBack: () -> Unit,
    private val log: CoreLog = CoreLog,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var nextProbeAt = now() + PROBE_EVERY_MS
    private var tunnelMisses = 0
    private var relayMisses = 0

    /** One round of checks. Returns why the session has to end, if it does. */
    suspend fun check(): String? {
        if (relayPort != null) {
            val relay = relay ?: return null
            if (relay.isStarting) return null
            if (relay.health != PsiphonRelay.Health.Up) return recoverUnlock(relayPort, mayRegain = true)
        }
        if (now() < nextProbeAt) return null
        nextProbeAt = now() + PROBE_EVERY_MS

        if (!carries(tunnelPort)) {
            tunnelMisses++
            nextProbeAt = now() + RETRY_AFTER_MISS_MS
            log.warn("[-] the tunnel did not carry a test connection ($tunnelMisses/$TUNNEL_MISSES)")
            // Psiphon rides the tunnel; probing it now would only blame it.
            return if (tunnelMisses >= TUNNEL_MISSES) "the tunnel stopped carrying traffic" else null
        }
        tunnelMisses = 0

        val port = relayPort ?: return null
        if (carries(port)) {
            relayMisses = 0
            return null
        }
        relayMisses++
        nextProbeAt = now() + RETRY_AFTER_MISS_MS
        log.warn("[-] psiphon did not carry a test connection ($relayMisses/$RELAY_MISSES)")
        if (relayMisses < RELAY_MISSES) return null
        relayMisses = 0
        // Psiphon still says it is up, so waiting for it to notice is no use.
        return recoverUnlock(port, mayRegain = false)
    }

    /**
     * Brings Psiphon back. When it reported the loss itself ([mayRegain]) it
     * gets a moment to find another server first; otherwise it is restarted
     * at once. Returns a reason only when it cannot be brought back.
     */
    private suspend fun recoverUnlock(port: Int, mayRegain: Boolean): String? {
        val relay = relay ?: return "the region-unlock exit stopped"
        onUnlockDown()
        log.warn("[-] region unlock is down; holding traffic until psiphon is back")

        val regainBy = now() + if (mayRegain) RELAY_REGAIN_MS else 0
        while (now() < regainBy) {
            if (relay.health == PsiphonRelay.Health.Up && carries(port)) return back()
            if (relay.health == PsiphonRelay.Health.Stopped) break
            delay(REGAIN_POLL_MS)
        }

        log.info("[*] restarting psiphon")
        val failure = restartRelay(relay.country)
        return if (failure == null) back() else "region unlock could not come back: $failure"
    }

    private fun back(): String? {
        onUnlockBack()
        log.good("[+] region unlock is back")
        return null
    }

    companion object {
        /** How often a connection is opened through the tunnel and through Psiphon. */
        const val PROBE_EVERY_MS = 15_000L

        /** After a miss the next probe comes sooner, so a dead hop is found in seconds. */
        const val RETRY_AFTER_MISS_MS = 5_000L

        /** Misses in a row before the tunnel counts as dead. */
        const val TUNNEL_MISSES = 3

        /** Misses in a row before Psiphon counts as lost though it says it is up. */
        const val RELAY_MISSES = 2

        /** How long Psiphon gets to find another server on its own. */
        const val RELAY_REGAIN_MS = 15_000L
        const val REGAIN_POLL_MS = 500L
    }
}
