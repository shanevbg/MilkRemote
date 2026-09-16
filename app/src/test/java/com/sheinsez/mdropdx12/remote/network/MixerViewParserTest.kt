package com.sheinsez.mdropdx12.remote.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MixerViewParserTest {

    /**
     * `key=` is the LAST field of any record carrying one, and a key is
     * "<channel>|<fader>" -- it contains the separator. Splitting the line on
     * '|' truncates every key at its channel, silently, which is the bug the PC
     * side hit twice while this was written.
     */
    @Test
    fun aViewRowTakesItsKeyAsTheRestOfTheLine() {
        val row = MessageParser.parseMixerViewRow(
            "MIXER_VIEW|pos=0|order=24|hidden=0|pinned=1|" +
                "key=endpoint:{0.0.0.00000000}.{d2ae6268-1e31-42d5-a548-781cfad55ca4}|main",
        )!!

        assertEquals(0, row.pos)
        assertEquals(24, row.order)
        assertFalse(row.hidden)
        assertTrue(row.pinned)
        assertEquals(
            "endpoint:{0.0.0.00000000}.{d2ae6268-1e31-42d5-a548-781cfad55ca4}|main",
            row.key,
        )
    }

    /** A hidden row is reported rather than omitted, and carries pos=-1. */
    @Test
    fun aHiddenRowIsReportedWithNoDrawnPosition() {
        val row = MessageParser.parseMixerViewRow(
            "MIXER_VIEW|pos=-1|order=3|hidden=1|pinned=0|key=endpoint:{a}|main",
        )!!

        assertEquals(-1, row.pos)
        assertTrue(row.hidden)
        assertEquals("endpoint:{a}|main", row.key)
    }

    @Test
    fun aRowWithoutAKeyIsNotARow() {
        assertNull(MessageParser.parseMixerViewRow("MIXER_VIEW|pos=0|order=0|hidden=0|pinned=0"))
        assertNull(MessageParser.parseMixerViewRow("MIXER_VIEW_END|count=25|rev=1a2b3c4d"))
        assertNull(MessageParser.parseMixerViewRow("MIXER_ORDER|pos=0|key=sonar:aux|monitoring"))
    }

    @Test
    fun theTerminatorCarriesTheTokenAndTheThreeViewFlags() {
        val end = MessageParser.parseMixerViewEnd(
            "MIXER_VIEW_END|count=25|shown=17|hidden=8|rev=1a2b3c4d" +
                "|sortUnmutedFirst=0|pinFailoverDevices=1|showVirtualEndpoints=0",
        )!!

        assertEquals("1a2b3c4d", end.rev)
        assertFalse(end.sortUnmutedFirst)
        assertTrue(end.pinFailoverDevices)
        assertFalse(end.showVirtualEndpoints)
        assertTrue(end.rows.isEmpty())
    }

    /** An older PC answers MIXER_VIEW with nothing, so nothing must be invented. */
    @Test
    fun theTerminatorIsRequiredBeforeAnythingIsBelieved() {
        assertNull(MessageParser.parseMixerViewEnd("MIXER_VIEW|pos=0|order=0|hidden=0|pinned=0|key=a|b"))
        assertNull(MessageParser.parseMixerViewEnd("MIXER_ERR|reason=unknown"))
    }

    /** The push carries the token alone -- never the list. */
    @Test
    fun theChangePushCarriesOnlyTheToken() {
        assertEquals("9f04c7b1", MessageParser.parseMixerRev("MIXER_VIEW_CHANGED|rev=9f04c7b1"))
    }

    @Test
    fun aRefusedMoveCarriesTheCurrentToken() {
        assertEquals("9f04c7b1", MessageParser.parseMixerRev("MIXER_ERR|reason=stale|rev=9f04c7b1"))
        assertTrue(MessageParser.isMixerStale("MIXER_ERR|reason=stale|rev=9f04c7b1"))
        assertFalse(MessageParser.isMixerStale("MIXER_ERR|reason=unknown_fader"))
    }

    @Test
    fun theTokenIsReadFromEveryRecordThatCarriesOne() {
        assertEquals("1a2b3c4d", MessageParser.parseMixerRev("MIXER_ORDER_REV|rev=1a2b3c4d|count=27"))
        assertEquals("1a2b3c4d", MessageParser.parseMixerRev("MIXER_ORDER_END|count=27|rev=1a2b3c4d"))
        assertNull(MessageParser.parseMixerRev("MIXER_ORDER|pos=0|key=sonar:aux|monitoring"))
    }

    /**
     * A move reply now carries rev BEFORE key. Taken from after "|key=" the old
     * way, a token appended after it would have been read as part of the fader
     * id -- so the field order matters and the existing parser must still work.
     */
    @Test
    fun aMoveReplyStillParsesWithTheTokenInFrontOfTheKey() {
        val (pos, key) = MessageParser.parseMixerOrder(
            "MIXER_ORDER|pos=7|rev=1a2b3c4d|key=sonar:aux|monitoring",
        )!!
        assertEquals(7, pos)
        assertEquals("sonar:aux|monitoring", key)
        assertEquals("1a2b3c4d", MessageParser.parseMixerRev(
            "MIXER_ORDER|pos=7|rev=1a2b3c4d|key=sonar:aux|monitoring",
        ))
    }
}
