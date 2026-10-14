package com.sheinsez.mdropdx12.remote.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import org.junit.Test

/**
 * A server that accepts and then says nothing.
 *
 * This is the shape that wedged the app twice: the socket opens, AUTH goes out,
 * and no reply ever comes. There was no timer on that and no ping -- the ping
 * watchdog only starts at AUTH_OK -- so the client waited forever, and the
 * retry loop waited on the client. Both times the PC was listening, reachable,
 * and answering the same credentials with AUTH_OK; the fault was here.
 */
class TcpClientAuthTimeoutTest {

    /** Reads the AUTH frame so the client's write completes, then stays mute. */
    private fun silentServer(): ServerSocket {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            runCatching {
                val sock = server.accept()
                DataInputStream(sock.getInputStream()).readNBytes(4)
                // and nothing more, deliberately
                Thread.sleep(30_000)
            }
        }
        return server
    }

    /** Reads the AUTH frame and answers it, so the happy path is covered too. */
    private fun answeringServer(reply: String): ServerSocket {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            runCatching {
                val sock: Socket = server.accept()
                val input = DataInputStream(sock.getInputStream())
                val len = ByteArrayLittleEndianInt(input.readNBytes(4))
                input.readNBytes(len)
                sock.getOutputStream().write(MilkwaveProtocol.encode(reply))
                sock.getOutputStream().flush()
                Thread.sleep(30_000)
            }
        }
        return server
    }

    private fun ByteArrayLittleEndianInt(b: ByteArray): Int =
        (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)

    @Test
    fun anUnansweredAuthIsWrittenOffRatherThanWaitedOnForever() {
        val server = silentServer()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val client = TcpClient(scope, authTimeoutMs = 400)
        try {
            client.connect("127.0.0.1", server.localPort, "0000", "dev", "phone")
            runBlocking {
                withTimeout(10_000) {
                    client.connectionState.first { it == ConnectionState.AuthPending }
                    // The point of the fix: this arrives at all.
                    client.connectionState.first { it == ConnectionState.Disconnected }
                }
            }
        } finally {
            client.disconnect(); scope.cancel(); server.close()
        }
    }

    @Test
    fun anAnsweredAuthConnectsAndIsNotWrittenOff() {
        val server = answeringServer("AUTH_OK")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val client = TcpClient(scope, authTimeoutMs = 400)
        try {
            client.connect("127.0.0.1", server.localPort, "0000", "dev", "phone")
            runBlocking {
                withTimeout(10_000) {
                    client.connectionState.first { it == ConnectionState.Connected }
                }
                // Well past the timeout: the timer must have been disarmed.
                Thread.sleep(1_200)
                assertEquals(ConnectionState.Connected, client.connectionState.value)
            }
        } finally {
            client.disconnect(); scope.cancel(); server.close()
        }
    }

    /**
     * Waiting to be allowed on the PC is a legitimate long wait, so the
     * server's own AUTH_PENDING must disarm the timer rather than be cut short.
     */
    @Test
    fun anAuthPendingFromTheServerIsNotATimeout() {
        val server = answeringServer("AUTH_PENDING")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val client = TcpClient(scope, authTimeoutMs = 400)
        try {
            client.connect("127.0.0.1", server.localPort, "0000", "dev", "phone")
            runBlocking {
                withTimeout(10_000) {
                    client.connectionState.first { it == ConnectionState.AuthPending }
                }
                Thread.sleep(1_200)
                assertEquals(ConnectionState.AuthPending, client.connectionState.value)
            }
        } finally {
            client.disconnect(); scope.cancel(); server.close()
        }
    }
}
