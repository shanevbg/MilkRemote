package com.sheinsez.mdropdx12.remote.service

import android.content.Context
import android.os.Build
import com.sheinsez.mdropdx12.remote.data.settingsDataStore
import com.sheinsez.mdropdx12.remote.network.ConnectionState
import com.sheinsez.mdropdx12.remote.network.vban.VbanCodec
import com.sheinsez.mdropdx12.remote.network.vban.VbanSession
import com.sheinsez.mdropdx12.remote.network.vban.VbanSessionState
import com.sheinsez.mdropdx12.remote.viewmodel.SettingsViewModel
import kotlinx.coroutines.flow.first

/**
 * Brings both wires up from the stored addresses, and takes both down.
 *
 * One place for it because there are now two callers that must agree: a cold
 * start, and the header's tap-to-connect after a Disconnect. They used to be
 * a ViewModel each -- the TCP half in RemoteViewModel, the mixer half in
 * VbanViewModel -- and a reconnect from the header written as a third copy
 * would have been the one that drifted.
 */
class LinkControl(
    private val context: Context,
    private val connectionManager: ConnectionManager,
    private val session: VbanSession,
    private val stream: StreamController,
) {
    /**
     * The visualizer's own socket, at the address that last worked.
     *
     * False when there is nothing stored to connect to, so the caller can send
     * somebody to Settings rather than appear to do nothing.
     */
    suspend fun connectTcp(): Boolean {
        val prefs = context.settingsDataStore.data.first()
        val host = prefs[SettingsViewModel.KEY_LAST_HOST] ?: return false
        val port = prefs[SettingsViewModel.KEY_LAST_PORT] ?: 9270
        val pin = prefs[SettingsViewModel.KEY_PIN] ?: ""
        val deviceId = prefs[SettingsViewModel.KEY_DEVICE_ID] ?: return false
        val deviceName = prefs[SettingsViewModel.KEY_DEVICE_NAME] ?: Build.MODEL
        if (connectionManager.connectionState.value == ConnectionState.Disconnected) {
            connectionManager.connect(host, port, pin, deviceId, deviceName)
        }
        return true
    }

    /**
     * Bring the mixer link up for COMMANDS, whether or not anyone wants audio.
     *
     * The session used to come into existence only when Play was pressed,
     * which made the entire remote depend on the headphones: with nothing
     * playing there was no socket, no authentication, and every command was
     * refused with "No connection to the PC" while the PC sat there running.
     * Harmless when the visualizer had its own TCP server to fall back on,
     * and fatal once that is switched off.
     *
     * Idempotent, and cheap when it does nothing: no AudioTrack is built and
     * no audio is requested. False when no mixer address is stored.
     *
     * [outputId] null leaves the session's chosen output alone. The header
     * has no output picker of its own, and passing "automatic" from there
     * would quietly undo the one the stream card set.
     */
    suspend fun ensureSession(outputId: Int? = null): Boolean {
        val prefs = context.settingsDataStore.data.first()
        val host = prefs[SettingsViewModel.KEY_VBAN_HOST]
            ?: prefs[SettingsViewModel.KEY_LAST_HOST]
            ?: return false
        if (session.state.value != VbanSessionState.Idle &&
            session.state.value !is VbanSessionState.Lost
        ) {
            return true
        }
        val port = prefs[SettingsViewModel.KEY_VBAN_PORT] ?: VbanCodec.DEFAULT_PORT
        val pin = prefs[SettingsViewModel.KEY_VBAN_PIN] ?: ""
        // RESUME AUDIO IF IT WAS PLAYING. The in-memory want dies with
        // the process, so without this the app comes back silent after
        // being killed or updated -- which reads as the remote being
        // broken rather than as playback having stopped.
        val wasPlaying = prefs[SettingsViewModel.KEY_VBAN_AUDIO_ON] == true
        outputId?.let { session.preferredOutputId = it }
        session.start(
            host, port, pin, "mdxmixer",
            profile = if (wasPlaying) stream.profileFrom(prefs) else null,
            wantAudio = wasPlaying,
        )
        return true
    }

    /**
     * Both wires, from the header. False when neither has an address stored.
     *
     * Silent even if the stream was playing when Disconnect was pressed:
     * [disconnect] forgets that on purpose, because somebody who reconnects a
     * remote is not asking for audio in their ears.
     */
    suspend fun connect(): Boolean {
        val tcp = connectTcp()
        val mixer = ensureSession()
        return tcp || mixer
    }

    /**
     * Take everything down and keep it down until somebody connects again.
     *
     * Both wires. The TCP socket alone is what ConnectionManager.disconnect
     * drops, and with the mixer carrying commands -- the everyday setup --
     * the header would still have read "Connected · via the mixer" afterwards.
     *
     * The stream's "was playing" is cleared through the same stop a watch
     * uses, so neither a reconnect nor the next cold start brings audio back
     * that the user just turned off.
     *
     * Nothing comes back by itself: ConnectionManager's intentional flag holds
     * off its reconnect loop, and the session has no loop of its own -- once
     * stopped it stays stopped until something calls start.
     */
    fun disconnect() {
        stream.stop()
        session.stop()
        connectionManager.disconnect()
    }
}
