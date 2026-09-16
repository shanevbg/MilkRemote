package com.sheinsez.mdropdx12.remote.network

import com.sheinsez.mdropdx12.remote.data.model.MixerFader
import com.sheinsez.mdropdx12.remote.data.model.displayLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MixerHideNameTest {

    private val record =
        "MIXER_FADER|ch=sonar:aux|chname=Aux|provider=sonar|id=monitoring|label=Monitoring" +
            "|vol=0.040|mute=0|health=ok|virtual=0|canMute=1|chwindowsName=|battery=-1"

    @Test
    fun aFaderCarriesItsShortNameItsHiddenFlagAndItsGroups() {
        val f = MessageParser.parseMixerFader("$record|groups=1,2|hidden=1|short=Aux P")!!
        assertEquals("Aux P", f.shortName)
        assertTrue(f.hidden)
        assertEquals(setOf(1, 2), f.groups)
    }

    /** All three are absent on an older PC, and none may be invented. */
    @Test
    fun anOlderPcOmitsThemAndNothingIsAssumed() {
        val f = MessageParser.parseMixerFader(record)!!
        assertEquals("", f.shortName)
        assertFalse(f.hidden)
        assertTrue(f.groups.isEmpty())
    }

    @Test
    fun anEmptyGroupsFieldIsNoGroups() {
        val f = MessageParser.parseMixerFader("$record|groups=|hidden=0|short=")!!
        assertTrue(f.groups.isEmpty())
        assertEquals("", f.shortName)
    }

    /**
     * A name the user chose wins outright and is never combined. Showing
     * "Aux - Aux P" would throw away the choice that was the point of naming it.
     */
    @Test
    fun aChosenNameReplacesTheChannelAndFaderEntirely() {
        val faders = listOf(
            MixerFader("sonar:aux", "Aux", "sonar", "monitoring", "Monitoring",
                4, false, "ok", false, shortName = "Aux P"),
            MixerFader("sonar:aux", "Aux", "sonar", "streaming", "Streaming",
                100, false, "ok", false),
        )
        val labels = displayLabels(faders)
        assertEquals("Aux P", labels["sonar:aux|monitoring"])
        assertEquals("Aux \u2014 Streaming", labels["sonar:aux|streaming"])
    }

    @Test
    fun theHideAndRenameVerbsAddressAFaderTheSameWayEveryOtherWriteDoes() {
        assertEquals(
            "MIXER_HIDE=sonar:aux|monitoring|1",
            CommandBuilder.mixerHide("sonar:aux", "monitoring", true),
        )
        assertEquals(
            "MIXER_HIDE=sonar:aux|monitoring|0",
            CommandBuilder.mixerHide("sonar:aux", "monitoring", false),
        )
        assertEquals("MIXER_HIDDEN", CommandBuilder.mixerHidden())
    }

    /** An empty name clears it, so the empty string must reach the wire. */
    @Test
    fun anEmptyNameIsSentRatherThanSwallowed() {
        assertEquals(
            "MIXER_FADER_NAME=sonar:aux|monitoring|Aux P",
            CommandBuilder.mixerFaderName("sonar:aux", "monitoring", "Aux P"),
        )
        assertEquals(
            "MIXER_FADER_NAME=sonar:aux|monitoring|",
            CommandBuilder.mixerFaderName("sonar:aux", "monitoring", ""),
        )
    }
}
