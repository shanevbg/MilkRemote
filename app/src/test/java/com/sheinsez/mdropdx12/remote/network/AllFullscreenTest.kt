package com.sheinsez.mdropdx12.remote.network

import com.sheinsez.mdropdx12.remote.data.model.AllFullscreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AllFullscreenTest {

    /** Absolute, never a toggle: a surface that cannot read state must say what it wants. */
    @Test
    fun theCommandIsAbsolute() {
        assertEquals("SET_ALL_FULLSCREEN=1", CommandBuilder.setAllFullscreen(true))
        assertEquals("GET_ALL_FULLSCREEN", CommandBuilder.getAllFullscreen())
    }

    /**
     * Off is 2, not 0. 0 restores what the snapshot found, so when the windows
     * were already fullscreen it restores fullscreen and the switch does
     * nothing -- which is exactly what was reported (forgejo#98).
     */
    @Test
    fun offLeavesFullscreenRatherThanRestoringWhatWasFound() {
        assertEquals("SET_ALL_FULLSCREEN=2", CommandBuilder.setAllFullscreen(false))
        assertEquals("SET_ALL_FULLSCREEN=0", CommandBuilder.restoreAllFullscreen())
    }

    @Test
    fun theReplyCarriesTheVerdictAndTheCounts() {
        val s = MessageParser.parseAllFullscreen(
            "ALL_FULLSCREEN=1|primary=1|mirrors_active=1|children_up=2|children_total=2",
        )!!
        assertTrue(s.all)
        assertTrue(s.primary)
        assertTrue(s.mirrorsActive)
        assertEquals(2, s.childrenUp)
        assertEquals(2, s.childrenTotal)
        assertNull("nothing to explain when everything is up", s.shortfall)
    }

    /**
     * The counts are kept for this: "nothing happened" and "everything was
     * already up" look identical from a phone without them.
     */
    @Test
    fun aPartialStateSaysHowFarItGot() {
        val s = MessageParser.parseAllFullscreen(
            "ALL_FULLSCREEN=0|primary=1|mirrors_active=1|children_up=1|children_total=3",
        )!!
        assertFalse(s.all)
        assertEquals("1 of 3 displays up", s.shortfall)
    }

    @Test
    fun aMainWindowThatIsDownIsNamedWhenNothingElseExplainsIt() {
        val s = MessageParser.parseAllFullscreen(
            "ALL_FULLSCREEN=0|primary=0|mirrors_active=0|children_up=0|children_total=0",
        )!!
        assertEquals("the main window is not up", s.shortfall)
    }

    @Test
    fun nothingElseIsThisReply() {
        assertNull(MessageParser.parseAllFullscreen("IDLE_ACTIVE=0"))
        assertNull(MessageParser.parseAllFullscreen("DISPLAY_PROFILE|file=a"))
    }

    /** An unasked PC claims nothing: the tile shows unavailable, not "off". */
    @Test
    fun theDefaultIsNotAClaimThatEverythingIsDown() {
        assertFalse(AllFullscreen().all)
        assertNull(AllFullscreen().shortfall)
    }
}
