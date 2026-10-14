package com.sheinsez.mdropdx12.remote.viewmodel

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sheinsez.mdropdx12.remote.MdrApp
import com.sheinsez.mdropdx12.remote.VolumeRocker
import com.sheinsez.mdropdx12.remote.audio.PhoneAudioOutputs
import com.sheinsez.mdropdx12.remote.audio.PhoneOutput
import com.sheinsez.mdropdx12.remote.data.settingsDataStore
import com.sheinsez.mdropdx12.remote.network.vban.VbanCodec
import com.sheinsez.mdropdx12.remote.network.vban.VbanSession
import com.sheinsez.mdropdx12.remote.network.vban.VbanSessionState
import com.sheinsez.mdropdx12.remote.service.TransportCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The stream card's state: what the session is doing, and the phone's own
 * output and level.
 *
 * The local volume is the SYSTEM media volume rather than a gain private to
 * this stream, because Android already keeps one per output route — a
 * Bluetooth headset remembers its own level independently of the speaker.
 * A private gain would fight that and leave two numbers meaning "how loud".
 */
class VbanViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MdrApp
    private val session = app.vbanSession

    val state: StateFlow<VbanSessionState> = session.state
    val lagMs: StateFlow<Int> = session.lagMs
    val pcState = session.pcState
    val identity: StateFlow<VbanCodec.Ping0Reply?> = session.identity
    val pcSilent: StateFlow<Boolean> = session.pcSilent

    /** Audio playing here, as opposed to the link merely being up. */
    val audioOn: StateFlow<Boolean> = session.audioOn

    /** Paused from a media controller: still streaming, and silent. */
    val muted: StateFlow<Boolean> = session.muted

    private val _outputs = MutableStateFlow<List<PhoneOutput>>(emptyList())
    val outputs: StateFlow<List<PhoneOutput>> = _outputs

    private val _outputId = MutableStateFlow(PhoneOutput.AUTOMATIC_ID)
    val outputId: StateFlow<Int> = _outputId

    /** 0..1 of the current route's media volume. */
    private val _localVolume = MutableStateFlow(0f)
    val localVolume: StateFlow<Float> = _localVolume

    /**
     * Defaults ON: while the stream plays, the volume keys move this phone's
     * own output.
     *
     * It defaulted off at first, on the argument that a phone streaming the
     * monitor mix is still a remote control and somebody who linked faders to
     * the keys wants those faders. Tried both ways on the rig, and the other
     * reading wins: with audio in your ears, pressing volume-down and having
     * something happen on the PC instead is not what anyone means by the
     * volume keys. The option stays for the fader case.
     */
    val rockerLocal: StateFlow<Boolean> = application.settingsDataStore.data
        .map {
            it[SettingsViewModel.KEY_VBAN_ROCKER_LOCAL] ?: VolumeRocker.ROCKER_LOCAL_DEFAULT
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, VolumeRocker.ROCKER_LOCAL_DEFAULT)

    init {
        refreshOutputs()
        // The link is what every screen sends through, so it comes up with
        // the app rather than waiting for somebody to want headphones.
        ensureSession()
    }

    fun refreshOutputs() {
        _outputs.value = PhoneAudioOutputs.outputs(getApplication())
        _localVolume.value = PhoneAudioOutputs.volume(getApplication())
    }

    fun selectOutput(id: Int) {
        _outputId.value = id
        session.preferredOutputId = id
        // The level belongs to the route, so it changes with the route.
        _localVolume.value = PhoneAudioOutputs.volume(getApplication())
    }

    fun setLocalVolume(fraction: Float) {
        _localVolume.value = fraction
        PhoneAudioOutputs.setVolume(getApplication(), fraction)
    }

    /** Makeup gain on the PC, a percent. 100 is unity; the range reaches 6400. */
    fun setPcGainPercent(percent: Int) = session.setGainPercent(percent)

    /** Why audio is breaking up, in terms that separate the causes. */
    val counters: StateFlow<VbanSession.Counters> = session.counters

    /**
     * Wi-Fi downlink headroom, which is the fault nothing else can see.
     *
     * Android's own indicator reads association, signal and a connectivity
     * check, and all three stay green while the downlink collapses -- so the
     * stream dies behind a full-strength icon with nothing to explain it.
     */
    val downlink: StateFlow<com.sheinsez.mdropdx12.remote.network.vban.Downlink> =
        session.downlink

    /** Bring the link up for COMMANDS. See [com.sheinsez.mdropdx12.remote.service.LinkControl.ensureSession]. */
    fun ensureSession() {
        viewModelScope.launch { app.linkControl.ensureSession(_outputId.value) }
    }

    private fun profileFrom(prefs: androidx.datastore.preferences.core.Preferences) =
        app.stream.profileFrom(prefs)

    /**
     * Point the session at a mixer and bring it up, whether or not one is
     * already running.
     *
     * The host used to be unreachable from Settings: PIN and port were
     * editable, the address was not, and a LAN whose PC had moved left the
     * session hammering [SettingsViewModel.KEY_LAST_HOST] with no way to type
     * the new one. Calling [start] is not enough on its own -- [ensureSession]
     * refuses to replace a session that is already pinging, which is exactly
     * the stuck state a stale address produces.
     */
    fun useMixer(host: String, port: Int? = null) {
        val h = host.trim()
        if (h.isEmpty()) return
        viewModelScope.launch {
            val prefs = getApplication<Application>().settingsDataStore.data.first()
            val p = port ?: prefs[SettingsViewModel.KEY_VBAN_PORT] ?: VbanCodec.DEFAULT_PORT
            val pin = prefs[SettingsViewModel.KEY_VBAN_PIN] ?: ""
            getApplication<Application>().settingsDataStore.edit {
                it[SettingsViewModel.KEY_VBAN_HOST] = h
                it[SettingsViewModel.KEY_VBAN_PORT] = p
            }
            val wantAudio = session.audioOn.value ||
                prefs[SettingsViewModel.KEY_VBAN_AUDIO_ON] == true
            session.preferredOutputId = _outputId.value
            session.start(
                h, p, pin, "mdxmixer",
                profile = if (wantAudio) profileFrom(prefs) else null,
                wantAudio = wantAudio,
            )
        }
    }

    /**
     * The stream card's Play: the same start a watch's Play makes, so the
     * two cannot drift. See [com.sheinsez.mdropdx12.remote.service.StreamController].
     */
    fun start() {
        session.preferredOutputId = _outputId.value
        app.stream.play()
        refreshOutputs()
    }

    /** Stop the AUDIO, and keep the link. */
    fun stop() = app.stream.stop()

    /** Back from a media controller's Pause, which muted rather than stopped. */
    fun resume() = app.stream.handle(TransportCommand.Play)

    fun setRockerLocal(on: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().settingsDataStore.edit {
                it[SettingsViewModel.KEY_VBAN_ROCKER_LOCAL] = on
            }
        }
    }
}
