package com.sheinsez.mdropdx12.remote.network.vban

import com.sheinsez.mdropdx12.remote.audio.VbanAudioPlayer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions pulled out of the platform classes so they can be tested at
 * all: `android.media` throws `Stub!` in this suite, and a socket loop is
 * not something a unit test should own.
 */
class SessionStateTest {

    @Test
    fun `an approval moves a pinging session to streaming`() {
        assertEquals(
            VbanSessionState.Streaming,
            VbanSession.nextState(VbanSessionState.Pinging, AuthState.Ok),
        )
    }

    @Test
    fun `pending waits, and waiting again is still pending`() {
        assertEquals(
            VbanSessionState.AuthPending,
            VbanSession.nextState(VbanSessionState.Pinging, AuthState.Pending),
        )
        assertEquals(
            VbanSessionState.AuthPending,
            VbanSession.nextState(VbanSessionState.AuthPending, AuthState.Pending),
        )
    }

    @Test
    fun `approval after waiting starts the stream`() {
        assertEquals(
            VbanSessionState.Streaming,
            VbanSession.nextState(VbanSessionState.AuthPending, AuthState.Ok),
        )
    }

    /**
     * Terminal on purpose. The server counts strikes per IP, so a client
     * that keeps retrying a refusal turns a 60-second lockout into a
     * permanent one.
     */
    @Test
    fun `denial, lockout and a bad pin are terminal, with their reason kept`() {
        assertEquals(
            VbanSessionState.Lost(LostReason.Denied),
            VbanSession.nextState(VbanSessionState.AuthPending, AuthState.Denied),
        )
        assertEquals(
            VbanSessionState.Lost(LostReason.Locked),
            VbanSession.nextState(VbanSessionState.Pinging, AuthState.Locked),
        )
        assertEquals(
            VbanSessionState.Lost(LostReason.BadPin),
            VbanSession.nextState(VbanSessionState.Pinging, AuthState.BadPin),
        )
    }

    /** A re-auth after an AP roam must not restart the whole session. */
    @Test
    fun `a streaming session re-approved stays streaming`() {
        assertEquals(
            VbanSessionState.Streaming,
            VbanSession.nextState(VbanSessionState.Streaming, AuthState.Ok),
        )
    }

    /** The server's own expiry, which is why the ping interval is 2 s. */
    @Test
    fun `the ping cadence leaves room for missed pings`() {
        assertTrue(VbanSession.PEER_EXPIRY_MS >= 4 * 2_000L)
    }
}

/**
 * Review Focus 3 — the spec says a header change is a restart. The
 * AudioTrack cannot be exercised here, so the DECISION is where it can be.
 */
class AudioFormatChangeTest {

    private fun fmt(rate: Int, ch: Int = 2, type: Int = VbanCodec.DATATYPE_INT16) =
        VbanCodec.AudioFormat(rate, ch, type, 256)

    @Test
    fun `the first format is always a change`() {
        assertTrue(VbanAudioPlayer.formatChanged(null, fmt(48000)))
    }

    @Test
    fun `the same format is not a change`() {
        assertFalse(VbanAudioPlayer.formatChanged(fmt(48000), fmt(48000)))
    }

    /** 44.1 kHz through a 48 kHz track is wrong pitch, not an error. */
    @Test
    fun `a mix-rate change forces a reconfigure`() {
        assertTrue(VbanAudioPlayer.formatChanged(fmt(48000), fmt(44100)))
    }

    @Test
    fun `a channel or sample-type change forces a reconfigure`() {
        assertTrue(VbanAudioPlayer.formatChanged(fmt(48000, ch = 2), fmt(48000, ch = 1)))
        assertTrue(
            VbanAudioPlayer.formatChanged(
                fmt(48000, type = VbanCodec.DATATYPE_INT16),
                fmt(48000, type = VbanCodec.DATATYPE_FLOAT32),
            ),
        )
    }

    /** Packet size varies run to run and must NOT tear the track down. */
    @Test
    fun `a different packet size alone is not a format change`() {
        val a = VbanCodec.AudioFormat(48000, 2, VbanCodec.DATATYPE_INT16, 256)
        val b = VbanCodec.AudioFormat(48000, 2, VbanCodec.DATATYPE_INT16, 128)
        assertFalse(VbanAudioPlayer.formatChanged(a, b))
    }

    @Test
    fun `sample widths match the wire`() {
        assertEquals(2, VbanAudioPlayer.bytesPerSample(VbanCodec.DATATYPE_INT16))
        assertEquals(4, VbanAudioPlayer.bytesPerSample(VbanCodec.DATATYPE_FLOAT32))
    }
}

class FrameAssemblerTest {

    private fun packet(
        stream: String,
        index: Int,
        type: Int,
        nuFrame: Long,
        body: ByteArray,
    ): VbanCodec.VbanPacket {
        val out = ByteArray(VbanCodec.HEADER_SIZE + body.size)
        VbanCodec.writeHeader(
            out, VbanCodec.PROTO_FRAME, sr = 0,
            nbs = index and 0xFF, nbc = (index shr 8) and 0xFF,
            bit = type, streamName = stream, nuFrame = nuFrame,
        )
        body.copyInto(out, VbanCodec.HEADER_SIZE)
        return VbanCodec.parse(out, out.size)!!
    }

    @Test
    fun `the stream name gives the display device number`() {
        assertEquals(1, FrameAssembler.displayNumberOf("VIDEO1"))
        assertEquals(3, FrameAssembler.displayNumberOf("VIDEO3"))
        assertEquals(12, FrameAssembler.displayNumberOf("VIDEO12"))
        assertNull(FrameAssembler.displayNumberOf("mdxmixer"))
        assertNull(FrameAssembler.displayNumberOf("VIDEOx"))
        assertNull(FrameAssembler.displayNumberOf("VIDEO"))
    }

    @Test
    fun `a multi-packet frame reassembles in order`() {
        val a = FrameAssembler()
        assertNull(a.accept(packet("VIDEO1", 0, 0x01, 5L, byteArrayOf(1, 2))))
        assertNull(a.accept(packet("VIDEO1", 1, 0x02, 5L, byteArrayOf(3, 4))))
        val done = a.accept(packet("VIDEO1", 2, 0x04, 5L, byteArrayOf(5)))!!
        assertEquals(1, done.first)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), done.second)
    }

    /** The spec is silent on one-packet frames; start|end = 0x05 is the inference. */
    @Test
    fun `a single-packet frame is start and end together`() {
        val a = FrameAssembler()
        val done = a.accept(packet("VIDEO2", 0, 0x05, 9L, byteArrayOf(7, 7, 7)))!!
        assertEquals(2, done.first)
        assertArrayEquals(byteArrayOf(7, 7, 7), done.second)
    }

    @Test
    fun `a dropped packet discards the frame, and the next one still works`() {
        val a = FrameAssembler()
        a.accept(packet("VIDEO1", 0, 0x01, 5L, byteArrayOf(1)))
        assertNull(a.accept(packet("VIDEO1", 2, 0x04, 5L, byteArrayOf(3))))   // 1 lost
        val done = a.accept(packet("VIDEO1", 0, 0x05, 6L, byteArrayOf(9)))!!
        assertArrayEquals(byteArrayOf(9), done.second)
    }

    /**
     * Review Focus 4 — two displays stream at once, and an unknown name must
     * be ignored rather than indexed into anything.
     */
    @Test
    fun `interleaved streams never splice, and unknown names are ignored`() {
        val a = FrameAssembler()
        a.accept(packet("VIDEO1", 0, 0x01, 1L, byteArrayOf(11)))
        a.accept(packet("VIDEO3", 0, 0x01, 1L, byteArrayOf(31)))
        a.accept(packet("VIDEO1", 1, 0x02, 1L, byteArrayOf(12)))

        val three = a.accept(packet("VIDEO3", 1, 0x04, 1L, byteArrayOf(32)))!!
        assertEquals(3, three.first)
        assertArrayEquals(byteArrayOf(31, 32), three.second)

        val one = a.accept(packet("VIDEO1", 2, 0x04, 1L, byteArrayOf(13)))!!
        assertEquals(1, one.first)
        assertArrayEquals(byteArrayOf(11, 12, 13), one.second)

        assertNull(a.accept(packet("NOTVIDEO", 0, 0x05, 1L, byteArrayOf(1))))
    }

    /** A late packet from the previous frame must not join the current one. */
    @Test
    fun `a packet from a different frame number is rejected`() {
        val a = FrameAssembler()
        a.accept(packet("VIDEO1", 0, 0x01, 10L, byteArrayOf(1)))
        assertNull(a.accept(packet("VIDEO1", 1, 0x04, 9L, byteArrayOf(2))))   // stale
    }
}
