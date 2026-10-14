package com.sheinsez.mdropdx12.remote.network.vban

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Golden vectors hand-derived from the VBAN rev 13 specification, not from
 * any implementation. A byte wrong here is a stream the PC cannot read, which
 * from the phone looks like silence with no error anywhere.
 */
class VbanHeaderTest {

    @Test
    fun `the rate table is a lookup, not a formula`() {
        assertEquals(3, VbanCodec.sampleRateIndex(48000))
        assertEquals(16, VbanCodec.sampleRateIndex(44100))
        assertEquals(-1, VbanCodec.sampleRateIndex(12345))
        assertEquals(48000, VbanCodec.sampleRateFromIndex(3))
        assertEquals(44100, VbanCodec.sampleRateFromIndex(16))
        assertEquals(0, VbanCodec.sampleRateFromIndex(99))
        assertEquals(0, VbanCodec.sampleRateFromIndex(-1))
    }

    @Test
    fun `a header round-trips through bytes`() {
        val out = ByteArray(VbanCodec.HEADER_SIZE + 4)
        VbanCodec.writeHeader(
            out,
            proto = VbanCodec.PROTO_AUDIO,
            sr = 3,
            nbs = 255,
            nbc = 1,
            bit = 1,
            streamName = "mdxmixer",
            nuFrame = 0x01020304L,
        )
        assertEquals('V'.code.toByte(), out[0])
        assertEquals('B'.code.toByte(), out[1])
        assertEquals('A'.code.toByte(), out[2])
        assertEquals('N'.code.toByte(), out[3])

        val p = VbanCodec.parse(out, out.size)!!
        assertEquals(VbanCodec.PROTO_AUDIO, p.header.proto)
        assertEquals(3, p.header.sr)
        assertEquals(255, p.header.nbs)
        assertEquals(1, p.header.nbc)
        assertEquals("mdxmixer", p.header.streamName)
        assertEquals(0x01020304L, p.header.nuFrame)
        assertEquals(4, p.dataLen)
    }

    /** The sub-protocol lives in the top three bits, the rate in the low five. */
    @Test
    fun `the protocol and rate share one byte without colliding`() {
        val out = ByteArray(VbanCodec.HEADER_SIZE)
        VbanCodec.writeHeader(out, VbanCodec.PROTO_TXT, 16, 0, 0, 0x10, "s", 1L)
        val p = VbanCodec.parse(out, out.size)!!
        assertEquals(VbanCodec.PROTO_TXT, p.header.proto)
        assertEquals(16, p.header.sr)
    }

    /** Unsigned on the wire; an Int would make the top of the range negative. */
    @Test
    fun `nuFrame near the top of the range stays positive`() {
        val out = ByteArray(VbanCodec.HEADER_SIZE)
        VbanCodec.writeHeader(out, VbanCodec.PROTO_AUDIO, 3, 0, 1, 1, "s", 0xFFFFFFFEL)
        assertEquals(0xFFFFFFFEL, VbanCodec.parse(out, out.size)!!.header.nuFrame)
    }

    /**
     * Review Focus 1 — this is unauthenticated input from the network. Every
     * one of these must be refused without reading past the buffer and
     * without throwing, because a throw here ends the receive loop and takes
     * the stream down for a reason no user can act on.
     */
    @Test
    fun `malformed input is refused rather than throwing`() {
        assertNull(VbanCodec.parse(ByteArray(0), 0))
        assertNull(VbanCodec.parse(ByteArray(27), 27))

        val wrongMagic = ByteArray(VbanCodec.HEADER_SIZE)
        assertNull(VbanCodec.parse(wrongMagic, wrongMagic.size))

        // A claimed length longer than the buffer must not be believed.
        val ok = ByteArray(VbanCodec.HEADER_SIZE)
        VbanCodec.writeHeader(ok, VbanCodec.PROTO_TXT, 0, 0, 0, 0x10, "s", 1L)
        assertNull(VbanCodec.parse(ok, ok.size + 100))

        // Longer than any legal VBAN packet.
        assertNull(VbanCodec.parse(ByteArray(VbanCodec.MAX_PACKET + 1), VbanCodec.MAX_PACKET + 1))
    }

    /** A 16-byte name with no NUL must not run on into nuFrame. */
    @Test
    fun `a full-width stream name does not overrun`() {
        val out = ByteArray(VbanCodec.HEADER_SIZE)
        VbanCodec.writeHeader(
            out, VbanCodec.PROTO_TXT, 0, 0, 0, 0x10, "0123456789abcdefOVERFLOW", 7L,
        )
        val p = VbanCodec.parse(out, out.size)!!
        assertEquals("0123456789abcdef", p.header.streamName)
        assertEquals(7L, p.header.nuFrame)
    }
}
