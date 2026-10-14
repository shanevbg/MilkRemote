package com.sheinsez.mdropdx12.remote.network.vban

/**
 * One complete JPEG per VBAN frame, arriving in packets of at most 1436
 * bytes.
 *
 * PURE — the caller does `BitmapFactory.decodeByteArray` — so loss, reorder
 * and several displays interleaving can be tested without a device.
 *
 * A dropped packet loses that frame and nothing else: there is no
 * retransmission and no partial decode, which is the right trade here
 * because the next capture replaces it a fraction of a second later.
 */
class FrameAssembler {

    private class Partial {
        val chunks = sortedMapOf<Int, ByteArray>()
        var nuFrame: Long = -1
    }

    private val byDisplay = mutableMapOf<Int, Partial>()

    companion object {
        private val VIDEO = Regex("""^VIDEO(\d+)$""")

        /**
         * `n` is the Windows display DEVICE number — the n in `\\.\DISPLAYn`
         * — and never a position in any list. Device numbers are neither
         * contiguous nor bounded by the monitor count on a machine whose
         * panels have been replugged, so indexing by position would draw one
         * screen's picture on another.
         */
        fun displayNumberOf(streamName: String): Int? =
            VIDEO.find(streamName)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** The completed (display number, JPEG) on the last packet of a frame. */
    fun accept(p: VbanCodec.VbanPacket): Pair<Int, ByteArray>? {
        if (p.header.proto != VbanCodec.PROTO_FRAME) return null
        val display = displayNumberOf(p.header.streamName) ?: return null
        // 16-bit packet index, split LSB/MSB across two header bytes.
        val index = (p.header.nbs and 0xFF) or ((p.header.nbc and 0xFF) shl 8)
        val type = p.header.bit and 0x07
        val isStart = type and 0x01 != 0
        val isEnd = type and 0x04 != 0

        val body = p.data.copyOfRange(
            VbanCodec.HEADER_SIZE,
            VbanCodec.HEADER_SIZE + p.dataLen,
        )
        val partial =
            if (isStart) Partial().also { byDisplay[display] = it } else byDisplay[display]
                ?: return null

        if (isStart) partial.nuFrame = p.header.nuFrame
        else if (partial.nuFrame != p.header.nuFrame) return null

        partial.chunks[index] = body
        if (!isEnd) return null

        // Every index from 0 to the end packet must be present. A hole means
        // this frame is gone; the next capture is the fix, not a repair here.
        val expected = index + 1
        if (partial.chunks.size != expected) {
            byDisplay.remove(display)
            return null
        }
        val out = ByteArray(partial.chunks.values.sumOf { it.size })
        var at = 0
        for (chunk in partial.chunks.values) {
            chunk.copyInto(out, at)
            at += chunk.size
        }
        byDisplay.remove(display)
        return display to out
    }
}
