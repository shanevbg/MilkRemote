package com.sheinsez.mdropdx12.remote.network.vban

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Playing with no lead, and the two cases that look like it and are not.
 *
 * The fault this exists for was live for eleven minutes before anybody could
 * name it: `queued` sat at 0, `inserts` ran from 63 to 7977, and every other
 * instrument said the stream was healthy -- `net=3 ms`, PC `dropped=0`, no
 * underruns, no re-locks, link Live throughout. The one thing that cleared it
 * was a hand-press of Stop then Play.
 *
 * The risk in automating that press is firing it when nothing is wrong, and
 * there are two ways to be empty and perfectly fine: nobody is playing
 * anything, and the PC has nothing to send. Both are covered below, because
 * re-priming on either would flush the track every six seconds for as long as
 * the mixer sat idle.
 */
class BufferHealthTest {

    @Test
    fun anEmptyTrackWithAudioFlowingIsRePrimedAfterThreeTicks() {
        val h = BufferHealth(emptyMs = 4, tolerance = 3)
        assertFalse(h.onTick(queuedMs = 0, playing = true, emitting = true))
        assertFalse(h.onTick(queuedMs = 0, playing = true, emitting = true))
        assertTrue(h.onTick(queuedMs = 0, playing = true, emitting = true))
    }

    @Test
    fun aHealthyCushionNeverTriggers() {
        val h = BufferHealth(emptyMs = 4, tolerance = 3)
        // The low-latency profile runs at 40 ms, resilient at 150.
        repeat(50) { assertFalse(h.onTick(queuedMs = 37, playing = true, emitting = true)) }
        repeat(50) { assertFalse(h.onTick(queuedMs = 150, playing = true, emitting = true)) }
    }

    @Test
    fun anIdleMixerIsNotAFault() {
        // emitting=false: the PC has nothing to send, so of course the track
        // is empty. Re-priming here would churn for as long as nobody played
        // anything -- the same mistake AudioStall had to be taught about.
        val h = BufferHealth(emptyMs = 4, tolerance = 3)
        repeat(50) { assertFalse(h.onTick(queuedMs = 0, playing = true, emitting = false)) }
    }

    @Test
    fun aControlOnlyLinkIsNotAFault() {
        // playing=false: no AudioTrack exists. The session is Streaming in
        // the protocol sense and renders nothing, which is a supported state.
        val h = BufferHealth(emptyMs = 4, tolerance = 3)
        repeat(50) { assertFalse(h.onTick(queuedMs = 0, playing = false, emitting = true)) }
    }

    @Test
    fun oneGoodTickClearsTheCount() {
        val h = BufferHealth(emptyMs = 4, tolerance = 3)
        assertFalse(h.onTick(queuedMs = 0, playing = true, emitting = true))
        assertFalse(h.onTick(queuedMs = 0, playing = true, emitting = true))
        // A dip recovered. Not a fault.
        assertFalse(h.onTick(queuedMs = 30, playing = true, emitting = true))
        assertFalse(h.onTick(queuedMs = 0, playing = true, emitting = true))
        assertFalse(h.onTick(queuedMs = 0, playing = true, emitting = true))
        assertTrue(h.onTick(queuedMs = 0, playing = true, emitting = true))
    }

    @Test
    fun aFrameShyOfEmptyStillCountsAsEmpty() {
        // A track can read a frame or two short on the tick it is sampled;
        // requiring exactly zero would miss the fault most of the time.
        val h = BufferHealth(emptyMs = 4, tolerance = 3)
        repeat(2) { assertFalse(h.onTick(queuedMs = 3, playing = true, emitting = true)) }
        assertTrue(h.onTick(queuedMs = 4, playing = true, emitting = true))
    }

    @Test
    fun itStartsOverAfterFiring() {
        val h = BufferHealth(emptyMs = 4, tolerance = 3)
        repeat(2) { h.onTick(queuedMs = 0, playing = true, emitting = true) }
        assertTrue(h.onTick(queuedMs = 0, playing = true, emitting = true))
        // The re-prime deserves a chance to work before it is judged again.
        assertFalse(h.onTick(queuedMs = 0, playing = true, emitting = true))
        assertFalse(h.onTick(queuedMs = 0, playing = true, emitting = true))
        assertTrue(h.onTick(queuedMs = 0, playing = true, emitting = true))
    }
}
