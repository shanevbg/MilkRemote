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

/** How long a sent AUTH may go unanswered before the socket is written off. */
private const val AUTH_TIMEOUT_MS = 8_000L

class TcpClient(
    private val scope: CoroutineScope,
    /** Overridable so a test can exercise the timeout without waiting for it. */
    private val authTimeoutMs: Long = AUTH_TIMEOUT_MS,
) {
    @Volatile private var socket: Socket? = null
    @Volatile private var outputStream: OutputStream? = null
    private var readJob: Job? = null
    private var pingJob: Job? = null
    private var connectJob: Job? = null
    // Read from the ping loop, written from the read loop -- different threads,
    // so without @Volatile the watchdog can see a stale timestamp and drop a
    // healthy connection.
    @Volatile private var lastPongReceived: Long = 0L
    @Volatile private var connectAttempt: Int = 0
    private var lastPin: String = ""
    private var lastDeviceId: String = ""
    private var lastDeviceName: String = ""

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val messages: SharedFlow<String> = _messages

    fun connect(host: String, port: Int, pin: String, deviceId: String, deviceName: String) {
        lastPin = pin
        lastDeviceId = deviceId
        lastDeviceName = deviceName

        // Cancel any in-progress connect and clean up existing connection
        connectJob?.cancel()
        closeSocket()

        _connectionState.value = ConnectionState.Connecting
        // Which attempt this is. Socket.connect below is a BLOCKING JVM call and
        // is not interrupted by Job.cancel(), and there is no suspension point
        // between it returning and the field writes that follow — so a
        // superseded attempt used to run to completion and overwrite socket,
        // outputStream and readJob with a dead connection's values, leaving the
        // live reader running with nothing referencing it. Commands then went to
        // one socket while replies arrived on another, and whichever died first
        // tore down the wrong one.
        val attempt = ++connectAttempt

        connectJob = scope.launch(Dispatchers.IO) {
            try {
                val sock = Socket()
                sock.soTimeout = 0          // blocking reads
                sock.keepAlive = true        // TCP keepalive
                sock.tcpNoDelay = true       // disable Nagle for responsiveness
                sock.connect(InetSocketAddress(host, port), 5000)

                // Someone started a newer attempt while this one blocked. Its
                // socket is the real one now; drop this and touch nothing.
                if (attempt != connectAttempt || !isActive) {
                    try { sock.close() } catch (_: IOException) {}
                    return@launch
                }

                socket = sock
                outputStream = sock.getOutputStream()

                // A child of this job, so cancelling the connect cancels the
                // reader it created. Launched on the outer scope it outlived
                // its own connection and could not be stopped.
                readJob = launch(Dispatchers.IO) { readLoop(sock.getInputStream()) }

                sendAuth()
                _connectionState.value = ConnectionState.AuthPending
            } catch (e: IOException) {
                if (attempt == connectAttempt) {
                    closeSocket()
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
        }
    }

    /**
     * Commands issued while the socket is down, held until it is back.
     *
     * `outputStream?.let { }` is a no-op when the stream is null, so every
     * command sent during a reconnect used to be discarded silently — no queue,
     * no error, no sign on screen. A two-second drop did not merely pause the
     * app, it swallowed whatever was tapped during it, which is most of what
     * "unresponsive" actually meant.
     *
     * Bounded, because a long outage must not accumulate a burst that is
     * replayed into the visualiser minutes later; the oldest go first, since a
     * stale slider position is worth less than a recent one.
     */
    private val pending = ArrayDeque<String>()
    private val pendingLock = Any()

    fun send(command: String) {
        scope.launch(Dispatchers.IO) {
            val stream = outputStream
            if (stream == null) {
                synchronized(pendingLock) {
                    while (pending.size >= MAX_PENDING) pending.removeFirst()
                    pending.addLast(command)
                }
                return@launch
            }
            try {
                writeFrame(stream, command)
            } catch (_: IOException) {
                // Hold it: this is exactly the moment a command would be lost,
                // and the reconnect that follows is what will carry it.
                synchronized(pendingLock) {
                    while (pending.size >= MAX_PENDING) pending.removeFirst()
                    pending.addLast(command)
                }
                disconnect()
            }
        }
    }

    fun disconnect() {
        authTimeoutJob?.cancel()
        authTimeoutJob = null
        connectJob?.cancel()
        pingJob?.cancel()
        readJob?.cancel()
        closeSocket()
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun writeFrame(stream: OutputStream, command: String) {
        val frame = MilkwaveProtocol.encode(command)
        synchronized(stream) {
            stream.write(frame)
            stream.flush()
        }
    }

    /** Drain what was queued while the connection was down, oldest first. */
    private fun flushPending() {
        val stream = outputStream ?: return
        val queued = synchronized(pendingLock) {
            if (pending.isEmpty()) return
            val copy = pending.toList()
            pending.clear()
            copy
        }
        for (command in queued) {
            try {
                writeFrame(stream, command)
            } catch (_: IOException) {
                // Put the rest back rather than losing the tail, and let the
                // reconnect that follows carry them.
                synchronized(pendingLock) {
                    queued.subList(queued.indexOf(command), queued.size)
                        .asReversed().forEach { pending.addFirst(it) }
                }
                disconnect()
                return
            }
        }
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
                authAnswered()
                _connectionState.value = ConnectionState.Connected
                startPing()
                send("STATE")
                // Only now: the socket exists from the moment connect() returns,
                // but the PC discards anything sent before it has authorised us,
                // so flushing any earlier would throw the queue away quietly.
                flushPending()
            }
            message.startsWith("AUTH_FAIL") -> {
                authAnswered()
                _connectionState.value = ConnectionState.Disconnected
            }
            message == "AUTH_PENDING" -> {
                authAnswered()
                _connectionState.value = ConnectionState.AuthPending
            }
            message == "AUTH_REQUIRED" -> {
                authAnswered()
                // Server dropped our session (reconnect, timeout, or stale socket). Re-auth in-place.
                pingJob?.cancel()
                _connectionState.value = ConnectionState.AuthPending
                sendAuth()
            }
            message == "PONG" -> { lastPongReceived = System.currentTimeMillis() }
            else -> _messages.emit(message)
        }
    }

    /**
     * The socket is open and AUTH has gone out, and nothing has answered.
     *
     * There was no timer on this at all, and no ping either -- the ping
     * watchdog only starts at AUTH_OK. So a socket that connected and was then
     * never answered left the client waiting for a reply forever, which is not
     * a state anything recovered from: ConnectionManager's retry loop waits for
     * Connected or Disconnected before looping, and neither ever came. The app
     * sat on "Reconnecting..." indefinitely while the PC was listening,
     * reachable, and answering the same credentials with AUTH_OK.
     */
    private var authTimeoutJob: Job? = null

    private fun sendAuth() {
        if (lastDeviceId.isEmpty()) return
        send("AUTH|$lastPin|$lastDeviceId|$lastDeviceName")
        authTimeoutJob?.cancel()
        authTimeoutJob = scope.launch {
            delay(authTimeoutMs)
            // Any auth reply at all disarms this, INCLUDING the server's own
            // AUTH_PENDING -- waiting to be allowed on the PC is a legitimate
            // long wait, and must not be cut short. This covers silence only.
            if (_connectionState.value == ConnectionState.AuthPending) {
                disconnect()
            }
        }
    }

    private fun authAnswered() {
        authTimeoutJob?.cancel()
        authTimeoutJob = null
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

    private companion object {
        // Enough to hold a burst of slider movement across a short outage,
        // small enough that a long one cannot replay minutes of stale input
        // into the visualiser when the link comes back.
        const val MAX_PENDING = 64
    }
}
