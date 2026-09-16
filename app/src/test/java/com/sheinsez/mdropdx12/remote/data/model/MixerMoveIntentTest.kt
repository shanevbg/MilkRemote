package com.sheinsez.mdropdx12.remote.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class MixerMoveIntentTest {

    @Test
    fun aPressIsTheMoveToSend() {
        val i = MixerMoveIntent()
        assertEquals("a|main" to -1, i.start("a|main", -1))
    }

    /**
     * A refusal is not a failure: the PC reorders on its own -- the pinned
     * failover device swaps out every few hours -- so the row the user pressed
     * is still there and the intent survives to be asked against the new list.
     */
    @Test
    fun aRefusalIsAskedAgainWhenTheNewListArrives() {
        val i = MixerMoveIntent()
        i.start("a|main", 1)
        assertTrue(i.refused())
        assertEquals("a|main" to 1, i.onView())
    }

    /** Once. A PC that kept reordering would otherwise be an endless argument. */
    @Test
    fun onlyOnceForOnePress() {
        val i = MixerMoveIntent()
        i.start("a|main", 1)
        i.refused()
        i.onView()

        assertFalse(i.refused())
        assertNull(i.onView())
    }

    /**
     * Only a refusal arms the retry. Views arrive for every reason -- a device
     * connecting, a reorder made at the PC -- and a move whose acknowledgement
     * was merely lost must not resurface minutes later on one of those.
     */
    @Test
    fun aViewArrivingOnItsOwnResendsNothing() {
        val i = MixerMoveIntent()
        i.start("a|main", 1)
        assertNull(i.onView())
        assertNull(i.onView())
    }

    @Test
    fun anAcknowledgementEndsIt() {
        val i = MixerMoveIntent()
        i.start("a|main", 1)
        i.acknowledged()

        assertFalse(i.refused())
        assertNull(i.onView())
    }

    /** A fresh press is a fresh intent, and gets its own one retry. */
    @Test
    fun aNewPressRestoresTheRetry() {
        val i = MixerMoveIntent()
        i.start("a|main", 1)
        i.refused()
        i.onView()

        i.start("b|main", -1)
        assertTrue(i.refused())
        assertEquals("b|main" to -1, i.onView())
    }

    /** Nothing to send -- already at that end of the list -- pends nothing. */
    @Test
    fun givingUpLeavesNothingPending() {
        val i = MixerMoveIntent()
        i.start("a|main", -1)
        i.abandon()

        assertFalse(i.refused())
        assertNull(i.onView())
    }
}
