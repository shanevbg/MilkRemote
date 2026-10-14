package com.sheinsez.mdropdx12.remote.network.vban

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Asking the mixer why, instead of inferring it from silence.
 */
class StreamDiagnosisTest {

    private fun peer(
        id: String = "me",
        authed: Boolean = true,
        state: String = "authorized",
        audio: Boolean = true,
        denied: Boolean = false,
    ) = VbanPeer(
        addr = "192.168.0.105:41234",
        deviceName = "Test Phone",
        authed = authed,
        audioOn = audio,
        framesOn = false,
        deviceId = id,
        authState = state,
        fwdSub = false,
        denied = denied,
        trusted = false,
        always = false,
    )

    /**
     * The precondition the whole table rests on: this is asked ONLY because
     * audio is already missing. So a row that looks perfect means the PC is
     * doing everything right and the packets are being lost on the way --
     * which is the one fault nothing at this end can fix.
     */
    @Test
    fun `a perfect row, while audio is missing, means the path is broken`() {
        assertEquals(StreamFault.PathBroken, StreamDiagnosis.diagnose(peer(), wantAudio = true))
        assertNull(StreamDiagnosis.explain(StreamFault.None))
    }

    /**
     * The commonest real cause. An expired peer is not listed at all -- the
     * mixer drops the row, so absence IS the diagnosis, with no timeout
     * needed.
     */
    @Test
    fun `absent from the mixer's table means the registration is gone`() {
        assertEquals(StreamFault.NotRegistered, StreamDiagnosis.diagnose(null, wantAudio = true))
        assertTrue(StreamDiagnosis.needsReauth(StreamFault.NotRegistered))
    }

    /** The cheap fix, and the whole reason this is worth more than a timeout. */
    @Test
    fun `authorized with the audio grant dropped needs one record, not a re-auth`() {
        val f = StreamDiagnosis.diagnose(peer(audio = false), wantAudio = true)
        assertEquals(StreamFault.AudioGrantLost, f)
        assertFalse("re-authenticating is the expensive answer here", StreamDiagnosis.needsReauth(f))
    }

    @Test
    fun `no audio wanted and none granted is fine`() {
        assertEquals(
            StreamFault.None,
            StreamDiagnosis.diagnose(peer(audio = false), wantAudio = false),
        )
    }

    @Test
    fun `waiting for approval is not something to retry around`() {
        val f = StreamDiagnosis.diagnose(
            peer(authed = false, state = "pending", audio = false),
            wantAudio = true,
        )
        assertEquals(StreamFault.AwaitingApproval, f)
        assertFalse(StreamDiagnosis.needsReauth(f))
    }

    /**
     * The one that must never retry. The mixer counts strikes per address, so
     * a client that keeps trying turns a sixty-second lockout into a permanent
     * one.
     */
    @Test
    fun `a refusal is terminal and is checked before anything else`() {
        for (p in listOf(
            peer(denied = true),
            peer(state = "denied", authed = false),
            // Denied AND listed as authorized would be contradictory; the
            // refusal still wins, because acting on the other reading is the
            // harmful direction.
            peer(denied = true, state = "authorized", audio = true),
        )) {
            val f = StreamDiagnosis.diagnose(p, wantAudio = true)
            assertEquals(StreamFault.Refused, f)
            assertFalse(StreamDiagnosis.needsReauth(f))
        }
    }

    /** A row created by a bare ping, with no authentication behind it. */
    @Test
    fun `listed but never authenticated is as good as absent`() {
        assertEquals(
            StreamFault.NotRegistered,
            StreamDiagnosis.diagnose(peer(authed = false, state = "none", audio = false), true),
        )
    }

    /**
     * Everything at the PC is right and nothing is arriving, so the loss is on
     * the way. One fresh registration is still worth trying, because it
     * re-resolves the address the mixer sends to -- a phone that changed
     * network mid-session is exactly this shape.
     */
    @Test
    fun `a broken path still earns one fresh registration`() {
        // Re-registering re-resolves the address and port the mixer sends to,
        // and a phone that changed network mid-session is exactly this shape.
        assertTrue(StreamDiagnosis.needsReauth(StreamFault.PathBroken))
        assertNotNull(StreamDiagnosis.explain(StreamFault.PathBroken))
    }

    // ── finding ourselves ────────────────────────────────────────────────

    @Test
    fun `we are found by device id, not by address`() {
        val rows = listOf(
            peer(id = "other-phone"),
            peer(id = "mine"),
            peer(id = "pc-pipe"),
        )
        assertEquals("mine", StreamDiagnosis.mine(rows, "mine")?.deviceId)
    }

    /**
     * Matching on address would break exactly when it matters: the source port
     * changes on every new socket and NAT rewrites the host.
     */
    @Test
    fun `a row for somebody else is not ours`() {
        assertNull(StreamDiagnosis.mine(listOf(peer(id = "other")), "mine"))
    }

    @Test
    fun `an empty id never matches an empty id`() {
        // A row with no id must not be adopted by a phone that has no id
        // either -- that pairing would make every anonymous row ours.
        assertNull(StreamDiagnosis.mine(listOf(peer(id = "")), ""))
    }

    @Test
    fun `an empty table is an absent row`() {
        assertNull(StreamDiagnosis.mine(emptyList(), "mine"))
        assertEquals(
            StreamFault.NotRegistered,
            StreamDiagnosis.diagnose(StreamDiagnosis.mine(emptyList(), "mine"), true),
        )
    }

    @Test
    fun `every fault a person can see has a sentence`() {
        for (f in StreamFault.entries.filter { it != StreamFault.None }) {
            assertNotNull(f.name, StreamDiagnosis.explain(f))
        }
    }
}
