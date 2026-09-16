package com.sheinsez.mdropdx12.remote.network

import com.sheinsez.mdropdx12.remote.data.model.DisplayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GET_DISPLAY_MODE, which replaced inferring the tier from GET_CHILDREN
 * membership. The inference could not see a display holding its own preset in
 * the parent process: it runs no child, so it read as a mirror.
 */
class DisplayModeTest {

    private val live =
        "DISPLAY_MODE|1=child,device=D1,auto=0,independent=0" +
            "|2=mirror,device=D2,auto=0,independent=1" +
            "|3=preset,device=D3,auto=1,independent=0|END"

    @Test
    fun `reads every tier by display number`() {
        val modes = MessageParser.parseDisplayModes(live)!!
        assertEquals(DisplayMode.Child, modes[1]?.mode)
        assertEquals(DisplayMode.Mirror, modes[2]?.mode)
        assertEquals(DisplayMode.Preset, modes[3]?.mode)
    }

    @Test
    fun `auto marks the display the cost gate promoted`() {
        val modes = MessageParser.parseDisplayModes(live)!!
        assertEquals(false, modes[1]?.auto)
        assertTrue(modes[3]!!.auto)
    }

    @Test
    fun `independent rides along per display`() {
        val modes = MessageParser.parseDisplayModes(live)!!
        assertTrue(modes[2]!!.independent)
        assertEquals(false, modes[1]?.independent)
    }

    /** The PC's help says "own" is accepted for preset, so it may say either. */
    @Test
    fun `own is a synonym for preset`() {
        val modes = MessageParser.parseDisplayModes("DISPLAY_MODE|1=own,device=D1|END")!!
        assertEquals(DisplayMode.Preset, modes[1]?.mode)
    }

    @Test
    fun `another message is not one of these`() {
        assertNull(MessageParser.parseDisplayModes("DISPLAY_WATERMARK|1=0|END"))
    }

    /**
     * Child and Preset differ in where they render, not in what is on the
     * wall, so the segmented control shows one tier and the isolation switch
     * carries the difference.
     */
    @Test
    fun `child folds under the own-preset tier`() {
        assertEquals(DisplayMode.Preset, DisplayMode.Child.tier)
        assertEquals(DisplayMode.Preset, DisplayMode.Preset.tier)
        assertEquals(DisplayMode.Mirror, DisplayMode.Mirror.tier)
        assertTrue(DisplayMode.Child.ownPreset)
        assertTrue(DisplayMode.Preset.ownPreset)
        assertEquals(false, DisplayMode.Mirror.ownPreset)
        assertEquals(3, DisplayMode.TIERS.size)
    }

    /**
     * The relay form, kept for a PC without `displaytiers`. DISPLAY_NEXT= used
     * to answer no_surface on a child-owned display, and the fix for that
     * shipped in the same merge as the tiers (mdropdx12 forgejo#236).
     */
    @Test
    fun `a child is stepped through the relay`() {
        assertEquals(
            "DISPLAY|3|SIGNAL|NEXT_PRESET",
            CommandBuilder.childPresetStep(3, next = true),
        )
        assertEquals(
            "DISPLAY|3|SIGNAL|PREV_PRESET",
            CommandBuilder.childPresetStep(3, next = false),
        )
    }

    @Test
    fun `raising one display goes through the relay too`() {
        assertEquals("DISPLAY|2|RAISE_WINDOW", CommandBuilder.raiseDisplayWindow(2))
        assertEquals("RAISE_WINDOW", CommandBuilder.raiseWindow())
    }

    /** The lower half, which the PC gained with `windowzorder`. */
    @Test
    fun `lowering has the same two forms`() {
        assertEquals("LOWER_WINDOW", CommandBuilder.lowerWindow())
        assertEquals("DISPLAY|2|LOWER_WINDOW", CommandBuilder.lowerDisplayWindow(2))
    }

    @Test
    fun `the mode argument is the PC's own token`() {
        assertEquals("SET_DISPLAY_MODE=2,preset", CommandBuilder.setDisplayMode(2, DisplayMode.Preset))
        assertEquals("SET_DISPLAY_MODE=2,child", CommandBuilder.setDisplayMode(2, DisplayMode.Child))
    }
}
