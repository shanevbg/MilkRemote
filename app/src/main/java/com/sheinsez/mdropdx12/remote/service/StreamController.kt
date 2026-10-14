package com.sheinsez.mdropdx12.remote.service

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.sheinsez.mdropdx12.remote.data.settingsDataStore
import com.sheinsez.mdropdx12.remote.network.vban.LatencyProfile
import com.sheinsez.mdropdx12.remote.network.vban.VbanCodec
import com.sheinsez.mdropdx12.remote.network.vban.VbanSession
import com.sheinsez.mdropdx12.remote.network.vban.VbanSessionState
import com.sheinsez.mdropdx12.remote.viewmodel.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Starts, stops and pauses the stream from anywhere: the stream card, a
 * watch, the lock screen, a headset button.
 *
 * Application-scoped, like LinkedFaderController, because the media session
 * that receives a watch's Play lives in the background service with no
 * Activity in front -- and Play is the press most likely to arrive then. This
 * used to be VbanViewModel's own code, and a ViewModel only exists while a
 * screen does; the stream card now calls this too, so there is one way to
 * start the stream rather than two that could drift.
 */
class StreamController(
    private val context: Context,
    private val session: VbanSession,
    private val scope: CoroutineScope,
) {
    private companion object {
        const val TAG = "MdrStream"
    }

    /** What a media controller should be shown, from the session's own flags. */
    fun playbackNow(): StreamPlayback =
        StreamTransport.playback(session.audioOn.value, session.muted.value)

    fun handle(command: TransportCommand) {
        val now = playbackNow()
        val action = StreamTransport.actionFor(command, now)
        // One line per press, because a watch is the one controller whose
        // presses nobody sees happen: "it ignored me" is otherwise unanswerable.
        Log.i(TAG, "media $command while $now -> $action")
        when (action) {
            TransportAction.Start -> play()
            TransportAction.Mute -> session.setMuted(true)
            TransportAction.Unmute -> session.setMuted(false)
            TransportAction.StopAudio -> stop()
            TransportAction.Nothing -> Unit
        }
    }

    /**
     * Start playing on this phone.
     *
     * On the main thread, which is where this ran when it was the
     * ViewModel's: a media callback arrives there too, so nothing about the
     * session's threading changes by moving it.
     */
    fun play() {
        scope.launch(Dispatchers.Main) {
            val prefs = context.settingsDataStore.data.first()
            // The mixer's own address first. They are usually one machine,
            // but the visualizer's server can be off while the mixer is up,
            // and then last_host is a stale answer to a different question.
            val host = prefs[SettingsViewModel.KEY_VBAN_HOST]
                ?: prefs[SettingsViewModel.KEY_LAST_HOST]
                ?: return@launch
            val port = prefs[SettingsViewModel.KEY_VBAN_PORT] ?: VbanCodec.DEFAULT_PORT
            val pin = prefs[SettingsViewModel.KEY_VBAN_PIN] ?: ""
            val profile = profileFrom(prefs)
            // Add audio to a link that is already up, rather than tearing it
            // down and rebuilding it: re-authenticating costs a round trip
            // and drops the forwarding subscription with it.
            if (session.state.value == VbanSessionState.Streaming) {
                session.enableAudio(profile)
            } else {
                session.start(host, port, pin, "mdxmixer", profile, wantAudio = true)
            }
            rememberPlaying(true)
        }
    }

    /**
     * Stop the AUDIO, and keep the link.
     *
     * The user is done listening, not done with the remote -- and taking the
     * session down here would leave every other screen unable to send
     * anything, which is precisely the state this app spent an afternoon
     * stuck in.
     */
    fun stop() {
        session.stopAudio()
        rememberPlaying(false)
    }

    /**
     * So a restart can put playback back the way the user left it -- and
     * keep the media player on offer once the stream has ever played.
     */
    private fun rememberPlaying(on: Boolean) {
        scope.launch {
            context.settingsDataStore.edit {
                it[SettingsViewModel.KEY_VBAN_AUDIO_ON] = on
                if (on) it[SettingsViewModel.KEY_VBAN_PLAYED] = true
            }
        }
    }

    /** The stored latency profile. Here once, for every place that starts audio. */
    fun profileFrom(prefs: Preferences): LatencyProfile =
        if (prefs[SettingsViewModel.KEY_VBAN_PROFILE] == "low") LatencyProfile.LowLatency
        else LatencyProfile.Resilient
}
