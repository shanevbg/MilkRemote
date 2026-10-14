package com.sheinsez.mdropdx12.remote.network.vban

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the mixer is where the Mixer screen reads from, and when it can be
 * asked. Two different questions, and the gap between them is the point.
 */
class MdxmMixerSourceTest {

    @Test
    fun `an authorized session can be asked`() {
        assertTrue(MdxmMixer.askable(VbanSessionState.Streaming))
        assertFalse(MdxmMixer.askable(VbanSessionState.Pinging))
        assertFalse(MdxmMixer.askable(VbanSessionState.AuthPending))
        assertFalse(MdxmMixer.askable(VbanSessionState.Idle))
        assertFalse(MdxmMixer.askable(VbanSessionState.Lost(LostReason.Denied)))
    }

    /**
     * A session re-authenticates for a second or two after a roam or a socket
     * rebuild. If the mixer stopped being the source for that long the screen
     * would empty itself, fall back to asking the visualizer, and draw that
     * path's refusal -- over a list that was correct a moment ago and will be
     * again.
     */
    @Test
    fun `a session that is coming back up is still the source`() {
        assertTrue(MdxmMixer.isSource(VbanSessionState.Streaming))
        assertTrue(MdxmMixer.isSource(VbanSessionState.Pinging))
        assertTrue(MdxmMixer.isSource(VbanSessionState.AuthPending))
    }

    /** Somebody who has never run the mixer reads from the visualizer, as before. */
    @Test
    fun `no session, or a refused one, is not the source`() {
        assertFalse(MdxmMixer.isSource(VbanSessionState.Idle))
        assertFalse(MdxmMixer.isSource(VbanSessionState.Lost(LostReason.Stopped)))
        assertFalse(MdxmMixer.isSource(VbanSessionState.Lost(LostReason.Denied)))
        assertFalse(MdxmMixer.isSource(VbanSessionState.Lost(LostReason.BadPin)))
    }
}
