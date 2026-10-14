package com.sheinsez.mdropdx12.remote.network.vban

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the socket is rebuilt, and -- the part that was wrong -- how often.
 *
 * The backoff exists so a PC that is simply switched off does not cost a new
 * socket every ten seconds all night. It was written, shipped, and did not
 * work: the field log shows thirteen consecutive rebinds at a flat twelve
 * seconds, the threshold never once growing.
 *
 * The cause is not visible from this class alone, which is why it survived
 * review. [Reachability] is reset on every rebind -- it must be, or the fresh
 * socket inherits the dead one's debt and is condemned on its first tick --
 * so for the next two or three ticks it answers "reachable" having heard
 * nothing at all. The old `onTick(false)` took that for contact and reset the
 * threshold with it.
 *
 * So the rule under test is narrow and worth stating plainly: a tick that
 * merely fails to be unreachable stops the count; only an arriving datagram
 * forgives the backoff.
 */
class RebindPolicyTest {

    /** Ticks until the next rebind, driving it the way the ping loop does. */
    private fun ticksToRebind(p: RebindPolicy, limit: Int = 500): Int {
        for (i in 1..limit) if (p.onTick(unreachable = true)) return i
        return -1
    }

    @Test
    fun firstRebuildWaitsTheTolerance() {
        val p = RebindPolicy(tolerance = 5)
        assertEquals(5, ticksToRebind(p))
    }

    @Test
    fun silenceDoublesTheWaitEachTime() {
        val p = RebindPolicy(tolerance = 5, ceiling = 40)
        assertEquals(5, ticksToRebind(p))
        assertEquals(10, ticksToRebind(p))
        assertEquals(20, ticksToRebind(p))
        assertEquals(40, ticksToRebind(p))
    }

    @Test
    fun theWaitStopsGrowingAtTheCeiling() {
        val p = RebindPolicy(tolerance = 5, ceiling = 20)
        ticksToRebind(p)
        ticksToRebind(p)
        assertEquals(20, ticksToRebind(p))
        assertEquals(20, ticksToRebind(p))
    }

    /**
     * THE REGRESSION. Reachability's post-rebind optimism arrives here as a
     * run of `onTick(false)`, and must not buy back the short interval.
     */
    @Test
    fun optimismAfterARebuildDoesNotResetTheBackoff() {
        val p = RebindPolicy(tolerance = 5, ceiling = 40)
        assertEquals(5, ticksToRebind(p))

        // Exactly what the session does next: reach.reset() makes the next
        // few ticks report reachable, on no evidence.
        repeat(3) { assertFalse(p.onTick(unreachable = false)) }

        // The old code answered 5 here, for ever.
        assertEquals(10, ticksToRebind(p))
    }

    @Test
    fun hearingSomethingForgivesTheBackoff() {
        val p = RebindPolicy(tolerance = 5, ceiling = 40)
        ticksToRebind(p)
        ticksToRebind(p)

        // A datagram actually arrived. This, and only this, starts over.
        p.onHeard()
        assertEquals(5, ticksToRebind(p))
    }

    @Test
    fun aReachableTickStopsTheCountWithoutRebuilding() {
        val p = RebindPolicy(tolerance = 5)
        repeat(4) { assertFalse(p.onTick(unreachable = true)) }
        assertFalse(p.onTick(unreachable = false))
        // Count restarted, so four more silent ticks are still not enough.
        repeat(4) { assertFalse(p.onTick(unreachable = true)) }
        assertTrue(p.onTick(unreachable = true))
    }

    @Test
    fun resetRestoresBothTheCountAndTheWait() {
        val p = RebindPolicy(tolerance = 5, ceiling = 40)
        ticksToRebind(p)
        ticksToRebind(p)
        p.reset()
        assertEquals(5, ticksToRebind(p))
    }
}
