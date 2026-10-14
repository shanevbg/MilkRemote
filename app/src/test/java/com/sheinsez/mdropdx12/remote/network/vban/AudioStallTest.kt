package com.sheinsez.mdropdx12.remote.network.vban

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioStallTest {

    private fun stall() = AudioStall(nudgeAfterMs = 2_000L, reauthAfterMs = 6_000L)

    @Test
    fun `nothing is wrong while audio keeps arriving`() {
        val s = stall()
        s.armed(0)
        var t = 0L
        while (t < 30_000L) {
            t += 5L                     // ~188 packets a second
            s.onAudio(t)
            assertEquals(AudioStall.Action.None, s.check(t, wantAudio = true))
        }
    }

    @Test
    fun `a short hiccup is not a stall`() {
        val s = stall()
        s.armed(0)
        s.onAudio(100)
        assertEquals(AudioStall.Action.None, s.check(1_900, wantAudio = true))
    }

    /** Two seconds of silence is about 375 packets that did not arrive. */
    @Test
    fun `two seconds of silence asks for audio again`() {
        val s = stall()
        s.onAudio(0)
        assertEquals(AudioStall.Action.Nudge, s.check(2_000, wantAudio = true))
    }

    @Test
    fun `the cheap answer is tried once, not every tick`() {
        val s = stall()
        s.onAudio(0)
        assertEquals(AudioStall.Action.Nudge, s.check(2_000, wantAudio = true))
        assertEquals(AudioStall.Action.None, s.check(2_500, wantAudio = true))
        assertEquals(AudioStall.Action.None, s.check(4_000, wantAudio = true))
    }

    @Test
    fun `silence outlasting the nudge earns a re-authentication`() {
        val s = stall()
        s.onAudio(0)
        assertEquals(AudioStall.Action.Nudge, s.check(2_000, wantAudio = true))
        assertEquals(AudioStall.Action.Reauth, s.check(6_000, wantAudio = true))
    }

    /**
     * The retry storm this has to avoid: a PC that is simply switched off must
     * not be re-authenticated against twice a second for ever.
     */
    @Test
    fun `a PC that stays away is not re-authenticated repeatedly`() {
        val s = stall()
        s.onAudio(0)
        s.check(2_000, wantAudio = true)
        assertEquals(AudioStall.Action.Reauth, s.check(6_000, wantAudio = true))
        for (t in listOf(8_000L, 10_000L, 30_000L, 300_000L)) {
            assertEquals(AudioStall.Action.None, s.check(t, wantAudio = true))
        }
    }

    @Test
    fun `audio returning re-arms both escalations`() {
        val s = stall()
        s.onAudio(0)
        s.check(2_000, wantAudio = true)
        s.check(6_000, wantAudio = true)
        s.onAudio(6_500)
        assertEquals(AudioStall.Action.Nudge, s.check(8_500, wantAudio = true))
        assertEquals(AudioStall.Action.Reauth, s.check(12_500, wantAudio = true))
    }

    /** Nobody asked for audio, so its absence is not a fault. */
    @Test
    fun `silence is fine when no audio was wanted`() {
        val s = stall()
        s.onAudio(0)
        for (t in listOf(2_000L, 6_000L, 60_000L)) {
            assertEquals(AudioStall.Action.None, s.check(t, wantAudio = false))
        }
    }

    /**
     * "No audio yet" and "audio stopped" look identical to a stopwatch with
     * nothing on it, so there has to be a starting mark.
     */
    @Test
    fun `a session that has heard nothing yet does not accuse until armed`() {
        val s = stall()
        assertEquals(AudioStall.Action.None, s.check(60_000, wantAudio = true))
        assertNull(s.silentForMs(60_000))
        s.armed(60_000)
        assertEquals(0L, s.silentForMs(60_000))
        assertEquals(AudioStall.Action.Nudge, s.check(62_000, wantAudio = true))
    }

    /**
     * THE LOOP THIS PREVENTS. Re-arming happens when a session has just
     * re-established itself, and the mark still standing is from before the
     * outage. Keeping it would measure the whole outage against a link that
     * has only just come back, escalate on the very next tick, and do it again
     * for ever.
     */
    @Test
    fun `re-arming after a recovery starts the clock again`() {
        val s = stall()
        s.onAudio(0)
        assertEquals(AudioStall.Action.Reauth, s.check(6_000, wantAudio = true))
        // The session re-authenticates and asks for audio again at t=7s.
        s.armed(7_000)
        assertEquals(0L, s.silentForMs(7_000))
        // It must now get a fresh two seconds before being accused again.
        assertEquals(AudioStall.Action.None, s.check(8_000, wantAudio = true))
        assertEquals(AudioStall.Action.Nudge, s.check(9_000, wantAudio = true))
    }

    /** An NTP step or a resume is not evidence of a stall. */
    @Test
    fun `a clock that went backwards starts again rather than reading negative`() {
        val s = stall()
        s.onAudio(10_000)
        assertEquals(AudioStall.Action.None, s.check(5_000, wantAudio = true))
        assertEquals(AudioStall.Action.Nudge, s.check(7_000, wantAudio = true))
    }

    @Test
    fun `reset clears everything`() {
        val s = stall()
        s.onAudio(0)
        s.check(6_000, wantAudio = true)
        s.reset()
        assertNull(s.silentForMs(10_000))
        assertEquals(AudioStall.Action.None, s.check(10_000, wantAudio = true))
    }

    /**
     * The escalation has to land inside the server's 10 s peer expiry to be
     * worth anything -- after it, the phone learns the peer is gone from a
     * refusal instead.
     */
    @Test
    fun `the re-auth fires before the server would have expired the peer`() {
        val s = AudioStall()
        s.onAudio(0)
        s.check(2_000, wantAudio = true)
        assertEquals(AudioStall.Action.Reauth, s.check(6_000, wantAudio = true))
        org.junit.Assert.assertTrue(6_000L < VbanSession.PEER_EXPIRY_MS + 2_000L)
    }
}
