package com.sheinsez.mdropdx12.remote.network

import java.nio.ByteBuffer
import java.nio.ByteOrder

object MilkwaveProtocol {
    fun encode(command: String): ByteArray {
        val payload = command.toByteArray(Charsets.UTF_8)
        val frame = ByteArray(4 + payload.size)
        ByteBuffer.wrap(frame, 0, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.size)
        System.arraycopy(payload, 0, frame, 4, payload.size)
        return frame
    }

    fun decode(buffer: ByteArray, offset: Int, length: Int): DecodeResult? {
        if (length < 4) return null
        val payloadLen = ByteBuffer.wrap(buffer, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (payloadLen < 0 || payloadLen > 4 * 1024 * 1024) return DecodeResult("", 4)
        if (length < 4 + payloadLen) return null
        val message = String(buffer, offset + 4, payloadLen, Charsets.UTF_8)
        return DecodeResult(message, 4 + payloadLen)
    }

    data class DecodeResult(val message: String, val bytesConsumed: Int)
}
