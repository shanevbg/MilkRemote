package com.sheinsez.mdropdx12.remote.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MixerQueryThrottleTest {

    @Test
    fun theFirstQueryGoesOutImmediately() {
        assertEquals(0L, MixerQueryThrottle().waitFor(5_000L))
    }

    /** Sonar is read over HTTP to answer, so a second query has to wait. */
    @Test
    fun aSecondQueryInsideTheIntervalIsHeldBack() {
        val t = MixerQueryThrottle(1000L)
        t.record(5_000L)
        assertEquals(1000L, t.waitFor(5_000L))
        assertEquals(600L, t.waitFor(5_400L))
        assertEquals(0L, t.waitFor(6_000L))
        assertEquals(0L, t.waitFor(9_000L))
    }

    /** A clock that jumps backwards must not grant a free pass. */
    @Test
    fun aBackwardsClockDoesNotOpenTheGate() {
        val t = MixerQueryThrottle(1000L)
        t.record(9_000L)
        assertEquals(1000L, t.waitFor(1_000L))
    }
}
