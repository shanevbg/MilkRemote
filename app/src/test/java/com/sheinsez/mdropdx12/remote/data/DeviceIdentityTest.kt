package com.sheinsez.mdropdx12.remote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The id both PCs authorize by. Getting a second one minted is not a crash --
 * it is the phone silently needing to be approved again.
 */
class DeviceIdentityTest {

    // An invented id, not this phone's. The real one is half of a usable pair
    // against both PCs on this LAN, and this repo mirrors to GitHub.
    @Test
    fun `a usable ANDROID_ID is taken as-is`() {
        assertEquals(
            "0123456789abcdef",
            DeviceIdentity.normalize("0123456789abcdef") { "FALLBACK" },
        )
    }

    @Test
    fun `a missing ANDROID_ID falls back`() {
        assertEquals("FALLBACK", DeviceIdentity.normalize(null) { "FALLBACK" })
        assertEquals("FALLBACK", DeviceIdentity.normalize("") { "FALLBACK" })
        assertEquals("FALLBACK", DeviceIdentity.normalize("   ") { "FALLBACK" })
    }

    /**
     * A whole generation of handsets shipped reporting this one value. Taking
     * it would give every one of them the same identity, so it is worth no
     * more than a null.
     */
    @Test
    fun `the known-bad ANDROID_ID falls back`() {
        assertEquals("FALLBACK", DeviceIdentity.normalize("9774d56d682e549c") { "FALLBACK" })
    }

    @Test
    fun `a minted id is 16 hex characters and not shared`() {
        val a = DeviceIdentity.randomId()
        val b = DeviceIdentity.randomId()
        assertEquals(16, a.length)
        assertTrue(a.all { it.isDigit() || it in 'a'..'f' })
        assertNotEquals(a, b)
    }
}
