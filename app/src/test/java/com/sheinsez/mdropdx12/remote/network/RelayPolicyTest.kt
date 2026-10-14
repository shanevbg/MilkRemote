package com.sheinsez.mdropdx12.remote.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The honest answer to "does this work through the mixer", per verb.
 *
 * Written against the real CommandBuilder surface rather than invented verbs,
 * because the value of this table is entirely in whether it matches what the
 * app actually sends.
 */
class RelayPolicyTest {

    @Test
    fun `the verb is read without its argument or its payload`() {
        assertEquals("SET_DISPLAY_MODE", RelayPolicy.verbOf("SET_DISPLAY_MODE=2,preset"))
        assertEquals("SIGNAL", RelayPolicy.verbOf("SIGNAL|NEXT_PRESET"))
        assertEquals("GET_VERSION", RelayPolicy.verbOf("GET_VERSION"))
        assertEquals("MSG", RelayPolicy.verbOf("MSG|text=hello=there|and=pipes"))
        // Case and stray whitespace must not create a second class for the
        // same verb.
        assertEquals("PRESET", RelayPolicy.verbOf(" preset=C:\\x.milk"))
    }

    @Test
    fun `the ordinary action surface travels`() {
        val actions = listOf(
            "SIGNAL|NEXT_PRESET",
            "SEND=0x1B",
            "MSG|text=hello",
            "SET_MIRROR_OPACITY=2,50",
            "SET_MIRROR_ENABLED=1",
            "SET_DISPLAY_MODE=2,preset",
            "SET_DISPLAY_WATERMARK=2,1",
            "RAISE_WINDOW",
            "LOWER_WINDOW",
            "DISPLAY_NEXT=2",
            "PRESET=C:\\presets\\x.milk",
            "MOVE_TO_DISPLAY=2",
            "COL_HUE=0.5",
            "VAR_TIME=1.0",
            "AMP|l=1.0|r=1.0",
            "MIXER_SET=personal,0.4",
            "DISPLAY_PROFILE_SAVE=evening",
            "SET_PRESET_LOCK=1",
        )
        for (c in actions) {
            assertEquals("$c should travel", RelayFitness.Action, RelayPolicy.fitnessOf(c))
            assertTrue(c, RelayPolicy.travels(c))
            assertNull(c, RelayPolicy.explain(c))
        }
    }

    /**
     * The whole reason this class separates two kinds of action.
     *
     * A lossy transport invites a retry, and a retry of either of these
     * inverts the thing the user was setting.
     */
    @Test
    fun `the two remaining toggles are marked so they are never retried`() {
        assertEquals(RelayFitness.ToggleAction, RelayPolicy.fitnessOf("TOGGLE_DEVICE_MUTE"))
        assertEquals(
            RelayFitness.ToggleAction,
            RelayPolicy.fitnessOf("SET_MIRROR_WATERMARK=1"),
        )
        // They still travel -- sending one is fine, re-sending is not.
        assertTrue(RelayPolicy.travels("TOGGLE_DEVICE_MUTE"))
    }

    /**
     * SET_MIRROR_WATERMARK is spelled like a setter and behaves like a toggle,
     * while its per-display sibling is a genuine setter. Confusing the two is
     * the mistake this pins down.
     */
    @Test
    fun `the per-display watermark setter is not the global toggle`() {
        assertEquals(
            RelayFitness.Action,
            RelayPolicy.fitnessOf("SET_DISPLAY_WATERMARK=2,1"),
        )
    }

    /**
     * A batch is still a batch -- it can arrive incomplete, and a stranger's
     * terminator can end it early -- but it TRAVELS. Each of these was
     * measured against the live relay arriving intact, terminator included.
     * Refusing them emptied the Displays and Presets screens, which is how
     * the app came to look as though nothing worked.
     */
    @Test
    fun `a batch travels, and is still marked as a batch`() {
        for (c in listOf(
            "BROWSE_PRESETS=0,60",
            "GET_CHILDREN",
            "GET_AUDIO_DEVICES",
            "GET_PRESET_FOLDERS",
            "GET_PRESET_TAGS",
        )) {
            assertEquals(c, RelayFitness.Batch, RelayPolicy.fitnessOf(c))
            assertTrue(c, RelayPolicy.travels(c))
            assertNull("a batch that arrives needs no excuse", RelayPolicy.explain(c))
        }
    }

    /**
     * The mixer listings are NOT batches that happen to be long -- the server
     * answered `MDXM_ERR|msg=toolong`, twenty-two times for MIXER_STATE,
     * because mdx12 packs them into 3000-character messages.
     */
    @Test
    fun `the mixer listings are refused by the server itself`() {
        for (c in listOf("MIXER_STATE", "MIXER_VIEW", "MIXER_ORDER")) {
            assertEquals(c, RelayFitness.TooBig, RelayPolicy.fitnessOf(c))
            assertFalse(c, RelayPolicy.travels(c))
            assertNotNull(c, RelayPolicy.explain(c))
        }
    }

    @Test
    fun `replies that cannot fit a packet are named as such`() {
        assertEquals(RelayFitness.TooBig, RelayPolicy.fitnessOf("MIXER_STATE"))
        assertEquals(RelayFitness.TooBig, RelayPolicy.fitnessOf("DIAG_MIRRORS"))
        assertEquals(RelayFitness.TooBig, RelayPolicy.fitnessOf("HELP=JSON"))
        // Answers with a whole preset file. Only reachable by typing it into
        // the raw box, which is exactly why the table has to name it.
        assertEquals(RelayFitness.TooBig, RelayPolicy.fitnessOf("GET_PRESET_SOURCE"))
        assertFalse(RelayPolicy.travels("MIXER_STATE"))
    }

    // ── a mixer that cuts a large reply into parts ───────────────────────
    //
    // Everything above is the answer against a mixer that does not, which is
    // the default: such a mixer is still out there, and it says `toolong`.

    /** The verbs whose answer depends on which mixer is asked. */
    private val LARGE = listOf(
        "DIAG_MIRRORS",
        "DISPLAY_PROFILE_LIST",
        "MIXER_STATE",
        "MIXER_VIEW",
        "MIXER_ORDER",
    )

    /**
     * ONE record, about 2.7 KB with three displays attached, so it crosses in
     * two parts. Joined it is self-describing -- it names every display -- so
     * a stranger's is as good as this phone's own.
     */
    @Test
    fun `the display listing crosses a mixer that sends parts`() {
        assertEquals(
            RelayFitness.SharedAnswer,
            RelayPolicy.fitnessOf("DIAG_MIRRORS", parts = true),
        )
        assertTrue(RelayPolicy.travels("DIAG_MIRRORS", parts = true))
        assertNull(RelayPolicy.explain("DIAG_MIRRORS", parts = true))
    }

    /**
     * These were never one large record: mdx12 packs many small rows into one
     * pipe message, and a mixer that sends parts also takes that message apart
     * and relays a row at a time. So they arrive as what they always were.
     */
    @Test
    fun `the packed listings cross it as batches`() {
        for (c in listOf("DISPLAY_PROFILE_LIST", "MIXER_STATE", "MIXER_VIEW", "MIXER_ORDER")) {
            assertEquals(c, RelayFitness.Batch, RelayPolicy.fitnessOf(c, parts = true))
            assertTrue(c, RelayPolicy.travels(c, parts = true))
            assertNull(c, RelayPolicy.explain(c, parts = true))
        }
    }

    /**
     * Refused as before, but no longer as a law of nature: this one has a
     * remedy, and the sentence has to say whose move it is.
     */
    @Test
    fun `a mixer without parts is what the refusal names`() {
        for (c in LARGE) {
            assertEquals(c, RelayFitness.TooBig, RelayPolicy.fitnessOf(c))
            assertFalse(c, RelayPolicy.travels(c))
            assertTrue(c, RelayPolicy.explain(c)!!.contains("mdxmixer"))
        }
    }

    /**
     * Parts make a large reply possible, not the relay a bulk transport. A
     * HELP=JSON listing is about thirty datagrams that all have to arrive, and
     * a preset's source comes back a line at a time with its blank lines gone
     * and nothing to say where it ends.
     */
    @Test
    fun `two replies stay too large whatever the mixer does`() {
        for (c in listOf("HELP=JSON", "GET_PRESET_SOURCE")) {
            assertEquals(c, RelayFitness.TooBig, RelayPolicy.fitnessOf(c, parts = true))
            assertFalse(c, RelayPolicy.travels(c, parts = true))
            // Nothing to update: this one is the relay, not the mixer's age.
            assertFalse(c, RelayPolicy.explain(c, parts = true)!!.contains("mdxmixer"))
        }
    }

    @Test
    fun `parts change nothing for a command that already fitted`() {
        for (c in listOf(
            "SIGNAL|NEXT_PRESET",
            "TOGGLE_DEVICE_MUTE",
            "GET_DISPLAY_MODE",
            "GET_VERSION",
            "GET_CHILDREN",
            "MIXER_SUBSCRIBE=1",
            "SOME_NEW_VERB=1",
        )) {
            assertEquals(c, RelayPolicy.fitnessOf(c), RelayPolicy.fitnessOf(c, parts = true))
        }
    }

    @Test
    fun `the saved arrangement listing is a verb this table has thought about`() {
        assertTrue(RelayPolicy.isKnown("DISPLAY_PROFILE_LIST"))
        assertTrue(RelayPolicy.isKnown("DIAG_MIRRORS"))
    }

    /**
     * The one failure that is otherwise invisible: the subscription is
     * accepted and the stream is empty, because those pushes only ever went
     * to TCP clients.
     */
    @Test
    fun `a subscription whose pushes never arrive is not success`() {
        assertEquals(
            RelayFitness.DeadSubscription,
            RelayPolicy.fitnessOf("MIXER_SUBSCRIBE=1"),
        )
        assertFalse(RelayPolicy.travels("MIXER_SUBSCRIBE=1"))
        assertTrue(RelayPolicy.explain("MIXER_SUBSCRIBE=1")!!.contains("directly"))
    }

    @Test
    fun `a small GET answers with a record anyone can read`() {
        for (c in listOf(
            "GET_DISPLAY_MODE",
            "GET_WATERMARK",
            "GET_ALWAYS_ON_TOP",
            "GET_DEVICE_VOLUME",
            "GET_IDLE_ACTIVE",
            "MIXER_ORDER_REV",
            // Two records that fit one packet, and only one PC can answer, so
            // a stranger's VERSION reply is this phone's answer too. Every
            // feature gate in the app depends on this one being reachable.
            "GET_VERSION",
        )) {
            assertEquals(c, RelayFitness.SharedAnswer, RelayPolicy.fitnessOf(c))
            assertTrue(c, RelayPolicy.travels(c))
        }
    }

    /**
     * A relayed command is judged on the outer verb. What the inner command
     * wants back is the inner command's business, and mdx12 acks `DISPLAY|`
     * either way.
     */
    @Test
    fun `a command addressed to one display travels on the outer verb`() {
        assertEquals(
            RelayFitness.Action,
            RelayPolicy.fitnessOf("DISPLAY|2|SIGNAL|NEXT_PRESET"),
        )
        // Even when the inner command would not have travelled on its own.
        assertEquals(
            RelayFitness.Action,
            RelayPolicy.fitnessOf("DISPLAY|2|MIXER_STATE"),
        )
    }

    @Test
    fun `an unknown verb is assumed to be an action rather than refused`() {
        // mdx12 grows verbs; a client that refused everything it did not
        // recognise would stop working every time the PC gained one. An
        // optimistic default costs a missed reply at worst.
        assertEquals(RelayFitness.Action, RelayPolicy.fitnessOf("SOME_NEW_VERB=1"))
        assertTrue(RelayPolicy.travels("SOME_NEW_VERB=1"))
    }
}
