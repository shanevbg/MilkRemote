package com.sheinsez.mdropdx12.remote.network.vban

import com.sheinsez.mdropdx12.remote.network.vban.VbanSession.Counters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telling a hole we made from one the network made, and a fault happening now
 * from one that happened once.
 *
 * Both distinctions were missing, and both cost real time. `inserts` counted
 * holes on the far side of a DROP_OLDEST queue, so a packet this phone threw
 * away was indistinguishable from one that never arrived -- and a phone-side
 * fault was read as network loss for eleven minutes while `net` was 3 ms.
 * `troubled` tested lifetime totals, so the fault line documented as silent on
 * a healthy stream printed every two seconds from the moment the track's
 * cushion first filled.
 */
class CountersTest {

    @Test
    fun insertsBeyondArrivalGapsAreOurs() {
        // Twelve holes played, two gaps actually seen arriving: ten were made
        // by discarding from a full playout queue.
        val c = Counters(inserts = 12, arrivalGaps = 2)
        assertEquals(10, c.selfInflicted)
    }

    @Test
    fun holesTheNetworkMadeAreNotOurs() {
        val c = Counters(inserts = 7, arrivalGaps = 7)
        assertEquals(0, c.selfInflicted)
    }

    @Test
    fun moreArrivalGapsThanInsertsIsNotNegative() {
        // A gap can be seen on arrival and then re-locked past rather than
        // filled, so arrivals legitimately exceed inserts.
        val c = Counters(inserts = 3, arrivalGaps = 9)
        assertEquals(0, c.selfInflicted)
    }

    @Test
    fun aHealthyStreamReportsNothing() {
        val c = Counters()
        assertFalse(c.rising)
        assertFalse(c.recentlyTroubled)
        assertEquals(0, c.selfInflicted)
    }

    /**
     * THE REGRESSION. A session that underran while its cushion filled, and
     * has been perfect since, must not read as faulting.
     */
    @Test
    fun oldUnderrunsDoNotMakeAStreamTroubled() {
        val c = Counters(underruns = 9, rising = false, recentlyTroubled = false)
        assertFalse(c.rising)
        assertFalse(c.recentlyTroubled)
    }

    @Test
    fun aRiseIsReportedSeparatelyFromItsMemory() {
        // The log wants the instant; the screen wants the memory. They are
        // different fields precisely so one can clear while the other has not.
        val now = Counters(underruns = 10, rising = true, recentlyTroubled = true)
        assertTrue(now.rising)
        assertTrue(now.recentlyTroubled)

        val justAfter = Counters(underruns = 10, rising = false, recentlyTroubled = true)
        assertFalse(justAfter.rising)
        assertTrue(justAfter.recentlyTroubled)
    }
}
