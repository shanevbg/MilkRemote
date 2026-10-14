package com.sheinsez.mdropdx12.remote.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the table has actually been reasoned about, as opposed to defaulted.
 *
 * `fitnessOf` is optimistic on purpose -- an unlisted verb is assumed to be an
 * ordinary action, so the app keeps working when the PC grows one. `isKnown`
 * is the honest counterpart, for the two places a PERSON types the verb.
 */
class RelayPolicyKnownTest {

    @Test
    fun `the app's own action surface is known`() {
        for (c in listOf(
            "SIGNAL|NEXT_PRESET",
            "SET_IDLE_ACTIVE=1",
            "SET_DISPLAY_MODE=2,preset",
            "RAISE_WINDOW",
            "MIXER_SET=personal,0.4",
            "PRESET=C:\\x.milk",
            "DISPLAY|2|SIGNAL|NEXT_PRESET",
        )) {
            assertTrue(c, RelayPolicy.isKnown(c))
        }
    }

    @Test
    fun `the named awkward cases are known, because being awkward is why they are named`() {
        for (c in listOf(
            "MIXER_STATE",
            "DIAG_MIRRORS",
            "BROWSE_PRESETS=0,60",
            "MIXER_SUBSCRIBE=1",
            "GET_PRESET_SOURCE",
            "TOGGLE_DEVICE_MUTE",
            "GET_VERSION",
        )) {
            assertTrue(c, RelayPolicy.isKnown(c))
        }
    }

    @Test
    fun `a verb nobody here has thought about is not vouched for`() {
        for (c in listOf(
            "SHADER_GLSL=void main(){}",
            "SHADER_IMPORT={}",
            "APPLY_PRESET_SOURCE=C:\\x.milk",
            "SOME_NEW_VERB=1",
            "PRESET_SOURCE",
        )) {
            assertFalse(c, RelayPolicy.isKnown(c))
        }
    }

    /**
     * The one that matters, and the reason the action list is named rather
     * than inferred from a prefix: `GET_` is not reliably a small query, so a
     * prefix rule would have vouched for every invented GET_ verb.
     */
    @Test
    fun `an invented GET verb is not known just because of its prefix`() {
        assertFalse(RelayPolicy.isKnown("GET_SOMETHING_INVENTED"))
        // ...while still being routed optimistically, which is the split.
        assertEquals(RelayFitness.SharedAnswer, RelayPolicy.fitnessOf("GET_SOMETHING_INVENTED"))
        assertTrue(RelayPolicy.travels("GET_SOMETHING_INVENTED"))
    }
}
