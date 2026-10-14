package com.sheinsez.mdropdx12.remote

import android.app.ActivityManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.sheinsez.mdropdx12.remote.network.ConnectionState
import com.sheinsez.mdropdx12.remote.ui.navigation.AppNavigation
import com.sheinsez.mdropdx12.remote.ui.theme.MdrTheme
import com.sheinsez.mdropdx12.remote.viewmodel.MixerViewModel
import com.sheinsez.mdropdx12.remote.viewmodel.RemoteViewModel
import com.sheinsez.mdropdx12.remote.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val remoteVm: RemoteViewModel by viewModels()
    private val settingsVm: SettingsViewModel by viewModels()

    // Activity-scoped on purpose, and handed to the Mixer screen rather than
    // let it build its own: a nav-scoped instance would be a second copy of the
    // mixer state, and the volume keys would drive a list the screen never
    // updated.
    private val mixerVm: MixerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // The linked set lives in settings; the mixer is what acts on it.
        lifecycleScope.launch {
            settingsVm.linkedFaders.collect { mixerVm.setLinked(it) }
        }

        setContent {
            MdrTheme {
                AppNavigation(
                    // The header's Exit. Everything in the background stops
                    // first, then every task goes, so nothing is left in
                    // Recents or the notification shade claiming to be
                    // connected.
                    //
                    // EVERY task, through ActivityManager, and not this
                    // Activity's finishAndRemoveTask: that removes the task
                    // only when called from its ROOT activity, and from any
                    // other it just finishes the one. A notification tap
                    // stacks a second MainActivity over the first, so Exit
                    // pressed there closed the top copy and the one beneath
                    // resumed -- which looked exactly like the app restarting
                    // itself.
                    onExit = {
                        (application as MdrApp).exit()
                        getSystemService(ActivityManager::class.java)
                            .appTasks.forEach { it.finishAndRemoveTask() }
                    },
                    remoteVm = remoteVm,
                    mixerVm = mixerVm,
                )
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            // While the phone is rendering the stream AND the user has asked
            // for it, the keys belong to this phone's own output: let them
            // fall through to STREAM_MUSIC untouched. Default off, so a
            // stream alone does not take the faders away from somebody who
            // deliberately linked them.
            val app = application as MdrApp
            if (VolumeRocker.localOutputOwnsKeys(
                    streaming = app.vbanStreaming.value,
                    linkWhenStreaming = settingsVm.vbanRockerLocal.value,
                )
            ) {
                return super.onKeyDown(keyCode, event)
            }
            val enabled = settingsVm.volumeRockerEnabled.value
            val connected = remoteVm.connectionManager.connectionState.value == ConnectionState.Connected
            if (VolumeRocker.shouldIntercept(enabled, connected)) {
                val up = keyCode == KeyEvent.KEYCODE_VOLUME_UP
                val step = settingsVm.volumeRockerStepPercent.value
                // Linked faders win: picking specific faders is a stronger
                // statement than the target chosen in Settings. With none
                // linked this answers false and the old behaviour stands,
                // rather than the key press being swallowed.
                if (!mixerVm.nudgeLinkedFaders(step, up)) {
                    remoteVm.nudgeVolume(up = up, stepPercent = step)
                }
                return true
            }
            // The mixer moves linked faders with the visualizer's socket off,
            // or with no visualizer running at all -- where the test above,
            // which is about that socket, says there is nothing to reach.
            // Linked faders ONLY: with none, the key is left exactly where it
            // went before rather than being swallowed.
            if (VolumeRocker.shouldIntercept(enabled, mixerVm.fromMixer.value) &&
                mixerVm.nudgeLinkedFaders(
                    settingsVm.volumeRockerStepPercent.value,
                    keyCode == KeyEvent.KEYCODE_VOLUME_UP,
                )
            ) {
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
