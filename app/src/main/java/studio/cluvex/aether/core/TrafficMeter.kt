package studio.cluvex.aether.core

import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import kotlin.random.Random

/** Cumulative byte counters, sampled by the UI once a second. */
data class TrafficTotals(val rxBytes: Long, val txBytes: Long)

fun interface TrafficMeter {
    /** The current totals, or null when the platform will not say. */
    fun totals(): TrafficTotals?
}

/**
 * Counts what the core itself sends and receives.
 *
 * The app is excluded from its own VPN, so every byte this uid moves over a
 * real interface is tunnel traffic on the wire — encrypted, padded, and
 * therefore slightly larger than what the apps inside the tunnel see, but a
 * faithful measure of the link.
 */
class UidTrafficMeter : TrafficMeter {
    private val uid = Process.myUid()

    override fun totals(): TrafficTotals? {
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) return null
        return TrafficTotals(rx, tx)
    }
}

/** Believable browsing-shaped traffic for the mock core. */
class SimulatedTrafficMeter(private val random: Random = Random.Default) : TrafficMeter {
    private var rx = 0L
    private var tx = 0L
    private var last = SystemClock.elapsedRealtime()
    private var burst = 0

    @Synchronized
    override fun totals(): TrafficTotals {
        val now = SystemClock.elapsedRealtime()
        val seconds = ((now - last).coerceAtLeast(0)) / 1000.0
        last = now
        if (burst == 0 && random.nextInt(100) < 18) burst = random.nextInt(2, 6)
        val rxRate = if (burst > 0) random.nextLong(900_000, 6_400_000) else random.nextLong(6_000, 180_000)
        val txRate = rxRate / random.nextLong(6, 14)
        if (burst > 0) burst--
        rx += (rxRate * seconds).toLong()
        tx += (txRate * seconds).toLong()
        return TrafficTotals(rx, tx)
    }
}
