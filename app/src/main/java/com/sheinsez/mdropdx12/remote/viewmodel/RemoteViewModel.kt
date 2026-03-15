package com.sheinsez.mdropdx12.remote.viewmodel

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sheinsez.mdropdx12.remote.MdrApp
import com.sheinsez.mdropdx12.remote.data.model.VisualizerState
import com.sheinsez.mdropdx12.remote.network.CommandBuilder
import com.sheinsez.mdropdx12.remote.network.ConnectionState
import com.sheinsez.mdropdx12.remote.network.MessageParser
import com.sheinsez.mdropdx12.remote.service.ConnectionManager
import com.sheinsez.mdropdx12.remote.viewmodel.SettingsViewModel.Companion.KEY_DEVICE_ID
import com.sheinsez.mdropdx12.remote.viewmodel.SettingsViewModel.Companion.KEY_DEVICE_NAME
import com.sheinsez.mdropdx12.remote.viewmodel.SettingsViewModel.Companion.KEY_LAST_HOST
import com.sheinsez.mdropdx12.remote.viewmodel.SettingsViewModel.Companion.KEY_LAST_PORT
import com.sheinsez.mdropdx12.remote.viewmodel.SettingsViewModel.Companion.KEY_PIN
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

import com.sheinsez.mdropdx12.remote.data.settingsDataStore

class RemoteViewModel(application: Application) : AndroidViewModel(application) {
    val connectionManager = (application as MdrApp).connectionManager

    private val _state = MutableStateFlow(VisualizerState())
    val state: StateFlow<VisualizerState> = _state

    private var waveThrottleJob: Job? = null
    private val pendingWaveParams = mutableMapOf<String, String>()
    private var volumeThrottleJob: Job? = null

    init {
        viewModelScope.launch {
            connectionManager.messages.collect { msg -> processMessage(msg) }
        }
        connectionManager.enableAutoReconnect()
        tryAutoConnect(application)
    }

    private fun tryAutoConnect(application: Application) {
        viewModelScope.launch {
            val prefs = application.settingsDataStore.data.first()
            val host = prefs[KEY_LAST_HOST] ?: return@launch
            val port = prefs[KEY_LAST_PORT] ?: 9270
            val pin = prefs[KEY_PIN] ?: ""
            val deviceId = prefs[KEY_DEVICE_ID] ?: return@launch
            val deviceName = prefs[KEY_DEVICE_NAME] ?: Build.MODEL
            if (connectionManager.connectionState.value == ConnectionState.Disconnected) {
                connectionManager.connect(host, port, pin, deviceId, deviceName)
            }
        }
    }

    private fun processMessage(msg: String) {
        when {
            msg.startsWith("PRESET=") -> {
                MessageParser.parsePreset(msg)?.let { name ->
                    _state.update { it.copy(presetName = name) }
                }
            }
            msg.startsWith("TRACK|") -> {
                MessageParser.parseTrack(msg)?.let { (artist, title, album) ->
                    _state.update { it.copy(trackArtist = artist, trackTitle = title, trackAlbum = album) }
                }
            }
            msg.startsWith("OPACITY=") -> {
                MessageParser.parseOpacity(msg)?.let { opacity ->
                    _state.update { it.copy(opacity = opacity) }
                }
            }
            msg.startsWith("WAVE|") -> {
                MessageParser.parseWave(msg, _state.value)?.let { updated ->
                    _state.value = updated
                }
            }
            msg.startsWith("SETTINGS|") -> {
                MessageParser.parseSettings(msg, _state.value)?.let { updated ->
                    _state.value = updated
                }
            }
            msg.startsWith("DEVICE_VOLUME=") -> {
                MessageParser.parseDeviceVolume(msg, _state.value)?.let { updated ->
                    _state.value = updated
                }
            }
            msg.startsWith("DEVICE_MUTE=") -> {
                MessageParser.parseDeviceMute(msg, _state.value)?.let { updated ->
                    _state.value = updated
                }
            }
            msg.startsWith("AUDIO_DEVICES|") -> {
                MessageParser.parseAudioDevices(msg, _state.value)?.let { updated ->
                    _state.value = updated
                }
            }
            msg.startsWith("DEVICE_VOLUME_ERROR=") || msg.startsWith("DEVICE_MUTE_ERROR=") -> {
                android.util.Log.w("RemoteViewModel", "Audio device error: $msg")
            }
        }
    }

    fun sendSignal(name: String) = connectionManager.send(CommandBuilder.signal(name))
    fun sendKey(hex: String) = connectionManager.send(CommandBuilder.sendKey(hex))
    fun sendMessage(text: String) = connectionManager.send(CommandBuilder.message(text))
    fun sendRaw(command: String) = connectionManager.send(CommandBuilder.raw(command))
    fun mediaPlayPause() = connectionManager.send(CommandBuilder.mediaPlayPause())
    fun mediaNext() = connectionManager.send(CommandBuilder.mediaNext())
    fun mediaPrev() = connectionManager.send(CommandBuilder.mediaPrev())
    fun nextPreset() = connectionManager.send(CommandBuilder.nextPreset())
    fun prevPreset() = connectionManager.send(CommandBuilder.prevPreset())

    fun updateWaveParam(key: String, value: String) {
        synchronized(pendingWaveParams) { pendingWaveParams[key] = value }
        if (waveThrottleJob?.isActive != true) {
            waveThrottleJob = viewModelScope.launch {
                delay(50)
                val params: Map<String, String>
                synchronized(pendingWaveParams) {
                    params = pendingWaveParams.toMap()
                    pendingWaveParams.clear()
                }
                if (params.isNotEmpty()) {
                    connectionManager.send(CommandBuilder.wave(params))
                }
            }
        }
    }

    fun updateColor(hue: Float? = null, saturation: Float? = null, brightness: Float? = null) {
        hue?.let { connectionManager.send(CommandBuilder.colorHue(it)) }
        saturation?.let { connectionManager.send(CommandBuilder.colorSaturation(it)) }
        brightness?.let { connectionManager.send(CommandBuilder.colorBrightness(it)) }
    }

    fun updateVariable(time: Float? = null, intensity: Float? = null, quality: Float? = null) {
        time?.let { connectionManager.send(CommandBuilder.varTime(it)) }
        intensity?.let { connectionManager.send(CommandBuilder.varIntensity(it)) }
        quality?.let { connectionManager.send(CommandBuilder.varQuality(it)) }
    }

    fun updateAmp(left: Float, right: Float) = connectionManager.send(CommandBuilder.amp(left, right))
    fun updateFftAttack(value: Float) = connectionManager.send(CommandBuilder.fftAttack(value))
    fun updateFftDecay(value: Float) = connectionManager.send(CommandBuilder.fftDecay(value))

    fun updateVolume(value: Float) {
        volumeThrottleJob?.cancel()
        volumeThrottleJob = viewModelScope.launch {
            delay(100)
            connectionManager.send(CommandBuilder.setDeviceVolume(value))
        }
    }

    fun toggleMute() = connectionManager.send(CommandBuilder.toggleDeviceMute())

    fun requestAudioDevices() = connectionManager.send(CommandBuilder.getAudioDevices())

    fun setAudioDevice(name: String) {
        connectionManager.send(CommandBuilder.setAudioDevice(name))
        viewModelScope.launch {
            delay(200)
            connectionManager.send(CommandBuilder.getDeviceVolume())
        }
    }
}
