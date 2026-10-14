package com.sheinsez.mdropdx12.remote.network

import com.sheinsez.mdropdx12.remote.data.model.PcFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerIdentityTest {

    private val v3 =
        "VERSION=3.0.0|build=Sep  2 2026 10:24:52|config=Release|arch=x64" +
            "|protocol=2|pid=45340|child=0|commands=224|script=49|signals=21" +
            "|features=mixerview,mixerrev,mixerpush,mixerbattery,mixernames"

    @Test
    fun aBuildSaysWhatItSupports() {
        val id = MessageParser.parseIdentity(v3)!!

        assertEquals("3.0.0", id.version)
        assertEquals(2, id.protocol)
        assertTrue(id.known)
        assertTrue(PcFeature.MIXER_VIEW in id)
        assertTrue(PcFeature.MIXER_BATTERY in id)
    }

    /**
     * The released build answers the same verb with neither a feature list nor
     * a protocol that has moved. Absence is the answer, and it must read as
     * "not declared" rather than as an error.
     */
    @Test
    fun anOlderBuildDeclaresNothingAndIsStillUnderstood() {
        val id = MessageParser.parseIdentity(
            "VERSION=2.11.0|build=Aug 27 2026 09:00:00|config=Release|arch=x64" +
                "|protocol=1|pid=1|child=0|commands=216|script=49|signals=21",
        )!!

        assertEquals("2.11.0", id.version)
        assertEquals(1, id.protocol)
        assertTrue(id.known)
        assertFalse(PcFeature.MIXER_VIEW in id)
    }

    /** Nothing else is an identity, and nothing is invented from silence. */
    @Test
    fun onlyAnIdentityLineIsOne() {
        assertNull(MessageParser.parseIdentity("MIXER_ORDER|pos=0|key=sonar:aux|monitoring"))
        assertNull(MessageParser.parseIdentity("VERSION=100"))   // the script verb, not this
    }

    /** An unknown PC has declared nothing, so nothing may be claimed about it. */
    @Test
    fun anUnaskedPcClaimsNothing() {
        val id = com.sheinsez.mdropdx12.remote.data.model.ServerIdentity()
        assertFalse(id.known)
        assertFalse(PcFeature.MIXER_VIEW in id)
    }
}
