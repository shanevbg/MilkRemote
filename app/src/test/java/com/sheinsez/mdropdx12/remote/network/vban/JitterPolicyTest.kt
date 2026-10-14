package com.sheinsez.mdropdx12.remote.network.vban

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The playout decisions. Driven with the real stream's shape — 256 samples
 * per packet at 48 kHz, so one packet is 5.33 ms — because the thresholds
 * are in milliseconds and a different packet size moves every boundary.
 */
class JitterPolicyTest {

    private fun policy() = JitterPolicy(LatencyProfile.Resilient)

    @Test
    fun `consecutive packets just play`() {
        val p = policy()
        assertEquals(PlayoutAction.Play, p.onPacket(100L, 256, 48000).action)
        assertEquals(PlayoutAction.Play, p.onPacket(101L, 256, 48000).action)
        assertEquals(0, p.inserts)
        assertEquals(0, p.drops)
    }

    /** The first packet of a stream has nothing to compare against. */
    @Test
    fun `the first packet always plays`() {
        assertEquals(PlayoutAction.Play, policy().onPacket(999_999L, 256, 48000).action)
    }

    /** A gap inside the buffer depth is filled with silence, not a restart. */
    @Test
    fun `a small gap inserts silence for exactly the missing span`() {
        val p = policy()
        p.onPacket(100L, 256, 48000)
        val d = p.onPacket(103L, 256, 48000)
        assertEquals(PlayoutAction.InsertSilence, d.action)
        assertEquals(256 * 2, d.silenceSamples)        // 101 and 102 missing
        assertEquals(1, p.inserts)
        assertEquals(0, p.restarts)
    }

    /** Beyond the jitter depth, re-locking beats pretending. */
    @Test
    fun `a large gap restarts the stream`() {
        val p = policy()
        p.onPacket(100L, 256, 48000)
        assertEquals(PlayoutAction.Restart, p.onPacket(100_000L, 256, 48000).action)
        assertEquals(1, p.restarts)
        // And the stream re-locks from there rather than reporting the gap twice.
        assertEquals(PlayoutAction.Play, p.onPacket(100_001L, 256, 48000).action)
    }

    /** A reordered or duplicated packet is dropped, not replayed. */
    @Test
    fun `a packet from the recent past is dropped`() {
        val p = policy()
        p.onPacket(100L, 256, 48000)
        p.onPacket(101L, 256, 48000)
        assertEquals(PlayoutAction.Drop, p.onPacket(100L, 256, 48000).action)
        assertEquals(PlayoutAction.Drop, p.onPacket(101L, 256, 48000).action)   // duplicate
        assertEquals(2, p.drops)
    }

    /**
     * THE SENDER RESTARTED, and this is the bug that made a broken stream
     * unrecoverable without stopping and starting playback by hand.
     *
     * mdxmixer's audio counter is one server-wide monotonic value, so it
     * survives this phone's peer expiring and re-registering -- but it goes
     * back to zero when mdxmixer itself restarts. Read as a reorder, the
     * packet is dropped AND `last` is left alone, so the next packet is judged
     * against the same stale value and dropped too. Every packet, for ever:
     * silence indistinguishable from a healthy stream.
     */
    @Test
    fun `a sender that started over re-locks instead of dropping for ever`() {
        val p = policy()
        p.onPacket(150_000L, 256, 48000)
        // mdxmixer restarts; its counter begins again.
        val d = p.onPacket(0L, 256, 48000)
        assertEquals(PlayoutAction.Restart, d.action)
        assertEquals(1, p.restarts)
        assertEquals(0, p.drops)
        // And the stream carries on from the new numbering rather than
        // measuring everything against the old one.
        assertEquals(PlayoutAction.Play, p.onPacket(1L, 256, 48000).action)
        assertEquals(PlayoutAction.Play, p.onPacket(2L, 256, 48000).action)
        assertEquals(0, p.drops)
    }

    /**
     * The regression this replaces: without the restart case, one restarted
     * sender dropped everything that followed it.
     */
    @Test
    fun `a restart does not leave every later packet being dropped`() {
        val p = policy()
        p.onPacket(500_000L, 256, 48000)
        p.onPacket(3L, 256, 48000)
        for (n in 4L..40L) p.onPacket(n, 256, 48000)
        assertEquals("nothing after a restart should be dropped", 0, p.drops)
    }

    /** A genuine reorder is still a reorder, and must not flush the buffer. */
    @Test
    fun `a packet a few behind is a reorder, not a restart`() {
        val p = policy()
        p.onPacket(100_000L, 256, 48000)
        for (back in 1..20) {
            assertEquals(
                "behind by $back",
                PlayoutAction.Drop,
                p.onPacket(100_000L - back, 256, 48000).action,
            )
        }
        assertEquals(0, p.restarts)
    }

    @Test
    fun `the restart threshold is honoured on both sides`() {
        val n = JitterPolicy.RESTART_BEHIND_PACKETS
        val justAReorder = policy()
        justAReorder.onPacket(1_000_000L, 256, 48000)
        assertEquals(
            PlayoutAction.Drop,
            justAReorder.onPacket(1_000_000L - n, 256, 48000).action,
        )
        val aRestart = policy()
        aRestart.onPacket(1_000_000L, 256, 48000)
        assertEquals(
            PlayoutAction.Restart,
            aRestart.onPacket(1_000_000L - n - 1, 256, 48000).action,
        )
    }

    /**
     * Review Focus 2 — nuFrame is monotonic for the server's whole process
     * lifetime, so it WILL wrap. Serial arithmetic must read this as a gap of
     * three, not a four-billion backwards jump that flushes a healthy buffer
     * once every few hours of listening.
     */
    @Test
    fun `nuFrame wrapping through 2^32 is a small gap, not a backwards jump`() {
        val p = policy()
        p.onPacket(0xFFFFFFFEL, 256, 48000)
        val d = p.onPacket(1L, 256, 48000)
        assertEquals(PlayoutAction.InsertSilence, d.action)
        assertEquals(256 * 2, d.silenceSamples)        // FFFFFFFF and 0 missing
        assertEquals(0, p.restarts)
        assertEquals(0, p.drops)
    }

    /** And the packet straight after a wrap is ordinary. */
    @Test
    fun `the packet after a wrap plays normally`() {
        val p = policy()
        p.onPacket(0xFFFFFFFFL, 256, 48000)
        assertEquals(PlayoutAction.Play, p.onPacket(0L, 256, 48000).action)
    }

    /** ~85 ms of gap: inside the resilient depth, outside low-latency's. */
    @Test
    fun `the profile decides where silence stops and a restart starts`() {
        val resilient = JitterPolicy(LatencyProfile.Resilient)
        val low = JitterPolicy(LatencyProfile.LowLatency)
        resilient.onPacket(10L, 256, 48000)
        low.onPacket(10L, 256, 48000)
        assertEquals(PlayoutAction.InsertSilence, resilient.onPacket(26L, 256, 48000).action)
        assertEquals(PlayoutAction.Restart, low.onPacket(26L, 256, 48000).action)
    }

    /** A smaller packet is less audio, so the same gap in packets is a shorter one in time. */
    @Test
    fun `the threshold is in milliseconds, not packets`() {
        // float32 packs 128 samples per packet, half the int16 count.
        val p = JitterPolicy(LatencyProfile.LowLatency)
        p.onPacket(10L, 128, 48000)
        // 15 missing x 2.67 ms = 40 ms, right at the boundary and still filled.
        assertEquals(PlayoutAction.InsertSilence, p.onPacket(26L, 128, 48000).action)
    }
}
