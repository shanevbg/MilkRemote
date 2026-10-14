package com.sheinsez.mdropdx12.remote.network

import com.sheinsez.mdropdx12.remote.data.model.DisplayProfileStartup
import com.sheinsez.mdropdx12.remote.data.model.PcFeature
import com.sheinsez.mdropdx12.remote.data.model.ServerIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayProfileTest {

    @Test
    fun profilesAreAddressedByNameNotPath() {
        assertEquals("DISPLAY_PROFILE_LIST", CommandBuilder.displayProfileList())
        assertEquals("DISPLAY_PROFILE_LOAD=evening", CommandBuilder.displayProfileLoad("evening"))
        assertEquals("DISPLAY_PROFILE_DELETE=old", CommandBuilder.displayProfileDelete("old"))
    }

    /** An empty name banks a timestamp, which is how these are meant to sort. */
    @Test
    fun anEmptySaveNameIsAllowedThrough() {
        assertEquals("DISPLAY_PROFILE_SAVE=", CommandBuilder.displayProfileSave(""))
        assertEquals("DISPLAY_PROFILE_SAVE=desk", CommandBuilder.displayProfileSave("desk"))
    }

    /** No argument queries; a name sets it; an empty name switches it off. */
    @Test
    fun theStartupVerbQueriesSetsAndDisables() {
        assertEquals("DISPLAY_PROFILE_STARTUP", CommandBuilder.displayProfileStartup())
        assertEquals("DISPLAY_PROFILE_STARTUP=desk", CommandBuilder.displayProfileStartup("desk"))
        assertEquals("DISPLAY_PROFILE_STARTUP=", CommandBuilder.displayProfileStartup(""))
    }

    @Test
    fun aProfileLineCarriesItsFileAndName() {
        val p = MessageParser.parseDisplayProfile(
            "DISPLAY_PROFILE|file=2026-09-04_03-12-08.json|name=2026-09-04 03:12:08",
        )!!
        assertEquals("2026-09-04_03-12-08.json", p.file)
        assertEquals("2026-09-04 03:12:08", p.name)
    }

    /** Nothing else is a profile line, and a nameless one falls back to its file. */
    @Test
    fun onlyAProfileLineIsOneAndTheNameHasAFallback() {
        assertNull(MessageParser.parseDisplayProfile("DISPLAY_PROFILE_STARTUP|file=a|enabled=1"))
        assertNull(MessageParser.parseDisplayProfile("DISPLAY_PROFILE|name=no file"))
        assertEquals("a.json", MessageParser.parseDisplayProfile("DISPLAY_PROFILE|file=a.json")!!.name)
    }

    /**
     * Both halves matter. A profile chosen while loading is disabled looks set
     * and never happens, and that is the case a client has to be able to explain.
     */
    @Test
    fun theStartupReplyCarriesBothTheChoiceAndWhetherItApplies() {
        val on = MessageParser.parseDisplayProfileStartup(
            "DISPLAY_PROFILE_STARTUP|file=desk.json|enabled=1",
        )!!
        assertEquals("desk.json", on.file)
        assertTrue(on.enabled)
        assertFalse(on.inactive)

        val chosenButOff = MessageParser.parseDisplayProfileStartup(
            "DISPLAY_PROFILE_STARTUP|file=desk.json|enabled=0",
        )!!
        assertTrue("a profile that will never load reads as inactive", chosenButOff.inactive)

        val none = MessageParser.parseDisplayProfileStartup(
            "DISPLAY_PROFILE_STARTUP|file=|enabled=1",
        )!!
        assertTrue("enabled with nothing chosen is still nothing", none.inactive)
    }

    @Test
    fun nothingIsClaimedBeforeThePcSaysItSupportsThem() {
        assertFalse(PcFeature.DISPLAY_PROFILES in ServerIdentity())
        val v5 = MessageParser.parseIdentity(
            "VERSION=3.0.0|build=x|config=Release|arch=x64|protocol=5|pid=1|child=0" +
                "|features=mixerview,displayprofiles",
        )!!
        assertTrue(PcFeature.DISPLAY_PROFILES in v5)
    }

    @Test
    fun aStartupWithNothingChosenIsInactive() {
        assertTrue(DisplayProfileStartup().inactive)
    }
}
