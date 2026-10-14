package com.sheinsez.mdropdx12.remote.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A level the user has just set, held over what the PC last said.
 *
 * The step buttons compute from the number on screen, and the mixer is polled.
 * A snapshot asked for before a press and answered after it carries the level
 * from BEFORE the press -- so without a hold the row steps back, and the next
 * press is measured from the stale number and one step is lost.
 */
class PendingWritesTest {

    private fun fader(id: String, percent: Int, muted: Boolean = false) = MixerFader(
        channelId = id, channelName = id, provider = "mdxm", faderId = "monitoring",
        label = "Personal", volumePercent = percent, muted = muted, health = "ok",
        virtual = false,
    )

    private val key = "sonar:aux|monitoring"

    @Test
    fun `a level just written wins over an older reading`() {
        val w = PendingWrites(holdMs = 1500)
        w.volume(key, 40, nowMs = 1_000)
        val out = w.over(listOf(fader("sonar:aux", 30)), nowMs = 1_200)
        assertEquals(40, out.single().volumePercent)
    }

    @Test
    fun `once the hold has run out the PC's reading stands`() {
        val w = PendingWrites(holdMs = 1500)
        w.volume(key, 40, nowMs = 1_000)
        val out = w.over(listOf(fader("sonar:aux", 30)), nowMs = 2_600)
        assertEquals(30, out.single().volumePercent)
    }

    /**
     * The hold ends the moment the PC agrees. Otherwise a change made AT the
     * PC a moment later would be painted over with the phone's own number
     * until the clock ran out.
     */
    @Test
    fun `a reading that agrees ends the hold early`() {
        val w = PendingWrites(holdMs = 1500)
        w.volume(key, 40, nowMs = 1_000)
        w.over(listOf(fader("sonar:aux", 40)), nowMs = 1_100)
        val out = w.over(listOf(fader("sonar:aux", 55)), nowMs = 1_200)
        assertEquals(55, out.single().volumePercent)
    }

    @Test
    fun `a second press restarts the hold`() {
        val w = PendingWrites(holdMs = 1500)
        w.volume(key, 40, nowMs = 1_000)
        w.volume(key, 41, nowMs = 2_000)
        val out = w.over(listOf(fader("sonar:aux", 30)), nowMs = 3_000)
        assertEquals(41, out.single().volumePercent)
    }

    /** A level and a mute are separate writes, and one must not vouch for the other. */
    @Test
    fun `a held level does not hold the mute`() {
        val w = PendingWrites(holdMs = 1500)
        w.volume(key, 40, nowMs = 1_000)
        val out = w.over(listOf(fader("sonar:aux", 30, muted = true)), nowMs = 1_100)
        assertEquals(40, out.single().volumePercent)
        assertTrue(out.single().muted)
    }

    @Test
    fun `a mute just written wins over an older reading`() {
        val w = PendingWrites(holdMs = 1500)
        w.mute(key, true, nowMs = 1_000)
        val out = w.over(listOf(fader("sonar:aux", 30, muted = false)), nowMs = 1_100)
        assertTrue(out.single().muted)
        assertEquals(30, out.single().volumePercent)
    }

    @Test
    fun `other faders are left exactly as read`() {
        val w = PendingWrites(holdMs = 1500)
        w.volume(key, 40, nowMs = 1_000)
        val other = fader("sonar:game", 77)
        val out = w.over(listOf(fader("sonar:aux", 30), other), nowMs = 1_100)
        assertEquals(other, out[1])
    }

    /** A new session's readings are not the old one's to overrule. */
    @Test
    fun `clearing forgets every hold`() {
        val w = PendingWrites(holdMs = 1500)
        w.volume(key, 40, nowMs = 1_000)
        w.mute(key, true, nowMs = 1_000)
        w.clear()
        val out = w.over(listOf(fader("sonar:aux", 30)), nowMs = 1_100)
        assertEquals(30, out.single().volumePercent)
        assertFalse(out.single().muted)
    }
}
