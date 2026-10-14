package com.sheinsez.mdropdx12.remote.network.vban

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The only tell that the sending PC has no clock. The audio itself is well
 * formed and contiguous, so this counter is the whole signal.
 */
class SilenceDetectorTest {

    @Test
    fun `the first sample never accuses`() {
        // Joining a long-running PC must not light up on a counter that grew
        // before this session existed.
        assertFalse(SilenceDetector().onState(starved = 31990, emitting = true))
    }

    @Test
    fun `a steady counter is a healthy stream`() {
        val d = SilenceDetector()
        d.onState(1000, true)
        assertFalse(d.onState(1000, true))
        assertFalse(d.onState(1002, true))   // an ordinary hiccup
    }

    @Test
    fun `a counter climbing by hundreds is silence`() {
        val d = SilenceDetector()
        d.onState(1000, true)
        // ~188 packets a second, polled every 2 s: a clockless PC contributes
        // several hundred per sample.
        assertTrue(d.onState(1376, true))
        assertTrue(d.silent)
    }

    @Test
    fun `it clears when the PC starts sending real audio again`() {
        val d = SilenceDetector()
        d.onState(1000, true)
        assertTrue(d.onState(1500, true))
        assertFalse(d.onState(1500, true))
    }

    /** Not emitting at all is a different state, with its own wording. */
    @Test
    fun `an idle sender is not reported as silence`() {
        val d = SilenceDetector()
        d.onState(1000, true)
        assertFalse(d.onState(9999, emitting = false))
    }

    /** A counter that went backwards means the PC restarted. */
    @Test
    fun `a restarted PC is not silence`() {
        val d = SilenceDetector()
        d.onState(50000, true)
        assertFalse(d.onState(3, true))
    }

    @Test
    fun `the threshold is honoured exactly`() {
        val d = SilenceDetector(threshold = 50)
        d.onState(0, true)
        assertFalse(d.onState(49, true))
        d.reset()
        d.onState(0, true)
        assertTrue(d.onState(50, true))
    }
}
