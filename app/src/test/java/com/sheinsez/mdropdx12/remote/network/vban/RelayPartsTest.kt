package com.sheinsez.mdropdx12.remote.network.vban

import com.sheinsez.mdropdx12.remote.network.MessageParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A relayed record too large for one packet, as the mixer puts it on the wire:
 *
 *     MDXM_FWDP|id=<n>|i=<k>|n=<N>|msg=<slice>
 *
 * The rules are mdxmixer's `docs/ipc.md` section 12, and each test here is one
 * of the things that section says a client has to get right.
 */
class RelayPartsTest {

    private fun part(record: String) = MdxmCommands.parsePart(record)

    /**
     * What the mixer's `RelayRecords` does to a record that does not fit:
     * slices of at most [bytes] UTF-8 bytes, cut between code points.
     */
    private fun cut(record: String, id: Int, bytes: Int = 1398): List<String> {
        val slices = mutableListOf<String>()
        val slice = StringBuilder()
        var used = 0
        var i = 0
        while (i < record.length) {
            val cp = record.codePointAt(i)
            val units = Character.charCount(cp)
            val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (used + size > bytes) {
                slices += slice.toString()
                slice.clear()
                used = 0
            }
            slice.append(record, i, i + units)
            used += size
            i += units
        }
        slices += slice.toString()
        return slices.mapIndexed { k, s -> "MDXM_FWDP|id=$id|i=$k|n=${slices.size}|msg=$s" }
    }

    // ── reading one part ─────────────────────────────────────────────────

    @Test
    fun `a part names its record, its place and how many there are`() {
        assertEquals(
            RelayPart(id = "7", index = 1, count = 3, slice = "enabled=1,opacity=100"),
            part("MDXM_FWDP|id=7|i=1|n=3|msg=enabled=1,opacity=100"),
        )
    }

    /**
     * `msg=` is a raw remainder, exactly as it is on MDXM_FWDR. A slice of an
     * mdx12 record is full of `|` and `=`, and can even carry text that looks
     * like this record's own fields.
     */
    @Test
    fun `a slice is everything after the first msg marker, verbatim`() {
        val slice = "|mon1=\\\\.\\DISPLAY2,enabled=1|i=9|n=1|msg=not a field|id=99"
        assertEquals(
            RelayPart("4", 0, 2, slice),
            part("MDXM_FWDP|id=4|i=0|n=2|msg=$slice"),
        )
    }

    /** A cut can fall beside a space, and the space is part of the record. */
    @Test
    fun `a slice keeps the spaces at its ends`() {
        assertEquals(
            RelayPart("1", 0, 2, " 2560x1440,portrait=0 "),
            part("MDXM_FWDP|id=1|i=0|n=2|msg= 2560x1440,portrait=0 "),
        )
    }

    @Test
    fun `anything that is not a well formed part is not one`() {
        assertNull(part("MDXM_FWDR|msg=MIRRORS|active=0"))
        assertNull(part("MDXM_FWDPX|id=1|i=0|n=2|msg=x"))          // not a prefix match
        assertNull(part("MDXM_FWDP|i=0|n=2|msg=x"))                // no record to belong to
        assertNull(part("MDXM_FWDP|id=1|i=0|n=2"))                 // no slice
        assertNull(part("MDXM_FWDP|id=1|i=0|n=2|msg="))            // an empty one
        assertNull(part("MDXM_FWDP|id=1|i=2|n=2|msg=x"))           // counts from 0
        assertNull(part("MDXM_FWDP|id=1|i=-1|n=2|msg=x"))
        assertNull(part("MDXM_FWDP|id=1|i=0|n=0|msg=x"))
        assertNull(part("MDXM_FWDP|id=1|i=a|n=2|msg=x"))
        // A count no mixer sends. Believing it would reserve room for it.
        assertNull(part("MDXM_FWDP|id=1|i=0|n=100000|msg=x"))
    }

    /** The two verbs are one letter apart, and each must refuse the other's. */
    @Test
    fun `a part is not read as a whole relayed record`() {
        assertNull(MdxmCommands.parseRelay("MDXM_FWDP|id=1|i=0|n=2|msg=MIRRORS|active=0"))
    }

    // ── joining ──────────────────────────────────────────────────────────

    @Test
    fun `the slices of one record join in order of their index`() {
        val parts = RelayParts()
        assertNull(parts.accept(part("MDXM_FWDP|id=3|i=0|n=2|msg=MIRRORS|active=0|mon0=a,")!!, 0))
        assertEquals(
            "MIRRORS|active=0|mon0=a,enabled=1",
            parts.accept(part("MDXM_FWDP|id=3|i=1|n=2|msg=enabled=1")!!, 5),
        )
    }

    /** UDP does not promise order, and nothing is sent twice. */
    @Test
    fun `parts that arrive out of order still join in index order`() {
        val parts = RelayParts()
        assertNull(parts.accept(part("MDXM_FWDP|id=3|i=2|n=3|msg=c")!!, 0))
        assertNull(parts.accept(part("MDXM_FWDP|id=3|i=0|n=3|msg=a")!!, 1))
        assertEquals("abc", parts.accept(part("MDXM_FWDP|id=3|i=1|n=3|msg=b")!!, 2))
    }

    /** The relay is everybody's answers, so two large records can interleave. */
    @Test
    fun `two records in flight at once do not mix`() {
        val parts = RelayParts()
        assertNull(parts.accept(part("MDXM_FWDP|id=10|i=0|n=2|msg=one-")!!, 0))
        assertNull(parts.accept(part("MDXM_FWDP|id=11|i=0|n=2|msg=two-")!!, 0))
        assertEquals("two-b", parts.accept(part("MDXM_FWDP|id=11|i=1|n=2|msg=b")!!, 1))
        assertEquals("one-a", parts.accept(part("MDXM_FWDP|id=10|i=1|n=2|msg=a")!!, 1))
    }

    /** A network can deliver a datagram twice even when nobody sent it twice. */
    @Test
    fun `the same part twice is still one part`() {
        val parts = RelayParts()
        assertNull(parts.accept(part("MDXM_FWDP|id=3|i=0|n=2|msg=a")!!, 0))
        assertNull(parts.accept(part("MDXM_FWDP|id=3|i=0|n=2|msg=a")!!, 1))
        assertEquals("ab", parts.accept(part("MDXM_FWDP|id=3|i=1|n=2|msg=b")!!, 2))
    }

    /**
     * A part that never arrives costs that record. One still short after a
     * couple of seconds will stay short, and holding it would join it to the
     * parts of whatever later record is given the same id.
     */
    @Test
    fun `a record still short after two seconds is dropped`() {
        val parts = RelayParts()
        assertNull(parts.accept(part("MDXM_FWDP|id=3|i=0|n=2|msg=stale-")!!, 0))
        assertEquals(1, parts.open)
        // Its other half, far too late to be the same record.
        assertNull(parts.accept(part("MDXM_FWDP|id=3|i=1|n=2|msg=late")!!, 2_500))
        // And the late half does not complete with a fresh first half either:
        // that is a new record, and it is the one that is kept.
        assertEquals(
            "fresh-late",
            parts.accept(part("MDXM_FWDP|id=3|i=0|n=2|msg=fresh-")!!, 2_600),
        )
    }

    @Test
    fun `a record that is merely slow is kept`() {
        val parts = RelayParts()
        assertNull(parts.accept(part("MDXM_FWDP|id=3|i=0|n=2|msg=a")!!, 0))
        assertEquals("ab", parts.accept(part("MDXM_FWDP|id=3|i=1|n=2|msg=b")!!, 1_900))
    }

    /**
     * `id` starts again from zero when the mixer restarts, so an unfinished
     * record from before must not be finished by a part from after.
     */
    @Test
    fun `clearing forgets every unfinished record`() {
        val parts = RelayParts()
        assertNull(parts.accept(part("MDXM_FWDP|id=0|i=0|n=2|msg=before-")!!, 0))
        parts.clear()
        assertEquals(0, parts.open)
        assertNull(parts.accept(part("MDXM_FWDP|id=0|i=1|n=2|msg=after")!!, 10))
    }

    /** The same id with a different count is a different record. */
    @Test
    fun `an id that comes back with a different count starts again`() {
        val parts = RelayParts()
        assertNull(parts.accept(part("MDXM_FWDP|id=0|i=0|n=3|msg=old-")!!, 0))
        assertNull(parts.accept(part("MDXM_FWDP|id=0|i=0|n=2|msg=new-")!!, 10))
        assertEquals("new-b", parts.accept(part("MDXM_FWDP|id=0|i=1|n=2|msg=b")!!, 20))
    }

    /**
     * Lost parts must not pile up for ever on a bad link: the oldest
     * unfinished record makes room for a new one.
     */
    @Test
    fun `unfinished records are bounded`() {
        val parts = RelayParts(maxOpen = 2)
        assertNull(parts.accept(part("MDXM_FWDP|id=1|i=0|n=2|msg=a")!!, 0))
        assertNull(parts.accept(part("MDXM_FWDP|id=2|i=0|n=2|msg=b")!!, 1))
        assertNull(parts.accept(part("MDXM_FWDP|id=3|i=0|n=2|msg=c")!!, 2))
        assertEquals(2, parts.open)
        // The two newest are the ones still there to be finished...
        assertEquals("bz", parts.accept(part("MDXM_FWDP|id=2|i=1|n=2|msg=z")!!, 3))
        assertEquals("cz", parts.accept(part("MDXM_FWDP|id=3|i=1|n=2|msg=z")!!, 4))
        // ...and the oldest is the one that was let go.
        assertNull(parts.accept(part("MDXM_FWDP|id=1|i=1|n=2|msg=z")!!, 5))
    }

    // ── the record this exists for ───────────────────────────────────────

    /**
     * DIAG_MIRRORS is one record of about 2.7 KB with three displays attached,
     * which is two parts. Joined, it has to be exactly what would have
     * followed `MDXM_FWDR|msg=` -- which is to say, something the Displays tab
     * can read.
     */
    @Test
    fun `a display listing cut in two reads back as three displays`() {
        fun mon(n: Int, name: String, rect: String) =
            "mon$n=\\\\.\\$name,enabled=1,opacity=100,clickthru=0,watermark=0,alwaysontop=0," +
                "independent=0,skipped=0,ownProcess=0,childOwns=0,display=$rect,portrait=0," +
                "ready=1,softDis=1,hwnd=00000000010A16FA,swapsize=2560x1440,bufs=2,rtvBase=80," +
                "winrect=$rect,visible=0,layered=0,trans=0,alpha=100,styleok=1,placeok=1," +
                "path=orient,drawFI=1,presFI=1,presHr=0x00000000,ok=160918,fail=0,skip=0"
        val surf = "surf=\\\\.\\DISPLAY1,own=1,preset=PowerMilk - Equidistant.milk,renders=classic," +
            "size=2560x1440,ready=1,frames=160921,pub=0,gate=0,pipeRdy=1,need=2560x1440," +
            "seenPf=5677398,try=1664353,rec=1664353,shaders=1,fb=1,simFps=22.0,pf=160922"
        val record = "MIRRORS|active=0|independent=1|aot=0|watermark=0|" +
            List(4) { surf }.joinToString("|") +
            "|render_on=\\\\.\\DISPLAY2,visible=1,revealed=1,renderwin=(-2160,-1165)-(0,2675) 2160x3840," +
            "opacity=1.00,fs=1,clickthru=0|" +
            mon(0, "DISPLAY1", "(0,-1440)-(2560,0) 2560x1440") + "|" +
            mon(1, "DISPLAY2", "(-2160,-1165)-(0,2675) 2160x3840") + "|" +
            mon(2, "DISPLAY3", "(0,0)-(2560,1440) 2560x1440")
        val wire = cut(record, id = 41)
        assertEquals("the sample has to need parts to mean anything", 2, wire.size)

        val parts = RelayParts()
        // Second half first, because nothing promises otherwise.
        assertNull(parts.accept(part(wire[1])!!, 0))
        val joined = parts.accept(part(wire[0])!!, 3)

        assertEquals(record, joined)
        val state = MessageParser.parseMirrors(joined!!)
        assertNotNull(state)
        assertEquals(listOf(1, 2, 3), state!!.monitors.map { it.deviceNumber })
        assertEquals(2, state.renderDisplayNumber)
    }
}
