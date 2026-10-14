package com.sheinsez.mdropdx12.remote.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkedFadersTest {

    private fun f(key: String, pct: Int): MixerFader {
        val parts = key.split("|")
        return MixerFader(parts[0], parts[0], "sonar", parts[1], parts[1], pct, false, "ok", false)
    }

    @Test
    fun everyLinkedFaderMovesByTheStep() {
        val out = nudgeLinked(listOf(f("a|main", 50), f("b|main", 30)), step = 5, up = true)
        assertEquals(mapOf("a|main" to 55, "b|main" to 35), out)
    }

    @Test
    fun downMovesTheOtherWay() {
        val out = nudgeLinked(listOf(f("a|main", 50), f("b|main", 30)), step = 10, up = false)
        assertEquals(mapOf("a|main" to 40, "b|main" to 20), out)
    }

    /**
     * The group stops when ANY member would clip. Clamping individually would
     * let the levels drift apart at the ends, and one trip to zero and back
     * would destroy the balance the user set.
     */
    @Test
    fun theGroupStopsWhenOneMemberWouldClip() {
        val out = nudgeLinked(listOf(f("a|main", 98), f("b|main", 50)), step = 5, up = true)
        assertEquals(mapOf("a|main" to 100, "b|main" to 52), out)
    }

    @Test
    fun theGroupStopsAtZeroTheSameWay() {
        val out = nudgeLinked(listOf(f("a|main", 3), f("b|main", 60)), step = 10, up = false)
        assertEquals(mapOf("a|main" to 0, "b|main" to 57), out)
    }

    /** Already at the limit: nothing moves, rather than the others drifting. */
    @Test
    fun aGroupAlreadyAtTheLimitDoesNotMove() {
        val out = nudgeLinked(listOf(f("a|main", 100), f("b|main", 40)), step = 5, up = true)
        assertEquals(mapOf("a|main" to 100, "b|main" to 40), out)
    }

    @Test
    fun anEmptySelectionProducesNothing() {
        assertTrue(nudgeLinked(emptyList(), step = 5, up = true).isEmpty())
    }
}
