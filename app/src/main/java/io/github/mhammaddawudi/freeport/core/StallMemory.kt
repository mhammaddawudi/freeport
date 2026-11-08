package io.github.mhammaddawudi.freeport.core

import io.github.mhammaddawudi.freeport.data.Protocol

/**
 * Which protocols went dead mid-session on a network, so automatic stops
 * choosing them there.
 *
 * Some operators let a WireGuard flow through for a quick test and for
 * messages, then choke it as soon as video starts: the connect-time speed
 * test looks fine and every edge of that protocol fails the same way. A
 * stall the speed guard confirmed (next to nothing arriving while in use) is
 * remembered for [MEMORY_MS]; automatic tries every other protocol first,
 * and only falls back to a stalled one when nothing else answers.
 */
object StallMemory {
    const val MEMORY_MS = 6 * 60 * 60_000L

    /** Under this a confirmed slowdown is a stall, not just a slow edge. */
    const val STALL_KBPS = 64L

    fun isRecent(stalledAt: Long, now: Long): Boolean = stalledAt > 0 && now - stalledAt in 0 until MEMORY_MS

    /**
     * [items] with those of stalled protocols moved to the end, the one that
     * stalled longest ago first among them: when two have stalled here, the
     * one that keeps stalling goes last. [stalledAt] maps each stalled
     * protocol to when it last stalled. Order is otherwise kept.
     */
    fun <T> stalledLast(items: List<T>, stalledAt: Map<Protocol, Long>, protocolOf: (T) -> Protocol): List<T> {
        val (bad, good) = items.partition { protocolOf(it) in stalledAt }
        return good + bad.sortedBy { stalledAt.getValue(protocolOf(it)) }
    }
}
