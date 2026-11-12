package io.github.mhammaddawudi.freeport.core

import android.content.Context
import android.content.SharedPreferences
import io.github.mhammaddawudi.freeport.data.NoizeProfile
import io.github.mhammaddawudi.freeport.data.Protocol

/**
 * Learns, per network, which protocol and per-tunnel settings carry the most.
 *
 * Every session's speed is already measured once it connects; the tuner
 * keeps those numbers by network, protocol and noize profile. When a
 * network has not shown a good speed yet, the next connection tries a
 * profile it has not measured (lighter ones first, since obfuscation costs
 * speed); once one is good, or all are measured, the fastest is used.
 * A profile that failed to connect counts as zero, so it is not tried again
 * until its measurement goes stale.
 */
class Tuner(
    private val store: TuningStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Folds a session's measured speed into what is known. Zero records a failure. */
    fun record(network: String, protocol: Protocol, recipe: Recipe, kbps: Long) {
        val key = key(network, protocol, recipe)
        val known = read(key)
        // One failure halves a speed seen before rather than erasing it: a
        // moment without signal should not bury what works on this network.
        val blended = when {
            known == null || known.kbps == 0L -> kbps
            kbps == 0L -> known.kbps / 2
            else -> (known.kbps + kbps) / 2
        }
        store.put(key, "$blended,${clock()}")
    }

    /** The recipe to connect [protocol] with on [network]. */
    fun recipeFor(network: String, protocol: Protocol, preferred: NoizeProfile): Recipe {
        val tries = recipes(protocol, preferred)
        if (tries.isEmpty()) return Recipe(preferred)
        val measured = tries.associateWith { read(key(network, protocol, it)) }
        val best = measured.entries.filter { it.value != null }.maxByOrNull { it.value!!.kbps }
        if (best != null && best.value!!.kbps >= GOOD_KBPS) return best.key
        return tries.firstOrNull { measured[it] == null } ?: best?.key ?: tries.first()
    }

    /**
     * How fast [protocol] has been on [network] at its best, or null when it
     * has not been measured. Automatic ranks what answered its scan by this.
     */
    fun speedOf(network: String, protocol: Protocol): Long? =
        protocol.noizeProfiles.flatMap { recipes(protocol, it) }.toSet()
            .mapNotNull { read(key(network, protocol, it))?.kbps }.maxOrNull()

    private fun read(key: String): Entry? {
        val parts = store.get(key)?.split(',') ?: return null
        val kbps = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val at = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return Entry(kbps, at).takeIf { clock() - at < if (kbps == 0L) FAILURE_TTL_MS else TTL_MS }
    }

    private fun key(network: String, protocol: Protocol, recipe: Recipe) = "$network/${protocol.id}/${recipe.id}"

    private data class Entry(val kbps: Long, val at: Long)

    companion object {
        /** Fast enough that looking for better is not worth a slower connection. */
        const val GOOD_KBPS = 1_500L

        /** Profiles worth measuring after the protocol's own default, lightest first. */
        private val EXPLORE = listOf(NoizeProfile.Off, NoizeProfile.Light)

        /** A larger MASQUE packet for HTTP/3: less overhead where the path carries it. */
        const val WIDE_H3_MTU = 1350

        /**
         * What is worth measuring for [protocol], the preferred profile first.
         * Only what changes the tunnel's speed and is read per tunnel: the
         * obfuscation, and for HTTP/3 the packet size. WireGuard's packet
         * mode is not among them, since the speed probe always takes the
         * SOCKS path and could not tell the two apart.
         */
        fun recipes(protocol: Protocol, preferred: NoizeProfile): List<Recipe> {
            val profiles = protocol.noizeProfiles
            if (profiles.isEmpty()) return emptyList()
            val first = if (preferred in profiles) preferred else profiles.first()
            val noizes = (listOf(first) + EXPLORE.filter { it in profiles }).distinct()
            val wide = if (protocol == Protocol.MasqueH3) listOf(Recipe(first, WIDE_H3_MTU)) else emptyList()
            return noizes.map { Recipe(it) } + wide
        }

        private const val TTL_MS = 7L * 24 * 60 * 60 * 1000
        private const val FAILURE_TTL_MS = 6L * 60 * 60 * 1000
    }
}

/** One combination of per-tunnel settings, measured on its own. */
data class Recipe(val noize: NoizeProfile, val masqueMtu: Int = 0) {
    /** The base recipe keeps the bare profile name, as earlier versions stored it. */
    val id: String get() = noize.wire + if (masqueMtu > 0) "+mtu$masqueMtu" else ""
}

interface TuningStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

class MemoryTuningStore : TuningStore {
    private val values = HashMap<String, String>()
    override fun get(key: String): String? = synchronized(values) { values[key] }
    override fun put(key: String, value: String) { synchronized(values) { values[key] = value } }
}

class PrefsTuningStore(context: Context) : TuningStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("tuning", Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
}
