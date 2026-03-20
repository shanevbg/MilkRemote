package com.sheinsez.mdropdx12.remote.network

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

enum class ConnectionState { Disconnected, Connecting, AuthPending, Connected }

class TcpClient(private val scope: CoroutineScope) {
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var readJob: Job? = null
    private var pingJob: Job? = null
    private var connectJob: Job? = null
    private var lastPongReceived: Long = 0L

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val messages: SharedFlow<String> = _messages

    fun connect(host: String, port: Int, pin: String, deviceId: String, deviceName: String) {
        // Cancel any in-progress connect and clean up existing connection
        connectJob?.cancel()
        closeSocket()

        _connectionState.value = ConnectionState.Connecting
        connectJob = scope.launch(Dispatchers.IO) {
            try {
                val sock = Socket()
                sock.soTimeout = 0          // blocking reads
                sock.keepAlive = true        // TCP keepalive
                sock.tcpNoDelay = true       // disable Nagle for responsiveness
                sock.connect(InetSocketAddress(host, port), 5000)
                socket = sock
                outputStream = sock.getOutputStream()

                readJob = scope.launch(Dispatchers.IO) { readLoop(sock.getInputStream()) }

                send("AUTH|$pin|$deviceId|$deviceName")
                _connectionState.value = ConnectionState.AuthPending
            } catch (e: IOException) {
                closeSocket()
                _connectionState.value = ConnectionState.Disconnected
            }
        }
    }

    fun send(command: String) {
        scope.launch(Dispatchers.IO) {
            try {
                outputStream?.let {
                    val frame = MilkwaveProtocol.encode(command)
                    synchronized(it) {
                        it.write(frame)
                        it.flush()
                    }
                }
            } catch (_: IOException) {
                disconnect()
            }
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        pingJob?.cancel()
        readJob?.cancel()
        closeSocket()
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun closeSocket() {
        pingJob?.cancel()
        readJob?.cancel()
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        outputStream = null
    }

    private suspend fun readLoop(input: InputStream) {
        val buffer = ByteArray(65536)
        val accumulated = ByteArrayOutputStream(8192)

        try {
            while (currentCoroutineContext().isActive) {
                val bytesRead = input.read(buffer)
                if (bytesRead == -1) break

                accumulated.write(buffer, 0, bytesRead)

                val bytes = accumulated.toByteArray()
                var offset = 0
                while (offset < bytes.size) {
                    val result = MilkwaveProtocol.decode(bytes, offset, bytes.size - offset) ?: break
                    if (result.message.isNotEmpty()) {
                        handleMessage(result.message)
                    }
                    offset += result.bytesConsumed
                }

                accumulated.reset()
                if (offset < bytes.size) {
                    accumulated.write(bytes, offset, bytes.size - offset)
                }
            }
        } catch (_: IOException) {}

        // Only set disconnected if we weren't explicitly cancelled (avoid racing with new connect)
        if (currentCoroutineContext().isActive) {
            disconnect()
        }
    }

    private suspend fun handleMessage(message: String) {
        when {
            message == "AUTH_OK" -> {
                _connectionState.value = ConnectionState.Connected
                startPing()
                send("STATE")
            }
            message.startsWith("AUTH_FAIL") -> {
                _connectionState.value = ConnectionState.Disconnected
            }
            message == "AUTH_PENDING" -> {
                _connectionState.value = ConnectionState.AuthPending
            }
            message == "PONG" -> { lastPongReceived = System.currentTimeMillis() }
            else -> _messages.emit(message)
        }
    }

    private fun startPing() {
        pingJob?.cancel()
        lastPongReceived = System.currentTimeMillis()
        pingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(15_000)
                // Allow 2 missed pongs (45s total) before considering dead
                if (System.currentTimeMillis() - lastPongReceived > 45_000) {
                    disconnect()
                    break
                }
                send("PING")
            }
        }
    }
}
