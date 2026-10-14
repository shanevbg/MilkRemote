package com.sheinsez.mdropdx12.remote.network.vban

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MdxmCommandsTest {

    // Invented fixtures. A real PIN and a real device id are a usable pair
    // against the mixer on this LAN, and this repo mirrors to GitHub.
    @Test
    fun `auth carries pin, device and name as keyed fields`() {
        assertEquals(
            "MDXM_AUTH|pin=0000|device=0123456789abcdef|name=Test Phone",
            MdxmCommands.auth("0000", "0123456789abcdef", "Test Phone"),
        )
    }

    /** Requests are single-packet; free text truncates rather than overflowing. */
    @Test
    fun `an over-long device name is truncated to fit one packet`() {
        val cmd = MdxmCommands.auth("0000", "id", "N".repeat(4000))
        assertTrue(cmd.toByteArray(Charsets.UTF_8).size <= VbanCodec.MAX_DATA)
        assertTrue(cmd.startsWith("MDXM_AUTH|pin=0000|device=id|name=NNN"))
    }

    /**
     * The query is a BARE verb. MDXM_VBANSTATE is the reply record, and
     * sending it as a verb earns `MDXM_ERR|msg=unknown verb` -- confirmed
     * against a running server, which is why this test exists at all.
     */
    @Test
    fun `the state query carries no keyed fields`() {
        assertEquals("MDXM_VBAN", MdxmCommands.vbanQuery())
        assertEquals(false, MdxmCommands.vbanQuery().contains("="))
    }

    /**
     * A PERCENT, 0..6400, which is what the wire carries. The first version
     * sent `gain=0.50` -- half a percent, i.e. silence -- and the server
     * answered a healthy state record saying gain=0, so the symptom was a
     * working connection with no sound (mdxmixer forgejo#20).
     */
    @Test
    fun `gain is an integer percent, absolute and clamped`() {
        assertEquals("MDXM_VBAN|gain=100", MdxmCommands.gain(100))
        assertEquals("MDXM_VBAN|gain=50", MdxmCommands.gain(50))
        // 100 is unity, not a ceiling: the personal mix runs at a few percent
        // of full scale, so the wire goes to 6400.
        assertEquals("MDXM_VBAN|gain=6400", MdxmCommands.gain(6400))
        assertEquals("MDXM_VBAN|gain=6400", MdxmCommands.gain(99999))
        assertEquals("MDXM_VBAN|gain=0", MdxmCommands.gain(-5))
        // No decimal point can reach the wire, whatever the phone's locale.
        assertEquals(false, MdxmCommands.gain(50).contains("."))
        assertEquals(false, MdxmCommands.gain(50).contains(","))
    }

    @Test
    fun `audio and frames are per-peer flags`() {
        assertEquals("MDXM_VBAN|audio=0", MdxmCommands.audio(false))
        assertEquals("MDXM_VBAN|audio=1", MdxmCommands.audio(true))
        assertEquals("MDXM_VBAN|frames=1|fps=2.0", MdxmCommands.frames(true, 2.0f))
        assertEquals("MDXM_VBAN|frames=0", MdxmCommands.frames(false, null))
    }

    @Test
    fun `auth state maps every documented outcome`() {
        assertEquals(AuthState.Ok, MdxmCommands.parseAuthState("MDXM_AUTHSTATE|ok=1"))
        assertEquals(AuthState.Pending, MdxmCommands.parseAuthState("MDXM_AUTHSTATE|pending=1"))
        assertEquals(AuthState.BadPin, MdxmCommands.parseAuthState("MDXM_AUTHSTATE|ok=0|err=badpin"))
        assertEquals(AuthState.Locked, MdxmCommands.parseAuthState("MDXM_AUTHSTATE|ok=0|err=locked"))
        assertEquals(AuthState.Denied, MdxmCommands.parseAuthState("MDXM_AUTHSTATE|ok=0|err=denied"))
        assertNull(MdxmCommands.parseAuthState("MDXM_VBANSTATE|on=1"))
    }

    /**
     * The exact record a live mdxmixer sent back, pasted rather than
     * invented. Note `gain=100` -- the server reports a PERCENT, so reading
     * it as a 0..1 scalar would put the slider at 100x and every gain the
     * phone echoed back would be wrong.
     */
    @Test
    fun `the live state record parses, and gain is a percent`() {
        val s = MdxmCommands.parseVbanState(
            "MDXM_VBANSTATE|on=1|emitting=1|port=6980|name=mdxmixer|peers=1" +
                "|source=personal|format=i16|gain=100|fps=2|open=1|always=1" +
                "|alwaysframes=0|target=|sent=2347|starved=28|dropped=0|depthms=6" +
                "|behindms=0|srclatencyms=32|framessent=0|authpending=0|loopback=1|error=",
        )!!
        assertTrue(s.on && s.emitting)
        assertEquals(100, s.gainPercent)         // the wire's own unit, unconverted
        assertEquals("personal", s.source)
        assertEquals("i16", s.format)
        assertEquals(1, s.peers)
        assertEquals(2347L, s.sent)
        assertEquals(28L, s.starved)
        assertEquals(32, s.srcLatencyMs)
        assertEquals(false, s.authPending)
        assertTrue(s.trustLoopback)
    }

    @Test
    fun `a half-gain record reads as fifty percent`() {
        assertEquals(50, MdxmCommands.parseVbanState("MDXM_VBANSTATE|on=1|gain=50")!!.gainPercent)
        // Above unity is ordinary here, not an error to clamp away.
        assertEquals(400, MdxmCommands.parseVbanState("MDXM_VBANSTATE|gain=400")!!.gainPercent)
    }

    /**
     * Review Focus 5 -- a nuFrame gap mid-reply must discard the partial, not
     * hand up a half-assembled snapshot that looks plausible.
     */
    @Test
    fun `a gap inside BEGIN-END discards the partial and asks to re-query`() {
        val a = MdxmCommands.ReplyAssembler()
        assertNull(a.accept(10L, "MDXM_BEGIN"))
        assertNull(a.accept(11L, "MDXM_VBANPEER|addr=192.168.0.105"))
        assertNull(a.accept(13L, "MDXM_VBANPEER|addr=192.168.0.106"))   // 12 lost
        assertTrue(a.needsRequery)

        a.reset()
        assertNull(a.accept(20L, "MDXM_BEGIN"))
        assertNull(a.accept(21L, "MDXM_VBANPEER|addr=x"))
        assertEquals(listOf("MDXM_VBANPEER|addr=x"), a.accept(22L, "MDXM_END"))
    }
}
