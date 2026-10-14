package com.sheinsez.mdropdx12.remote.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sheinsez.mdropdx12.remote.MdrApp
import com.sheinsez.mdropdx12.remote.VolumeRocker
import com.sheinsez.mdropdx12.remote.data.model.VisualizerState
import com.sheinsez.mdropdx12.remote.network.CommandBuilder
import com.sheinsez.mdropdx12.remote.network.MessageParser
import com.sheinsez.mdropdx12.remote.service.ConnectionManager
import com.sheinsez.mdropdx12.remote.viewmodel.SettingsViewModel.Companion.KEY_LAST_HOST
import com.sheinsez.mdropdx12.remote.viewmodel.SettingsViewModel.Companion.KEY_LAST_PORT
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

import androidx.datastore.preferences.core.edit
import com.sheinsez.mdropdx12.remote.data.settingsDataStore

class RemoteViewModel(application: Application) : AndroidViewModel(application) {
    val connectionManager = (application as MdrApp).connectionManager

    /**
     * Where MDropDX12 commands go.
     *
     * Not the TCP socket. The link routes each command by its verb -- through
     * the mixer for everything the relay can carry, down the visualizer's own
     * socket for the handful of replies too large for a datagram -- so a
     * screen states what it wants and does not pick a wire.
     */
    val link = (application as MdrApp).mdxLink

    private val _state = MutableStateFlow(VisualizerState())
    val state: StateFlow<VisualizerState> = _state

    private var waveThrottleJob: Job? = null
    private val pendingWaveParams = mutableMapOf<String, String>()
    private var volumeThrottleJob: Job? = null

    init {
        viewModelScope.launch {
            link.messages.collect { record -> processMessage(record.text) }
        }
        connectionManager.enableAutoReconnect()
        persistWorkingEndpoint(application)
        tryAutoConnect(application)
    }

    /** The header's Disconnect: both wires down, and the app stays open. */
    fun disconnect() = getApplication<MdrApp>().linkControl.disconnect()

    /**
     * The header's tap-to-connect: both wires, from the stored addresses.
     * [onNothingStored] runs when there is no address to use at all.
     */
    fun connect(onNothingStored: () -> Unit) {
        viewModelScope.launch {
            if (!getApplication<MdrApp>().linkControl.connect()) onNothingStored()
        }
    }

    /**
     * Write back the address that actually worked.
     *
     * The reconnect loop moves off a stored address that has stopped
     * answering, which fixes the session in hand but not the next cold start:
     * the app would read the dead address back out of the settings store and
     * wait for the whole fallback to play out again. Recording the endpoint
     * that connected means the fallback runs once rather than every launch.
     */
    private fun persistWorkingEndpoint(application: Application) {
        viewModelScope.launch {
            connectionManager.activeEndpoint.collect { endpoint ->
                application.settingsDataStore.edit { prefs ->
                    if (prefs[KEY_LAST_HOST] != endpoint.host) prefs[KEY_LAST_HOST] = endpoint.host
                    if (prefs[KEY_LAST_PORT] != endpoint.port) prefs[KEY_LAST_PORT] = endpoint.port
                }
            }
        }
    }

    private fun tryAutoConnect(application: Application) {
        viewModelScope.launch { (application as MdrApp).linkControl.connectTcp() }
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

    fun sendSignal(name: String) = link.send(CommandBuilder.signal(name))
    fun sendKey(hex: String) = link.send(CommandBuilder.sendKey(hex))
    fun sendMessage(text: String) = link.send(CommandBuilder.message(text))
    fun sendRaw(command: String) = link.send(CommandBuilder.raw(command))
    fun mediaPlayPause() = link.send(CommandBuilder.mediaPlayPause())
    fun mediaNext() = link.send(CommandBuilder.mediaNext())
    fun mediaPrev() = link.send(CommandBuilder.mediaPrev())
    fun nextPreset() = link.send(CommandBuilder.nextPreset())
    fun prevPreset() = link.send(CommandBuilder.prevPreset())

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
                    link.send(CommandBuilder.wave(params))
                }
            }
        }
    }

    fun updateColor(hue: Float? = null, saturation: Float? = null, brightness: Float? = null) {
        hue?.let { link.send(CommandBuilder.colorHue(it)) }
        saturation?.let { link.send(CommandBuilder.colorSaturation(it)) }
        brightness?.let { link.send(CommandBuilder.colorBrightness(it)) }
    }

    fun updateVariable(time: Float? = null, intensity: Float? = null, quality: Float? = null) {
        time?.let { link.send(CommandBuilder.varTime(it)) }
        intensity?.let { link.send(CommandBuilder.varIntensity(it)) }
        quality?.let { link.send(CommandBuilder.varQuality(it)) }
    }

    fun updateAmp(left: Float, right: Float) = link.send(CommandBuilder.amp(left, right))
    fun updateFftAttack(value: Float) = link.send(CommandBuilder.fftAttack(value))
    fun updateFftDecay(value: Float) = link.send(CommandBuilder.fftDecay(value))

    fun updateVolume(value: Float) {
        volumeThrottleJob?.cancel()
        volumeThrottleJob = viewModelScope.launch {
            delay(100)
            link.send(CommandBuilder.setDeviceVolume(value))
        }
    }

    fun nudgeVolume(up: Boolean, stepPercent: Int) {
        if (up && _state.value.muted) {
            link.send(CommandBuilder.toggleDeviceMute())
            _state.update { it.copy(muted = false) }
        }
        val next = VolumeRocker.nextVolume(_state.value.volume, up, stepPercent)
        _state.update { it.copy(volume = next) }
        updateVolume(next)
    }

    fun toggleMute() = link.send(CommandBuilder.toggleDeviceMute())

    fun requestAudioDevices() = link.send(CommandBuilder.getAudioDevices())

    fun setAudioDevice(name: String) {
        link.send(CommandBuilder.setAudioDevice(name))
        viewModelScope.launch {
            delay(200)
            link.send(CommandBuilder.getDeviceVolume())
        }
    }
}
