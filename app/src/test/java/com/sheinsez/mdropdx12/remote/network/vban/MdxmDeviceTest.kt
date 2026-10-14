package com.sheinsez.mdropdx12.remote.network.vban

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mixer's device rows, read from the mixer.
 *
 * The field order is `DeviceRecord`'s in mdxmixer's `ipc/protocol.cpp`. The
 * values are invented: an endpoint id and a Bluetooth address identify one
 * person's hardware, and this repository mirrors to GitHub.
 */
class MdxmDeviceTest {

    private val id = "{0.0.0.00000000}.{11111111-2222-3333-4444-555555555555}"

    private fun row(
        name: String = "Headphones (Test Buds)",
        alias: String = "Buds",
        flow: String = "render",
        active: Int = 1,
        battery: Int = 80,
        tail: String = "|peak=0.125",
    ) = "MDXM_DEVLVL|id=$id|name=$name|alias=$alias|flow=$flow|default=1|vol=0.42|mute=1" +
        "|active=$active|battery=$battery|seen=2026-10-11 16:07|handsfree=0|hidden=1|pinned=1" +
        "|container={aaaaaaaa-0000-0000-0000-000000000001}|bt=00:11:22:33:44:55" + tail

    @Test
    fun `a device row carries its level, its names and how it is filed`() {
        val d = MdxmCommands.parseDevice(row())!!
        assertEquals(id, d.id)
        assertEquals("Headphones (Test Buds)", d.name)
        assertEquals("Buds", d.alias)
        assertTrue(d.render)
        assertTrue(d.isDefault)
        assertEquals(0.42f, d.vol, 1e-4f)
        assertTrue(d.mute)
        assertTrue(d.active)
        assertEquals(80, d.battery)
        assertEquals("2026-10-11 16:07", d.seen)
        assertFalse(d.handsfree)
        assertTrue(d.hidden)
        assertTrue(d.pinned)
        assertEquals(0.125f, d.peak, 1e-4f)
    }

    /** -1 is the mixer saying "this device reports none", which is not 0%. */
    @Test
    fun `no battery is not an empty one`() {
        assertNull(MdxmCommands.parseDevice(row(battery = -1))!!.battery)
    }

    @Test
    fun `a capture endpoint is not a render one`() {
        assertFalse(MdxmCommands.parseDevice(row(flow = "capture"))!!.render)
    }

    @Test
    fun `an endpoint that is only paired is not active`() {
        assertFalse(MdxmCommands.parseDevice(row(active = 0))!!.active)
    }

    /** Same rule as a channel's: a meter that was not read is not silence. */
    @Test
    fun `a missing peak is unknown rather than zero`() {
        assertEquals(-1f, MdxmCommands.parseDevice(row(tail = ""))!!.peak, 1e-4f)
    }

    @Test
    fun `a row with no id is not a device`() {
        assertNull(MdxmCommands.parseDevice("MDXM_DEVLVL|name=Nameless|vol=1"))
    }

    /**
     * Verb-exact. `MDXM_DEV` is the raw endpoint list in the same reply, one
     * prefix away, and `MDXM_DEVSET` is the pushed id list: neither has a
     * level, and reading either as a row would list every device twice.
     */
    @Test
    fun `the neighbouring records are not device rows`() {
        assertNull(MdxmCommands.parseDevice("MDXM_DEV|id=$id|name=X|flow=render|active=1"))
        assertNull(MdxmCommands.parseDevice("MDXM_DEVSET|dev=$id"))
        assertNull(MdxmCommands.parseDevice("MDXM_CHAN|id=sonar:aux|name=Aux"))
    }

    // ── writes ───────────────────────────────────────────────────────────

    @Test
    fun `a device volume is positional, by endpoint id`() {
        assertEquals(
            "MDXM_DEVVOL|$id|0.4000",
            MdxmCommands.setDeviceVolume(id, 0.4f),
        )
    }

    @Test
    fun `a device volume is clamped to the range the PC accepts`() {
        assertTrue(MdxmCommands.setDeviceVolume(id, 7f).endsWith("|1.0000"))
        assertTrue(MdxmCommands.setDeviceVolume(id, -1f).endsWith("|0.0000"))
    }

    /** The same trap `setVolume` documents: "0,4000" on a comma-decimal phone. */
    @Test
    fun `a device volume uses a dot whatever the phone's locale`() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("MDXM_DEVVOL|$id|0.4000", MdxmCommands.setDeviceVolume(id, 0.4f))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun `a device mute is positional too`() {
        assertEquals("MDXM_DEVMUTE|$id|1", MdxmCommands.setDeviceMute(id, true))
        assertEquals("MDXM_DEVMUTE|$id|0", MdxmCommands.setDeviceMute(id, false))
    }
}
