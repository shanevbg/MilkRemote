package com.sheinsez.mdropdx12.remote.network.vban

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A BEGIN..END reply, as the mixer actually puts it on the wire.
 *
 * `ChunkReplies` packs as many whole records into a datagram as will fit, so
 * the records of one datagram all carry ONE frame number. The first test here
 * is that case, and it is the ordinary one: a peer table is three short
 * records and always travels together.
 */
class ReplyAssemblerTest {

    @Test
    fun `a reply that fits one datagram completes`() {
        val a = MdxmCommands.ReplyAssembler()
        assertNull(a.accept(5L, "MDXM_BEGIN"))
        assertNull(a.accept(5L, "MDXM_VBANPEER|addr=192.168.0.105"))
        assertEquals(
            listOf("MDXM_VBANPEER|addr=192.168.0.105"),
            a.accept(5L, "MDXM_END"),
        )
        assertFalse(a.needsRequery)
    }

    @Test
    fun `a reply spanning datagrams keeps every record of each`() {
        val a = MdxmCommands.ReplyAssembler()
        assertNull(a.accept(7L, "MDXM_BEGIN"))
        assertNull(a.accept(7L, "MDXM_CHAN|id=a"))
        assertNull(a.accept(7L, "MDXM_CHAN|id=b"))
        assertNull(a.accept(8L, "MDXM_DEVLVL|id=c"))
        assertNull(a.accept(8L, "MDXM_DEVLVL|id=d"))
        assertEquals(
            listOf("MDXM_CHAN|id=a", "MDXM_CHAN|id=b", "MDXM_DEVLVL|id=c", "MDXM_DEVLVL|id=d"),
            a.accept(9L, "MDXM_END"),
        )
    }

    /**
     * A relayed record is pushed from another thread on the mixer, numbered
     * from the same per-peer counter, and can land between two datagrams of a
     * reply. The session routes it elsewhere before the assembler is shown
     * it, so the frames the assembler saw RECORDS on skip one -- with nothing
     * lost. The frame has to be counted where the datagram is, not where a
     * record happened to be handed over.
     */
    @Test
    fun `a datagram that carried something else is not a gap`() {
        val a = MdxmCommands.ReplyAssembler()
        a.onDatagram(7L)
        assertNull(a.accept("MDXM_BEGIN"))
        assertNull(a.accept("MDXM_CHAN|id=a"))
        a.onDatagram(8L)                    // an MDXM_FWDR, taken before this
        a.onDatagram(9L)
        assertEquals(listOf("MDXM_CHAN|id=a"), a.accept("MDXM_END"))
        assertFalse(a.needsRequery)
    }

    /** The counter is 32 bits on the wire; 0 follows 0xFFFFFFFF. */
    @Test
    fun `the frame counter wrapping is not a gap`() {
        val a = MdxmCommands.ReplyAssembler()
        a.onDatagram(0xFFFFFFFFL)
        assertNull(a.accept("MDXM_BEGIN"))
        a.onDatagram(0L)
        assertEquals(emptyList<String>(), a.accept("MDXM_END"))
        assertFalse(a.needsRequery)
    }

    /** The guard the same-frame case must not weaken. */
    @Test
    fun `a lost datagram still discards the partial`() {
        val a = MdxmCommands.ReplyAssembler()
        assertNull(a.accept(7L, "MDXM_BEGIN"))
        assertNull(a.accept(7L, "MDXM_CHAN|id=a"))
        assertNull(a.accept(9L, "MDXM_DEVLVL|id=c"))        // 8 lost
        assertTrue(a.needsRequery)
        assertNull(a.accept(9L, "MDXM_END"))
    }
}
