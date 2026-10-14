package com.sheinsez.mdropdx12.remote.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A volume-key press waiting for levels to measure it against.
 *
 * It has to expire. A press held with no limit is applied whenever a reading
 * next happens to arrive -- which, with the screen off and the link down, can
 * be minutes later, and is then a level changing by itself.
 */
class HeldPressTest {

    @Test
    fun `a press is handed back once, while it is still recent`() {
        val h = HeldPress(validMs = 2_000)
        h.hold(up = true, nowMs = 10_000)
        assertEquals(true, h.take(nowMs = 10_400))
        assertNull(h.take(nowMs = 10_500))
    }

    @Test
    fun `a press that waited too long is dropped, not applied late`() {
        val h = HeldPress(validMs = 2_000)
        h.hold(up = false, nowMs = 10_000)
        assertNull(h.take(nowMs = 12_500))
        // And stays dropped: the next reading must not find it either.
        assertNull(h.take(nowMs = 12_600))
    }

    /** One press is held, the latest: a queue of them would replay a burst. */
    @Test
    fun `a newer press replaces the one being held`() {
        val h = HeldPress(validMs = 2_000)
        h.hold(up = true, nowMs = 10_000)
        h.hold(up = false, nowMs = 11_500)
        assertEquals(false, h.take(nowMs = 13_000))
    }

    @Test
    fun `nothing held is nothing to take`() {
        assertNull(HeldPress().take(nowMs = 1))
    }
}
