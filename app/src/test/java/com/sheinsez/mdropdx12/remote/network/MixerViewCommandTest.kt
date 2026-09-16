package com.sheinsez.mdropdx12.remote.network

import org.junit.Assert.assertEquals
import org.junit.Test

class MixerViewCommandTest {

    @Test
    fun theDrawnListAndTheTokenHaveTheirOwnQueries() {
        assertEquals("MIXER_VIEW", CommandBuilder.mixerView())
        assertEquals("MIXER_ORDER_REV", CommandBuilder.mixerOrderRev())
    }

    /** The token is optional, and the unguarded three-argument form still works. */
    @Test
    fun aMoveCarriesTheTokenItWasMadeAgainst() {
        assertEquals(
            "MIXER_ORDER_MOVE=sonar:aux|monitoring|-1|1a2b3c4d",
            CommandBuilder.mixerOrderMove("sonar:aux|monitoring", -1, "1a2b3c4d"),
        )
        assertEquals(
            "MIXER_ORDER_MOVE=sonar:aux|monitoring|3",
            CommandBuilder.mixerOrderMove("sonar:aux|monitoring", 3),
        )
    }

    /**
     * Comma-separated, because a bar appears in every key and a comma in none.
     * The token is mandatory here: a whole-list write from a stale view would
     * silently discard whatever changed at the PC in between.
     */
    @Test
    fun awholeArrangementIsCommaSeparatedBehindTheToken() {
        assertEquals(
            "MIXER_ORDER_SET=1a2b3c4d|sonar:aux|monitoring,sonar:media|streaming",
            CommandBuilder.mixerOrderSet(
                "1a2b3c4d",
                listOf("sonar:aux|monitoring", "sonar:media|streaming"),
            ),
        )
    }

    /**
     * Its own verb because battery is the one field that changes with no event
     * to announce it. MIXER_REFRESH would do it too, but that re-reads every
     * provider including Sonar over HTTP.
     */
    @Test
    fun batteryHasItsOwnCheapQuery() {
        assertEquals("MIXER_BATTERY", CommandBuilder.mixerBattery())
    }
}
