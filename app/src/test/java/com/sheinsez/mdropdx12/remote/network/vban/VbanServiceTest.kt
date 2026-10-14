package com.sheinsez.mdropdx12.remote.network.vban

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The identification exchange. The field offsets below are the ones a live
 * mdxmixer actually answers with -- checked with tools/vban_probe.py, not
 * counted off the struct by eye, because counting them wrong returns empty
 * strings and no error.
 */
class VbanServiceTest {

    /** Spec p.27: for SERVICE the low five bits MUST be zero -- 0x60 exactly. */
    @Test
    fun `a ping request is service type 0, function 0, format_SR exactly 0x60`() {
        val pkt = VbanCodec.buildPing0Request("VBAN Service", 4242L)
        assertEquals(VbanCodec.HEADER_SIZE, pkt.size)
        assertEquals(0x60.toByte(), pkt[4])
        assertEquals(0, pkt[5].toInt())      // function 0, reply bit clear
        assertEquals(0, pkt[6].toInt())      // service type 0 = identification
        val p = VbanCodec.parse(pkt, pkt.size)!!
        assertEquals(VbanCodec.PROTO_SERVICE, p.header.proto)
        assertEquals(4242L, p.header.nuFrame)
    }

    /** The exact shape the running server replied with. */
    @Test
    fun `an identification reply yields the features, rate and names`() {
        val reply = fakeReply(
            transactionId = 4242L,
            bitType = 0x22L,                         // TRANSMITTER | VIRTUALMIXER
            bitFeature = 0x11001L,                   // AUDIO | FRAME | TXT
            preferredRate = 48000,
            deviceName = "mdxmixer",
            appName = "mdxmixer",
            hostName = "AMDDESK",
        )
        val parsed = VbanCodec.parsePing0Reply(VbanCodec.parse(reply, reply.size)!!)!!
        assertEquals(4242L, parsed.transactionId)
        assertEquals(48000, parsed.preferredRate)
        assertEquals("mdxmixer", parsed.deviceName)
        assertEquals("mdxmixer", parsed.applicationName)
        assertEquals("AMDDESK", parsed.hostName)
        assertTrue(parsed.hasAudio)
        assertTrue(parsed.hasTxt)
        assertTrue(parsed.hasFrames)
    }

    /** A PC with frames turned off must not be read as having them. */
    @Test
    fun `absent feature bits are absent`() {
        val reply = fakeReply(1L, 0x2L, VbanCodec.FEATURE_AUDIO, 48000, "x", "x", "x")
        val parsed = VbanCodec.parsePing0Reply(VbanCodec.parse(reply, reply.size)!!)!!
        assertTrue(parsed.hasAudio)
        assertEquals(false, parsed.hasTxt)
        assertEquals(false, parsed.hasFrames)
    }

    /** A request echoed back, or a short payload, is not a reply. */
    @Test
    fun `a non-reply or truncated payload is rejected`() {
        val req = VbanCodec.buildPing0Request("VBAN Service", 1L)
        assertNull(VbanCodec.parsePing0Reply(VbanCodec.parse(req, req.size)!!))

        val short = ByteArray(VbanCodec.HEADER_SIZE + 10)
        VbanCodec.writeHeader(short, VbanCodec.PROTO_SERVICE, 0, 0x80, 0, 0, "s", 1L)
        assertNull(VbanCodec.parsePing0Reply(VbanCodec.parse(short, short.size)!!))
    }

    /** A real reply is 28 + 676 bytes; the probe measured exactly 704. */
    @Test
    fun `the payload is the spec's 676 bytes`() {
        val reply = fakeReply(1L, 0x22L, 0x11001L, 48000, "mdxmixer", "mdxmixer", "AMDDESK")
        assertEquals(704, reply.size)
        assertEquals(676, VbanCodec.PING0_PAYLOAD_SIZE)
    }

    private fun fakeReply(
        transactionId: Long,
        bitType: Long,
        bitFeature: Long,
        preferredRate: Int,
        deviceName: String,
        appName: String,
        hostName: String,
    ): ByteArray {
        val out = ByteArray(VbanCodec.HEADER_SIZE + VbanCodec.PING0_PAYLOAD_SIZE)
        VbanCodec.writeHeader(
            out, VbanCodec.PROTO_SERVICE, 0,
            nbs = VbanCodec.SERVICE_REPLY_BIT, nbc = VbanCodec.SERVICE_IDENTIFICATION,
            bit = 0, streamName = "VBAN Service", nuFrame = transactionId,
        )
        val b = VbanCodec.HEADER_SIZE
        fun le32(off: Int, v: Long) {
            out[b + off] = (v and 0xFF).toByte()
            out[b + off + 1] = ((v shr 8) and 0xFF).toByte()
            out[b + off + 2] = ((v shr 16) and 0xFF).toByte()
            out[b + off + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun ascii(off: Int, s: String, width: Int) {
            for (i in 0 until width) out[b + off + i] = if (i < s.length) s[i].code.toByte() else 0
        }
        le32(0, bitType)
        le32(4, bitFeature)
        le32(12, preferredRate.toLong())
        // 164 / 292 / 356, the offsets the live server uses. distantIp[32]
        // ends at 160 and distantPort + distantReserved take four bytes
        // between them.
        ascii(164, deviceName, 64)
        ascii(228, "VB-Audio", 64)      // manufacturerName
        ascii(292, appName, 64)
        ascii(356, hostName, 64)
        return out
    }
}
