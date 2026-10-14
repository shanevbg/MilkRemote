package com.sheinsez.mdropdx12.remote.network.vban

/**
 * The stream is locked, the network is fine, and there is nothing in the track.
 *
 * A fourth failure mode, distinct from every one already handled here.
 * [Reachability] asks whether the mixer answers; [AudioStall] asks whether
 * audio is arriving; [JitterPolicy] asks what to do with a packet that did.
 * None of them asks whether playout is keeping any LEAD, and without lead a
 * perfectly healthy stream pops continuously.
 *
 * How it happens: playout falls behind during a bad patch -- a retry storm at
 * the far end of the house will do it -- and the bounded playout channel
 * starts discarding. It discards the OLDEST packet, which is exactly the one
 * about to be played, so every discard is a hole; and the packets it keeps
 * become the oldest in their turn and are discarded too. The buffer can never
 * rebuild, so the holes never stop. Measured on the rig: `queued` fell to 0
 * at 05:07:18 and stayed there, and `inserts` went from 63 to 7977 over the
 * next eleven minutes with `net=3 ms` and the PC reporting `dropped=0`
 * throughout. Stopping and starting playback by hand cleared it instantly,
 * which is the whole proof that nothing outside this phone was wrong.
 *
 * The cure is the same thing that hand-press does: flush and re-establish, so
 * playout starts again with a cushion in front of it.
 *
 * Pure, so the thresholds are argued about here rather than tuned on the rig.
 */
class BufferHealth(
    /**
     * Queued audio at or below which the track is considered to have no lead.
     *
     * Not zero, because a track can legitimately read a frame or two shy of
     * empty on the tick it is sampled. Four milliseconds is under one packet
     * at 48 kHz, so nothing with a working cushion can be mistaken for this.
     */
    private val emptyMs: Int = 4,
    /**
     * Consecutive empty ticks before re-priming.
     *
     * Three, at the 2 s state tick, so about six seconds. A healthy stream
     * never sits empty that long -- it runs at the profile's depth, 40 ms on
     * low-latency and 150 on resilient -- and a transient dip while the
     * network misbehaves recovers within one tick. Six seconds of nothing,
     * while the PC says it is sending, is not a dip.
     */
    private val tolerance: Int = 3,
) {
    private var ticks = 0

    /** For a diagnostics line: consecutive ticks seen with no lead. */
    val emptyTicks: Int get() = ticks

    fun reset() {
        ticks = 0
    }

    /**
     * One state tick went by. Returns true if the track should be re-primed.
     *
     * [emitting] is the PC's own word for whether it is sending anything. An
     * empty track with nothing being sent is correct and must never be
     * re-primed -- that is a paused mixer, not a fault, and flushing on it
     * would churn the track for as long as nobody was playing anything.
     */
    fun onTick(queuedMs: Int, playing: Boolean, emitting: Boolean): Boolean {
        if (!playing || !emitting || queuedMs > emptyMs) {
            ticks = 0
            return false
        }
        ticks++
        if (ticks < tolerance) return false
        ticks = 0
        return true
    }
}
