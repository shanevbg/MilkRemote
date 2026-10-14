package com.sheinsez.mdropdx12.remote.network.vban

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The reading that would have explained a three-and-a-half-minute silence.
 *
 * Thresholds come from the walk on 2026-10-10 rather than from the stream's
 * bitrate: 272 Mbps carried it perfectly, 136 carried it perfectly, 36 could
 * not carry it at all while Android reported the network as healthy. See
 * `docs/design/2026-10-10-stream-latency-and-radio-measurements.md`.
 *
 * The hard part is not the trigger, it is not flapping. Rate adaptation steps
 * between modulation levels several times a second, so a single threshold
 * crossed by one sample would blink a warning on and off at a perfectly good
 * distance -- which teaches people to ignore it, and then it is worse than
 * nothing.
 */
class DownlinkHealthTest {

    private fun ticks(h: DownlinkHealth, mbps: Int, n: Int): Downlink {
        var last = h.state
        repeat(n) { last = h.onTick(mbps) }
        return last
    }

    @Test
    fun aHealthyLinkSaysNothing() {
        val h = DownlinkHealth()
        assertEquals(Downlink.Fine, ticks(h, 272, 5))
        assertEquals(Downlink.Fine, ticks(h, 136, 5))
    }

    @Test
    fun theRateThatKilledTheStreamReadsAsCollapsed() {
        val h = DownlinkHealth()
        ticks(h, 272, 2)
        assertEquals(Downlink.Collapsed, ticks(h, 36, 2))
    }

    @Test
    fun thinningIsWarnedAboutBeforeItCollapses() {
        // The value of a second band: the rate falls before the buffer
        // drains, so this fires while the audio is still clean.
        val h = DownlinkHealth()
        ticks(h, 272, 2)
        assertEquals(Downlink.Weak, ticks(h, 72, 2))
    }

    @Test
    fun oneLowSampleIsNotEnough() {
        val h = DownlinkHealth()
        ticks(h, 272, 3)
        // A single step down means nothing; the rate is renegotiated
        // continuously.
        assertEquals(Downlink.Fine, h.onTick(60))
        assertEquals(Downlink.Weak, h.onTick(60))
    }

    @Test
    fun aDipThatRecoversNeverWarns() {
        val h = DownlinkHealth()
        ticks(h, 272, 3)
        assertEquals(Downlink.Fine, h.onTick(60))
        assertEquals(Downlink.Fine, h.onTick(272))
        assertEquals(Downlink.Fine, h.onTick(60))
    }

    /** THE FLAPPING CASE. Back above the trigger is not back to healthy. */
    @Test
    fun aWarningHoldsUntilClearlyRecovered() {
        val h = DownlinkHealth(weakBelowMbps = 100, clearAboveMbps = 150)
        ticks(h, 272, 2)
        assertEquals(Downlink.Weak, ticks(h, 72, 2))
        // Over the trigger, under the clear point: still warning, or it
        // blinks on and off at one distance.
        assertEquals(Downlink.Weak, h.onTick(110))
        assertEquals(Downlink.Weak, h.onTick(149))
        assertEquals(Downlink.Fine, h.onTick(150))
    }

    @Test
    fun collapsedRelaxesToWeakOnTheWayBack() {
        val h = DownlinkHealth()
        ticks(h, 272, 2)
        assertEquals(Downlink.Collapsed, ticks(h, 36, 2))
        assertEquals(Downlink.Weak, h.onTick(80))
        assertEquals(Downlink.Fine, h.onTick(200))
    }

    @Test
    fun noReadingIsUnknownRatherThanAFault() {
        // Not on Wi-Fi, an API too old to ask, or the radio declining to
        // answer. Claiming the downlink had collapsed would be a lie told
        // confidently.
        val h = DownlinkHealth()
        assertEquals(Downlink.Unknown, ticks(h, -1, 5))
    }

    @Test
    fun losingTheReadingClearsAWarning() {
        val h = DownlinkHealth()
        ticks(h, 272, 2)
        assertEquals(Downlink.Collapsed, ticks(h, 36, 2))
        // Wi-Fi dropped entirely. Something else is wrong now and this is no
        // longer the thing to be telling anybody.
        assertEquals(Downlink.Unknown, h.onTick(-1))
    }

    @Test
    fun resetForgetsEverything() {
        val h = DownlinkHealth()
        ticks(h, 36, 3)
        h.reset()
        assertEquals(Downlink.Unknown, h.state)
        assertEquals(Downlink.Fine, h.onTick(272))
    }
}
