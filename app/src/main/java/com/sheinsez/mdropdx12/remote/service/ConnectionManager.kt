package com.sheinsez.mdropdx12.remote.service

import com.sheinsez.mdropdx12.remote.network.ConnectionState
import com.sheinsez.mdropdx12.remote.network.TcpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ConnectionManager(private val scope: CoroutineScope) {
    val tcpClient = TcpClient(scope)
    private var reconnectJob: Job? = null
    private var lastHost: String? = null
    private var lastPort: Int = 9270
    private var lastPin: String = ""
    private var lastDeviceId: String = ""
    private var lastDeviceName: String = ""

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

    private fun startReconnect() {
        if (reconnectJob?.isActive == true) return
        _isReconnecting.value = true
        reconnectJob = scope.launch {
            while (isActive && tcpClient.connectionState.value == ConnectionState.Disconnected) {
                delay(2000)
                lastHost?.let { host ->
                    tcpClient.connect(host, lastPort, lastPin, lastDeviceId, lastDeviceName)
                    delay(3000)
                }
            }
            _isReconnecting.value = false
        }
    }

    private fun stopReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        _isReconnecting.value = false
    }
}
