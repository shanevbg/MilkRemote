package com.sheinsez.mdropdx12.remote.network.vban

/**
 * The VBAN wire format (rev 13, SEP 2025), clean-room from the published
 * specification.
 *
 * PURE: no `android.*`, no sockets, no clock, so every byte-level decision is
 * testable without a network or a device. The JVM suite has no Robolectric,
 * which is the other reason -- anything that touched `android.media` here
 * would throw `Stub!` in every test.
 *
 * Every constant is the SPEC's, not ours. Where the spec is silent the
 * comment says so at the point of the decision, because the failure mode for
 * guessing is the far end going quiet with nothing to read at this one.
 */
object VbanCodec {

    const val HEADER_SIZE = 28
    /** 1500-byte MTU less 64, so a VLAN tag cannot fragment a packet. */
    const val MAX_DATA = 1436
    const val MAX_PACKET = HEADER_SIZE + MAX_DATA
    const val STREAM_NAME_SIZE = 16
    const val DEFAULT_PORT = 6980

    // Sub-protocol, bits 5-7 of format_SR.
    const val PROTO_AUDIO = 0x00
    const val PROTO_SERIAL = 0x20
    const val PROTO_TXT = 0x40
    const val PROTO_SERVICE = 0x60
    const val PROTO_FRAME = 0x80
    const val PROTO_MASK = 0xE0

    /**
     * The sample-rate table. NOT ordered by rate: the spec's is three
     * geometric families (48k-based, 8k-based, 44.1k-based), so **48000 is
     * index 3 and 44100 is index 16**. Computing an index instead of looking
     * it up is the obvious mistake, and it puts the stream on the wrong rate
     * -- which plays back at the wrong speed and sounds like a fault in the
     * music rather than a fault in the header.
     */
    private val RATES = intArrayOf(
        6000, 12000, 24000, 48000, 96000, 192000, 384000,
        8000, 16000, 32000, 64000, 128000, 256000, 512000,
        11025, 22050, 44100, 88200, 176400, 352800, 705600,
    )

    fun sampleRateIndex(rate: Int): Int = RATES.indexOf(rate)

    fun sampleRateFromIndex(index: Int): Int =
        if (index in RATES.indices) RATES[index] else 0

    data class VbanHeader(
        val proto: Int,
        /** Audio: the sample-rate index. Other sub-protocols: informational. */
        val sr: Int,
        val nbs: Int,
        val nbc: Int,
        val bit: Int,
        val streamName: String,
        /** Unsigned 32-bit on the wire, so it is carried as a Long. */
        val nuFrame: Long,
    )

    /**
     * A parsed packet. `data` aliases the caller's receive buffer and the
     * payload starts at [HEADER_SIZE]; it lives exactly as long as that
     * buffer does.
     */
    class VbanPacket(val header: VbanHeader, val data: ByteArray, val dataLen: Int)

    fun writeHeader(
        out: ByteArray,
        proto: Int,
        sr: Int,
        nbs: Int,
        nbc: Int,
        bit: Int,
        streamName: String,
        nuFrame: Long,
    ) {
        out[0] = 'V'.code.toByte()
        out[1] = 'B'.code.toByte()
        out[2] = 'A'.code.toByte()
        out[3] = 'N'.code.toByte()
        out[4] = ((proto and PROTO_MASK) or (sr and 0x1F)).toByte()
        out[5] = nbs.toByte()
        out[6] = nbc.toByte()
        out[7] = bit.toByte()
        for (i in 0 until STREAM_NAME_SIZE) {
            out[8 + i] = if (i < streamName.length) streamName[i].code.toByte() else 0
        }
        out[24] = (nuFrame and 0xFF).toByte()
        out[25] = ((nuFrame shr 8) and 0xFF).toByte()
        out[26] = ((nuFrame shr 16) and 0xFF).toByte()
        out[27] = ((nuFrame shr 24) and 0xFF).toByte()
    }

    // ── SERVICE: discovery, and our subscription ─────────────────────────
    //
    // PING0 is the spec's identification exchange and also how this client
    // subscribes: a request creates or renews this address as a peer at the
    // server, and the stream stops when the last one stops asking.

    const val SERVICE_IDENTIFICATION = 0      // format_nbc: service type
    const val SERVICE_FN_PING0 = 0            // format_nbs: function
    const val SERVICE_REPLY_BIT = 0x80        // format_nbs bit 7

    const val FEATURE_AUDIO = 0x1L
    const val FEATURE_FRAME = 0x1000L
    const val FEATURE_TXT = 0x10000L

    /** The spec's identification payload is exactly this many bytes. */
    const val PING0_PAYLOAD_SIZE = 676

    // Offsets into the identification payload, in the spec's field order:
    // bitType, bitFeature, bitFeatureEx, preferredRate, minRate, maxRate,
    // colorRgb, nVersion[4], gpsPosition[8], userPosition[8], langCode[8],
    // reservedAscii[8], reservedEx[64], distantIp[32], distantPort(2),
    // distantReserved(2), deviceName[64], manufacturerName[64],
    // applicationName[64], hostName[64], userName[128], userComment[128].
    //
    // THE NAME OFFSETS WERE VERIFIED AGAINST A RUNNING SERVER, not counted by
    // eye. `distantIp[32]` ends at 160 and the two 16-bit fields after it take
    // four bytes between them, so the names begin at 164. Reading them four
    // bytes early returns three empty strings and reports no error at all --
    // the server's name simply never appears. tools/vban_probe.py is what
    // settled it and is the way to settle the next one.
    private const val OFF_BIT_TYPE = 0
    private const val OFF_BIT_FEATURE = 4
    private const val OFF_PREFERRED_RATE = 12
    private const val OFF_DEVICE_NAME = 164
    private const val OFF_APPLICATION_NAME = 292
    private const val OFF_HOST_NAME = 356

    data class Ping0Reply(
        val bitType: Long,
        val bitFeature: Long,
        val preferredRate: Int,
        val deviceName: String,
        val applicationName: String,
        val hostName: String,
        /** Echoed from our request, so a reply can be matched to its ping. */
        val transactionId: Long,
    ) {
        val hasAudio get() = bitFeature and FEATURE_AUDIO != 0L
        val hasTxt get() = bitFeature and FEATURE_TXT != 0L
        val hasFrames get() = bitFeature and FEATURE_FRAME != 0L
    }

    fun buildPing0Request(streamName: String, transactionId: Long): ByteArray {
        val out = ByteArray(HEADER_SIZE)
        writeHeader(
            out,
            proto = PROTO_SERVICE,
            // MUST be zero for SERVICE: format_SR is 0x60 exactly, and a
            // strict receiver checks it.
            sr = 0,
            nbs = SERVICE_FN_PING0,
            nbc = SERVICE_IDENTIFICATION,
            bit = 0,
            streamName = streamName,
            nuFrame = transactionId,
        )
        return out
    }

    fun parsePing0Reply(p: VbanPacket): Ping0Reply? {
        if (p.header.proto != PROTO_SERVICE) return null
        if (p.header.sr != 0) return null
        if (p.header.nbs and SERVICE_REPLY_BIT == 0) return null   // not a reply
        if (p.header.nbc != SERVICE_IDENTIFICATION) return null
        if (p.dataLen < PING0_PAYLOAD_SIZE) return null
        val b = p.data
        val base = HEADER_SIZE
        fun le32(off: Int): Long =
            (b[base + off].toLong() and 0xFF) or
                ((b[base + off + 1].toLong() and 0xFF) shl 8) or
                ((b[base + off + 2].toLong() and 0xFF) shl 16) or
                ((b[base + off + 3].toLong() and 0xFF) shl 24)
        fun ascii(off: Int, width: Int): String {
            val sb = StringBuilder()
            for (i in 0 until width) {
                val c = b[base + off + i].toInt() and 0xFF
                if (c == 0) break
                sb.append(c.toChar())
            }
            return sb.toString()
        }
        return Ping0Reply(
            bitType = le32(OFF_BIT_TYPE),
            bitFeature = le32(OFF_BIT_FEATURE),
            preferredRate = le32(OFF_PREFERRED_RATE).toInt(),
            deviceName = ascii(OFF_DEVICE_NAME, 64),
            applicationName = ascii(OFF_APPLICATION_NAME, 64),
            hostName = ascii(OFF_HOST_NAME, 64),
            transactionId = p.header.nuFrame,
        )
    }

    // ── AUDIO ────────────────────────────────────────────────────────────

    const val DATATYPE_U8 = 0
    const val DATATYPE_INT16 = 1
    const val DATATYPE_INT24 = 2
    const val DATATYPE_INT32 = 3
    const val DATATYPE_FLOAT32 = 4
    const val DATATYPE_FLOAT64 = 5
    /** PCM is the only codec used; VB's VBCA/VBCV are not freely licensed. */
    const val CODEC_PCM = 0x00

    data class AudioFormat(
        val sampleRate: Int,
        val channels: Int,
        val dataType: Int,
        val samplesPerPacket: Int,
    )

    fun parseAudio(p: VbanPacket): AudioFormat? {
        if (p.header.proto != PROTO_AUDIO) return null
        if (p.header.bit and 0xF0 != CODEC_PCM) return null
        val rate = sampleRateFromIndex(p.header.sr)
        if (rate == 0) return null
        return AudioFormat(
            sampleRate = rate,
            // Every count on the wire is value-minus-one. Off by one here is
            // silence, or a channel count the track cannot be built with.
            channels = p.header.nbc + 1,
            dataType = p.header.bit and 0x07,
            samplesPerPacket = p.header.nbs + 1,
        )
    }

    // ── TXT: the MDXM control records ────────────────────────────────────

    const val TXT_UTF8 = 0x10

    /**
     * One TXT packet, or null when the payload will not fit.
     *
     * A record NEVER spans packets: half a record is not a slow answer, it is
     * two unparseable fragments.
     */
    fun buildTxt(streamName: String, nuFrame: Long, utf8: String): ByteArray? {
        val bytes = utf8.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_DATA) return null
        val out = ByteArray(HEADER_SIZE + bytes.size)
        writeHeader(
            out, PROTO_TXT, sr = 0, nbs = 0, nbc = 0, bit = TXT_UTF8,
            streamName = streamName, nuFrame = nuFrame,
        )
        bytes.copyInto(out, HEADER_SIZE)
        return out
    }

    fun parseTxt(p: VbanPacket): String? {
        if (p.header.proto != PROTO_TXT) return null
        return String(p.data, HEADER_SIZE, p.dataLen, Charsets.UTF_8)
    }

    /**
     * Validate a received datagram.
     *
     * This is the app's first unauthenticated input from the network, so
     * length and magic are checked before anything is believed and `dataLen`
     * is derived from the real length -- nothing downstream can read past the
     * buffer. Returns null rather than throwing: a malformed datagram must
     * not be able to end the receive loop, which would take the stream down
     * with it for no reason a user could act on.
     */
    fun parse(buf: ByteArray, len: Int): VbanPacket? {
        if (len < HEADER_SIZE || len > MAX_PACKET || len > buf.size) return null
        if (buf[0] != 'V'.code.toByte() || buf[1] != 'B'.code.toByte() ||
            buf[2] != 'A'.code.toByte() || buf[3] != 'N'.code.toByte()
        ) return null
        val srByte = buf[4].toInt() and 0xFF
        val name = StringBuilder()
        for (i in 0 until STREAM_NAME_SIZE) {
            val c = buf[8 + i].toInt() and 0xFF
            if (c == 0) break
            name.append(c.toChar())
        }
        val nuFrame = (buf[24].toLong() and 0xFF) or
            ((buf[25].toLong() and 0xFF) shl 8) or
            ((buf[26].toLong() and 0xFF) shl 16) or
            ((buf[27].toLong() and 0xFF) shl 24)
        return VbanPacket(
            VbanHeader(
                proto = srByte and PROTO_MASK,
                sr = srByte and 0x1F,
                nbs = buf[5].toInt() and 0xFF,
                nbc = buf[6].toInt() and 0xFF,
                bit = buf[7].toInt() and 0xFF,
                streamName = name.toString(),
                nuFrame = nuFrame,
            ),
            buf,
            len - HEADER_SIZE,
        )
    }
}
