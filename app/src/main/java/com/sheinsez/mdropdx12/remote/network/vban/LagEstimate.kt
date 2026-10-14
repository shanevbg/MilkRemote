package com.sheinsez.mdropdx12.remote.network.vban

/**
 * The five terms of end-to-end lag, each measured rather than guessed except
 * the last.
 *
 * This number exists to be TYPED INTO something — a video player's
 * audio-delay box, so lips stay in sync while the sound rides the phone — so
 * it is reported as one millisecond figure with the breakdown available
 * behind it.
 */
data class LagTerms(
    /** RTT/2 from the PING0 transaction: one-way, not round trip. */
    val networkMs: Int,
    /**
     * The PC's own pipeline estimate, from MDXM_VBANSTATE's `srclatencyms`
     * — capture cushion plus packet duration plus mean pacer wake. A live
     * mdxmixer reported 32 ms.
     */
    val srcLatencyMs: Int,
    /** What the player actually has buffered now, not the profile's target. */
    val bufferDepthMs: Int,
    /** AudioTimestamp-derived output latency, where the sink reports one. */
    val outputLatencyMs: Int,
    /** The user's slider, for sinks that under-report. Bluetooth hint ~150 ms. */
    val manualOffsetMs: Int,
)

object LagEstimate {

    /** One-way, so half the round trip. A clock that misbehaved reads as 0. */
    fun fromRtt(rttMs: Int): Int = if (rttMs <= 0) 0 else rttMs / 2

    fun total(terms: LagTerms): Int = maxOf(
        0,
        terms.networkMs + terms.srcLatencyMs + terms.bufferDepthMs +
            terms.outputLatencyMs + terms.manualOffsetMs,
    )
}
