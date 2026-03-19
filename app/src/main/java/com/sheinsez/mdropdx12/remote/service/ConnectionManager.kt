package com.sheinsez.mdropdx12.remote.service

import com.sheinsez.mdropdx12.remote.network.ConnectionState
import com.sheinsez.mdropdx12.remote.network.TcpClient
import com.sheinsez.mdropdx12.remote.network.discovery.MdnsDiscovery
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ConnectionManager(private val scope: CoroutineScope) {
    val tcpClient = TcpClient(scope)
    var mdnsDiscovery: MdnsDiscovery? = null  // Set from Activity/Application with context
    private var reconnectJob: Job? = null
    private var lastHost: String? = null
    private var lastPort: Int = 9270
    private var lastPin: String = ""
    private var lastDeviceId: String = ""
    private var lastDeviceName: String = ""
    private val _serverName = MutableStateFlow("")
    val serverName: StateFlow<String> = _serverName
    private var consecutiveFailures: Int = 0

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
        stopReconnect()
        tcpClient.connect(host, port, pin, deviceId, deviceName)
    }

    fun disconnect() {
        stopReconnect()
        tcpClient.disconnect()
        lastHost = null
    }

    fun send(command: String) = tcpClient.send(command)

    fun enableAutoReconnect() {
        scope.launch {
            tcpClient.connectionState.collect { state ->
                if (state == ConnectionState.Disconnected && lastHost != null) {
                    startReconnect()
                } else {
                    stopReconnect()
                }
            }
        }
    }

    fun onResume() {
        if (tcpClient.connectionState.value == ConnectionState.Disconnected && lastHost != null) {
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
            while (isActive && tcpClient.connectionState.value == ConnectionState.Disconnected) {
                delay(2000)
                lastHost?.let { host ->
                    tcpClient.connect(host, lastPort, lastPin, lastDeviceId, lastDeviceName)
                    delay(3000)
                    // Check if still disconnected after attempt
                    if (tcpClient.connectionState.value == ConnectionState.Disconnected) {
                        consecutiveFailures++
                        // After 3 failures, try mDNS re-discovery (server may have restarted on new port)
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
        delay(3000) // Give mDNS time to find services
        val servers = discovery.servers.value
        val match = servers.find { it.name == _serverName.value }
        if (match != null && (match.host != lastHost || match.port != lastPort)) {
            // Server restarted on a different host/port — update and retry
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
