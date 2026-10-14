package com.sheinsez.mdropdx12.remote.network.vban

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReachabilityTest {

    @Test
    fun `a fresh session is assumed reachable`() {
        // Not "unknown". A session that has sent nothing yet must not render
        // as lost, or every start flashes an error before the first reply.
        assertTrue(Reachability().reachable)
    }

    @Test
    fun `a packet heard is reachable`() {
        val r = Reachability()
        r.onPingSent()
        r.onHeard()
        assertTrue(r.reachable)
        assertEquals(0, r.unanswered)
    }

    /**
     * The reason the tolerance is not one. A single dropped datagram on a busy
     * AP is ordinary and UDP owes nobody a delivery.
     */
    @Test
    fun `one lost ping is not a lost mixer`() {
        val r = Reachability()
        r.onPingSent()
        assertTrue(r.reachable)
    }

    @Test
    fun `two lost pings are still not enough`() {
        val r = Reachability()
        r.onPingSent()
        r.onPingSent()
        assertTrue(r.reachable)
    }

    @Test
    fun `three unanswered in a row is unreachable`() {
        val r = Reachability()
        repeat(3) { r.onPingSent() }
        assertFalse(r.reachable)
        assertEquals(3, r.unanswered)
    }

    @Test
    fun `anything heard after a gap clears the whole debt`() {
        val r = Reachability()
        repeat(5) { r.onPingSent() }
        assertFalse(r.reachable)
        r.onHeard()
        assertTrue(r.reachable)
        assertEquals(0, r.unanswered)
    }

    /**
     * Replies are not individually matched. A late reply arriving after two
     * timeouts still proves the mixer is there, which is the only question.
     */
    @Test
    fun `an interleaved packet keeps it reachable`() {
        val r = Reachability()
        r.onPingSent(); r.onPingSent()
        r.onHeard()
        r.onPingSent(); r.onPingSent()
        assertTrue(r.reachable)
    }

    @Test
    fun `it fires before the server would have expired the peer`() {
        // 3 ticks at 2 s is ~6 s, inside the server's 10 s expiry, so the
        // phone says it has lost the PC rather than finding out from the next
        // refused command.
        val ticks = 3
        assertTrue(ticks * VbanSession.PING_INTERVAL_MS < VbanSession.PEER_EXPIRY_MS)
    }

    @Test
    fun `reset puts it back`() {
        val r = Reachability()
        repeat(4) { r.onPingSent() }
        r.reset()
        assertTrue(r.reachable)
        assertEquals(0, r.unanswered)
    }

    @Test
    fun `the tolerance is honoured exactly`() {
        val r = Reachability(tolerance = 2)
        r.onPingSent()
        assertTrue(r.reachable)
        r.onPingSent()
        assertFalse(r.reachable)
    }
}
