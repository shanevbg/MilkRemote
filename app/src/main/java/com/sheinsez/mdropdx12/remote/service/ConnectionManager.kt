package com.sheinsez.mdropdx12.remote.service

import com.sheinsez.mdropdx12.remote.network.ConnectionState
import com.sheinsez.mdropdx12.remote.network.TcpClient
import com.sheinsez.mdropdx12.remote.network.discovery.MdnsDiscovery
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

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

    private val _isReconnecting = MutableStateFlow(false)
    val isReconnecting: StateFlow<Boolean> = _isReconnecting

    val connectionState: StateFlow<ConnectionState> = tcpClient.connectionState
    val messages: SharedFlow<String> = tcpClient.messages

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

    private fun startReconnect() {
        if (reconnectJob?.isActive == true) return
        _isReconnecting.value = true
        consecutiveFailures = 0
        reconnectJob = scope.launch {
            while (isActive && lastHost != null) {
                delay(3000)
                // Re-check state — may have connected while we were waiting
                if (tcpClient.connectionState.value != ConnectionState.Disconnected) break

                lastHost?.let { host ->
                    tcpClient.connect(host, lastPort, lastPin, lastDeviceId, lastDeviceName)

                    // Wait for the connection attempt to resolve
                    val connected = tcpClient.connectionState
                        .filter { it == ConnectionState.Connected || it == ConnectionState.Disconnected }
                        .first()

                    if (connected == ConnectionState.Disconnected) {
                        consecutiveFailures++
                        if (consecutiveFailures >= 3 && _serverName.value.isNotEmpty()) {
                            tryRediscover()
                        }
                    } else {
                        consecutiveFailures = 0
                    }
                }
            }
            _isReconnecting.value = false
        }
    }

    private suspend fun tryRediscover() {
        val discovery = mdnsDiscovery ?: return
        discovery.startDiscovery()
        delay(3000)
        val servers = discovery.servers.value
        val match = servers.find { it.name == _serverName.value }
        if (match != null && (match.host != lastHost || match.port != lastPort)) {
            lastHost = match.host
            lastPort = match.port
            consecutiveFailures = 0
        }
        discovery.stopDiscovery()
    }

    private fun stopReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        _isReconnecting.value = false
    }
}
