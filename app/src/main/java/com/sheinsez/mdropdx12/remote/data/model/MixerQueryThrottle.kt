package com.sheinsez.mdropdx12.remote.data.model

/**
 * Lets at most one mixer query batch through per interval.
 *
 * The PC reads SteelSeries Sonar over HTTP to answer, and Sonar is not robust
 * to being asked repeatedly, so this is a hard ceiling rather than a politeness.
 * Kept free of clocks and coroutines so the decision itself can be tested.
 */
class MixerQueryThrottle(private val minIntervalMs: Long = 1000L) {
    private var lastAt: Long? = null

    /** Milliseconds to wait before the next query may go out; 0 means now. */
    fun waitFor(nowMs: Long): Long {
        val last = lastAt ?: return 0L
        val since = nowMs - last
        // A clock that went backwards must not grant a free pass or block for
        // hours; treat it as "just asked".
        if (since < 0L) return minIntervalMs
        return if (since >= minIntervalMs) 0L else minIntervalMs - since
    }

    fun record(nowMs: Long) { lastAt = nowMs }
}
