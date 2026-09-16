package com.sheinsez.mdropdx12.remote.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayWatermarkTest {

    /** Per display, and readable, which is what lets it be a switch. */
    @Test
    fun everyDisplayReportsItsOwnWatermark() {
        // Device names carry backslashes on the wire; they are not needed here,
        // and the number is how every display verb addresses one anyway.
        val m = MessageParser.parseDisplayWatermark(
            "DISPLAY_WATERMARK|1=0,device=D1,ownProcess=1" +
                "|2=1,device=D2,ownProcess=0" +
                "|3=0,device=D3,ownProcess=1|END",
        )!!
        assertEquals(3, m.size)
        assertFalse(m[1]!!)
        assertTrue(m[2]!!)
        assertFalse(m[3]!!)
    }

    /** The terminator is not a display, and neither is anything unparseable. */
    @Test
    fun theTerminatorIsNotADisplay() {
        val m = MessageParser.parseDisplayWatermark("DISPLAY_WATERMARK|1=1,device=x|END")!!
        assertEquals(setOf(1), m.keys)
    }

    @Test
    fun nothingElseIsThisReply() {
        assertNull(MessageParser.parseDisplayWatermark("WATERMARK=1"))
        assertNull(MessageParser.parseDisplayWatermark("DISPLAY_WATERMARK=1|ok=0|err=x"))
    }

    /** Any display is a legal target now, not only the primary. */
    @Test
    fun theSetterAddressesOneDisplay() {
        assertEquals(
            "SET_DISPLAY_WATERMARK=3,1",
            CommandBuilder.setDisplayWatermark(3, true),
        )
        assertEquals(
            "SET_DISPLAY_WATERMARK=3,0",
            CommandBuilder.setDisplayWatermark(3, false),
        )
        assertEquals("GET_DISPLAY_WATERMARK", CommandBuilder.getDisplayWatermark())
    }
}
