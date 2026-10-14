package com.sheinsez.mdropdx12.remote.network.vban

import org.junit.Assert.assertEquals
import org.junit.Test

class LagEstimateTest {

    @Test
    fun `the network term is half the round trip`() {
        assertEquals(10, LagEstimate.fromRtt(20))
        assertEquals(0, LagEstimate.fromRtt(0))
        assertEquals(0, LagEstimate.fromRtt(-5))     // a clock that misbehaved
    }

    /**
     * The shape of a real session: srcLatencyMs is the 32 ms a live mdxmixer
     * reported, the buffer is the resilient profile's target, and the sink
     * reports a little of its own.
     */
    @Test
    fun `the total is the sum of every term`() {
        val terms = LagTerms(
            networkMs = 10,
            srcLatencyMs = 32,
            bufferDepthMs = 150,
            outputLatencyMs = 25,
            manualOffsetMs = 0,
        )
        assertEquals(217, LagEstimate.total(terms))
    }

    /** Bluetooth sinks under-report; the manual offset is how that is dialed in. */
    @Test
    fun `the manual offset is added, and may be negative`() {
        val base = LagTerms(10, 32, 150, 25, 0)
        assertEquals(367, LagEstimate.total(base.copy(manualOffsetMs = 150)))
        assertEquals(197, LagEstimate.total(base.copy(manualOffsetMs = -20)))
    }

    /** However far the slider is dragged, a negative lag is not a number. */
    @Test
    fun `the total never goes below zero`() {
        assertEquals(0, LagEstimate.total(LagTerms(1, 1, 1, 1, -5000)))
    }

    /** With nothing measured yet the figure is honest rather than invented. */
    @Test
    fun `an unmeasured session reports zero`() {
        assertEquals(0, LagEstimate.total(LagTerms(0, 0, 0, 0, 0)))
    }
}
