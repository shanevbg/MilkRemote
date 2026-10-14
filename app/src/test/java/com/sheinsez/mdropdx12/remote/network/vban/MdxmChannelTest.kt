package com.sheinsez.mdropdx12.remote.network.vban

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mixer's own channels, read from the mixer.
 *
 * Records here are copied from a live MDXM_STATE rather than invented, since
 * the whole point of reading this surface is that it is the one the data
 * actually comes from.
 */
class MdxmChannelTest {

    @Test
    fun `a channel carries both mixes independently`() {
        val c = MdxmCommands.parseChannel(
            "MDXM_CHAN|id=sonar:game|name=Game|health=ok|pvol=0.13|pmute=1" +
                "|svol=1|smute=0|eq=0|peak=0|idle=0",
        )!!
        assertEquals("sonar:game", c.id)
        assertEquals("Game", c.name)
        assertTrue(c.healthy)
        // The pair that matters: muted for the owner, live to the stream.
        assertEquals(0.13f, c.personalVol, 1e-4f)
        assertTrue(c.personalMute)
        assertEquals(1f, c.streamingVol, 1e-4f)
        assertFalse(c.streamingMute)
    }

    /**
     * -1 is UNKNOWN, not silent. A channel that is idle, healthy and
     * reporting -1 all at once is a documented state, and drawing it as
     * silent would be a different claim.
     */
    @Test
    fun `an unknown peak stays unknown`() {
        val c = MdxmCommands.parseChannel(
            "MDXM_CHAN|id=sonar:masters|name=Master|health=ok|pvol=1|pmute=0" +
                "|svol=1|smute=0|eq=0|peak=-1|idle=0",
        )!!
        assertEquals(-1f, c.peak, 1e-4f)
    }

    /** And a record with no peak at all must not read as silence either. */
    @Test
    fun `a missing peak is unknown rather than zero`() {
        val c = MdxmCommands.parseChannel(
            "MDXM_CHAN|id=x|name=X|health=ok|pvol=1|pmute=0|svol=1|smute=0|eq=0",
        )!!
        assertEquals(-1f, c.peak, 1e-4f)
    }

    @Test
    fun `an idle channel is flagged`() {
        val c = MdxmCommands.parseChannel(
            "MDXM_CHAN|id=sonar|name=Sonar|health=ok|pvol=1|pmute=0|svol=1" +
                "|smute=0|eq=0|peak=-1|idle=1",
        )!!
        assertTrue(c.idle)
        assertTrue("idle is not a fault", c.healthy)
    }

    @Test
    fun `a bad channel is not healthy`() {
        val c = MdxmCommands.parseChannel(
            "MDXM_CHAN|id=x|name=X|health=bad|pvol=1|pmute=0|svol=1|smute=0|eq=0|peak=0",
        )!!
        assertFalse(c.healthy)
    }

    @Test
    fun `a channel with no id is not a channel`() {
        assertNull(MdxmCommands.parseChannel("MDXM_CHAN|name=Nameless"))
    }

    /** Verb-exact, like every other parser here. */
    @Test
    fun `other records are not channels`() {
        assertNull(MdxmCommands.parseChannel("MDXM_CHANS"))
        assertNull(MdxmCommands.parseChannel("MDXM_DEV|id=x|name=X"))
        assertNull(MdxmCommands.parseChannel("MDXM_BEGIN"))
    }

    // ── writes ───────────────────────────────────────────────────────────

    @Test
    fun `volume is positional, and names which mix`() {
        assertEquals(
            "MDXM_SET|sonar:game|personal|0.4000",
            MdxmCommands.setVolume("sonar:game", MdxmMix.Personal, 0.4f),
        )
        assertEquals(
            "MDXM_SET|sonar:game|streaming|1.0000",
            MdxmCommands.setVolume("sonar:game", MdxmMix.Streaming, 1f),
        )
    }

    @Test
    fun `volume is clamped to the range the PC accepts`() {
        assertTrue(MdxmCommands.setVolume("x", MdxmMix.Personal, 9f).endsWith("|1.0000"))
        assertTrue(MdxmCommands.setVolume("x", MdxmMix.Personal, -3f).endsWith("|0.0000"))
    }

    /**
     * Formatted in the root locale. A phone set to a comma-decimal locale
     * would otherwise send "0,4000", which the PC parses as a different
     * number or not at all -- the same class of bug as sending gain as a
     * scalar when the wire wanted a percent.
     */
    @Test
    fun `volume uses a dot whatever the phone's locale`() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals(
                "MDXM_SET|x|personal|0.4000",
                MdxmCommands.setVolume("x", MdxmMix.Personal, 0.4f),
            )
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun `mute names its mix too`() {
        assertEquals(
            "MDXM_MUTE|sonar:chatCapture|personal|1",
            MdxmCommands.setMute("sonar:chatCapture", MdxmMix.Personal, true),
        )
        assertEquals(
            "MDXM_MUTE|sonar:chatCapture|streaming|0",
            MdxmCommands.setMute("sonar:chatCapture", MdxmMix.Streaming, false),
        )
    }

    @Test
    fun `the state query is a bare verb`() {
        assertEquals("MDXM_STATE", MdxmCommands.stateQuery())
    }
}
