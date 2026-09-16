package com.sheinsez.mdropdx12.remote.service

import com.sheinsez.mdropdx12.remote.data.model.ServerIdentity
import com.sheinsez.mdropdx12.remote.network.CommandBuilder
import com.sheinsez.mdropdx12.remote.network.ConnectionState
import com.sheinsez.mdropdx12.remote.network.MessageParser
import com.sheinsez.mdropdx12.remote.network.TcpClient
import com.sheinsez.mdropdx12.remote.network.discovery.MdnsDiscovery
import com.sheinsez.mdropdx12.remote.network.discovery.ServerCandidates
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Longer than TcpClient's own auth timeout, so this only catches what that misses. */
private const val ATTEMPT_TIMEOUT_MS = 20_000L

class ConnectionManager(private val scope: CoroutineScope) {
    val tcpClient = TcpClient(scope)
    var mdnsDiscovery: MdnsDiscovery? = null
    private var reconnectJob: Job? = null
    private var lastHost: String? = null
    private var lastPort: Int = 9270
    private var lastPin: String = ""
    private var lastDeviceId: String = ""
    private var lastDeviceName: String = ""
    private val _serverName = MutableStateFlow("")
    val serverName: StateFlow<String> = _serverName
    private var consecutiveFailures: Int = 0
    private var intentionalDisconnect = false

    /**
     * Endpoints tried without success since the last time one worked.
     *
     * Cleared on every connection, so a address that failed because the PC was
     * simply not running yet is not held against it forever.
     */
    private val failedEndpoints = linkedSetOf<String>()

    /**
     * The address actually in use, which is not always the one asked for.
     *
     * The reconnect loop moves to another discovered address when the stored
     * one turns out to be a black hole, and that decision is worth keeping:
     * without it every cold start goes back to the dead address and waits for
     * the fallback to happen again. Emitted so whoever owns the settings store
     * can persist it.
     *
     * A SharedFlow rather than a StateFlow, because a StateFlow conflates: the
     * settings screen can write a host back at any time, so the SAME endpoint
     * succeeding twice has to be published twice or the second recovery is
     * never recorded. Replay 1 so a late subscriber still learns the endpoint
     * in use.
     */
    private val _activeEndpoint = MutableSharedFlow<Endpoint>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val activeEndpoint: SharedFlow<Endpoint> = _activeEndpoint

    data class Endpoint(val host: String, val port: Int)

    private val _isReconnecting = MutableStateFlow(false)
    val isReconnecting: StateFlow<Boolean> = _isReconnecting

    val connectionState: StateFlow<ConnectionState> = tcpClient.connectionState
    val messages: SharedFlow<String> = tcpClient.messages

    /**
     * What the PC says it is, and what it says it supports.
     *
     * Asked once per connection rather than probed per feature: a screen can
     * then explain what a PC is missing instead of quietly doing less. Reset on
     * every connect, because the next PC may not be the last one -- and because
     * a stale capability list is worse than none, since it would be believed.
     */
    private val _identity = MutableStateFlow(ServerIdentity())
    val identity: StateFlow<ServerIdentity> = _identity

    init {
        scope.launch {
            tcpClient.connectionState.collect { state ->
                if (state == ConnectionState.Connected) {
                    failedEndpoints.clear()
                    lastHost?.let { _activeEndpoint.tryEmit(Endpoint(it, lastPort)) }
                    _identity.value = ServerIdentity()
                    tcpClient.send(CommandBuilder.getVersion())
                } else {
                    _identity.value = ServerIdentity()
                }
            }
        }
        scope.launch {
            // Batched with its END_BATCH terminator, like every other reply.
            tcpClient.messages.collect { raw ->
                for (line in raw.lineSequence()) {
                    MessageParser.parseIdentity(line.trim())?.let { _identity.value = it }
                }
            }
        }
    }

    fun connect(host: String, port: Int, pin: String, deviceId: String, deviceName: String) {
        lastHost = host
        lastPort = port
        lastPin = pin
        lastDeviceId = deviceId
        lastDeviceName = deviceName
        intentionalDisconnect = false
        stopReconnect()
        tcpClient.connect(host, port, pin, deviceId, deviceName)
    }

    fun disconnect() {
        intentionalDisconnect = true
        stopReconnect()
        tcpClient.disconnect()
        lastHost = null
    }

    fun send(command: String) = tcpClient.send(command)

    fun enableAutoReconnect() {
        scope.launch {
            tcpClient.connectionState
                .dropWhile { it == ConnectionState.Disconnected } // skip initial state
                .collect { state ->
                    when (state) {
                        ConnectionState.Disconnected -> {
                            if (!intentionalDisconnect && lastHost != null) {
                                startReconnect()
                            }
                        }
                        ConnectionState.Connected -> {
                            stopReconnect()
                        }
                        else -> {} // Connecting/AuthPending — wait
                    }
                }
        }
    }

    fun onResume() {
        if (tcpClient.connectionState.value == ConnectionState.Disconnected && lastHost != null && !intentionalDisconnect) {
            connect(lastHost!!, lastPort, lastPin, lastDeviceId, lastDeviceName)
        }
    }

    fun onPause() {
        stopReconnect()
    }

    fun setServerName(name: String) { _serverName.value = name }

    /**
     * Synchronized with stopReconnect, because the check and the assignment
     * below are one decision and were not one operation.
     *
     * Every Disconnected calls in here, and TcpClient publishes that from
     * whichever thread noticed -- so two callers could both read a finished
     * job, both pass the guard, and both launch. The second assignment then
     * orphaned the first loop instead of replacing it: two loops retried the
     * same host, raced over the discovery socket, and fought over a
     * connectionState each was reading to decide what to do. Observed as
     * duplicated attempts at the same millisecond from two thread ids.
     */
    @Synchronized
    private fun startReconnect() {
        if (reconnectJob?.isActive == true) return
        _isReconnecting.value = true
        consecutiveFailures = 0
        reconnectJob = scope.launch {
            while (isActive && lastHost != null) {
                delay(3000)
                // Only a live connection ends the loop, and nothing else
                // skips an attempt.
                //
                // This used to bail out on any state that was not
                // Disconnected, which turned two ordinary situations into a
                // permanent stop. A connect cancelled to make way for another
                // never runs its IOException handler -- cancellation is not an
                // IOException -- so connectionState can sit at Connecting with
                // no attempt behind it at all, and the loop either exited on
                // it or, once that was changed to waiting, waited on it
                // forever. Neither is recoverable without the user noticing
                // and reconnecting by hand.
                //
                // Attempting regardless is safe: tcpClient.connect supersedes
                // any attempt still in flight, and withTimeoutOrNull below
                // bounds every one of them.
                if (tcpClient.connectionState.value == ConnectionState.Connected) break

                lastHost?.let { host ->
                    tcpClient.connect(host, lastPort, lastPin, lastDeviceId, lastDeviceName)

                    // Wait for the connection attempt to resolve.
                    //
                    // Bounded, because an unbounded wait here is a loop that
                    // stops looping: a socket parked in AuthPending resolves to
                    // neither, and this waited on it forever while the retry
                    // that would have fixed it sat behind this line. TcpClient
                    // now times its own auth out, so this is a second fence
                    // rather than the fix -- but a retry loop should not be one
                    // await away from never running again.
                    val connected = withTimeoutOrNull(ATTEMPT_TIMEOUT_MS) {
                        tcpClient.connectionState
                            .filter {
                                it == ConnectionState.Connected ||
                                    it == ConnectionState.Disconnected
                            }
                            .first()
                    }

                    if (connected != ConnectionState.Connected) {
                        consecutiveFailures++
                        // Remember that THIS address did not answer, then move
                        // to another one rather than spending the next attempt
                        // on it again. An address that is merely routable
                        // costs a full timeout per try, so retrying it while a
                        // live sibling is sitting in the discovery list is the
                        // slowest possible way to get nowhere.
                        failedEndpoints += ServerCandidates.endpointKey(host, lastPort)
                        moveToAnotherAddress()
                    } else {
                        consecutiveFailures = 0
                    }
                }
            }
            _isReconnecting.value = false
        }
    }

    /**
     * Point the retry loop at a different address after one has failed.
     *
     * The old shape of this waited for three consecutive failures and then
     * took whatever `find` returned for the service name -- which, when a PC
     * publishes two addresses under one name, is as likely to be the dead one
     * as the live one, and could even switch a working host out for a black
     * hole. Now it runs after the FIRST failure and asks for a ranked list, so
     * a beacon address wins and an already-failed one is skipped.
     *
     * Discovery is started here rather than being left running: it holds a UDP
     * socket and an NSD registration, and the only moment those are needed is
     * when the address in hand has stopped working.
     */
    private suspend fun moveToAnotherAddress() {
        val discovery = mdnsDiscovery ?: return
        discovery.startDiscovery()
        // Long enough for a 3s beacon to arrive, so a live address can outrank
        // a published one rather than losing to whatever mDNS answered first.
        delay(4000)
        val next = ServerCandidates.next(
            servers = discovery.servers.value,
            serverName = _serverName.value.takeIf { it.isNotEmpty() },
            failed = failedEndpoints,
            current = lastHost?.let { ServerCandidates.endpointKey(it, lastPort) },
        )
        discovery.stopDiscovery()

        if (next != null && (next.host != lastHost || next.port != lastPort)) {
            lastHost = next.host
            lastPort = next.port
            consecutiveFailures = 0
        }
    }

    @Synchronized
    private fun stopReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        _isReconnecting.value = false
    }
}
