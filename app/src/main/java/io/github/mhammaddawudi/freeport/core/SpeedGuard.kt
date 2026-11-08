package io.github.mhammaddawudi.freeport.core

/**
 * Notices when a held session has gone slow, the way a person notices it:
 * they are watching something and it keeps stalling.
 *
 * People found that a session which had been fast would crawl after a while,
 * and that switching off and on again brought the speed straight back. On
 * these networks a long-lived flow is the one that gets throttled; a fresh
 * one to the same edge is not, yet. This makes that switch for them, before
 * they reach for the button.
 *
 * It only ever judges while the line is in use — someone scrolling reels
 * pulls data in bursts — and only flags a slowdown when even the best burst
 * of the last stretch fell well below what the edge did when it was chosen.
 * The flag is a suspicion: the engine confirms it with a short download
 * before it touches the session. Pure, so it can be tested with made-up
 * numbers; [feed] takes one sample of the cumulative receive counter.
 */
class SpeedGuard(
    /** What the edge delivered when it was chosen, in KB/s. */
    private val baselineKbps: Long,
    private val now: () -> Long,
    private val startedAt: Long = now(),
) {
    private val samples = ArrayDeque<Pair<Long, Long>>()
    private var lastSuspicionAt = Long.MIN_VALUE / 2

    /** How slow counts as slow: a share of the baseline, never below what video needs. */
    val floorKbps: Long = maxOf(MIN_FLOOR_KBPS, baselineKbps * FLOOR_PERCENT / 100)

    /** Records the cumulative bytes received at this moment. */
    fun feed(rxBytes: Long) {
        val at = now()
        samples.addLast(at to rxBytes)
        while (samples.size > 1 && at - samples.first().first > WINDOW_MS) samples.removeFirst()
    }

    /**
     * True when the session should be checked: it has run long enough, it
     * is in use, and even its best burst lately was below [floorKbps].
     */
    fun suspicious(): Boolean {
        val at = now()
        if (at - startedAt < GRACE_MS || at - lastSuspicionAt < RECHECK_MS) return false
        if (samples.size < 2) return false
        val first = samples.first()
        val last = samples.last()
        val span = last.first - first.first
        if (span < WINDOW_MS * 3 / 4) return false
        val averageKbps = (last.second - first.second) * 1000 / span / 1024
        if (averageKbps < IN_USE_KBPS) return false
        if (bestBurstKbps() >= floorKbps) return false
        lastSuspicionAt = at
        return true
    }

    /** The average over the window in KB/s, null until the window has filled. */
    fun averageKbps(): Long? {
        if (samples.size < 2) return null
        val span = samples.last().first - samples.first().first
        if (span < WINDOW_MS * 3 / 4) return null
        return (samples.last().second - samples.first().second) * 1000 / span / 1024
    }

    /** Whether a probe that delivered [kbps] confirms the suspicion. */
    fun confirms(kbps: Long): Boolean = kbps < floorKbps

    /** The fastest [BURST_MS] stretch in the window, in KB/s. */
    internal fun bestBurstKbps(): Long {
        val list = samples.toList()
        var best = 0L
        var start = 0
        for (end in list.indices) {
            while (start < end && list[end].first - list[start].first > BURST_MS) start++
            val span = list[end].first - list[start].first
            if (span >= BURST_MS / 2) {
                best = maxOf(best, (list[end].second - list[start].second) * 1000 / span / 1024)
            }
        }
        return best
    }

    companion object {
        /** The stretch of use that is judged. */
        const val WINDOW_MS = 45_000L

        /** A burst: long enough to be a reel loading, short enough to catch one. */
        const val BURST_MS = 2_000L

        /** Under this on average the person is reading, not watching; nothing is judged. */
        const val IN_USE_KBPS = 25L

        /** A fresh session gets this long before it is judged at all. */
        const val GRACE_MS = 60_000L

        /** After a check, whatever its outcome, the next one waits this long. */
        const val RECHECK_MS = 150_000L

        const val FLOOR_PERCENT = 35L

        /** Reels need roughly this much; below it they stall whatever the baseline was. */
        const val MIN_FLOOR_KBPS = 250L
    }
}
