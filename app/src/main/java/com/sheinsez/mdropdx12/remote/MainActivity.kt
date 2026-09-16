package com.sheinsez.mdropdx12.remote

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
                AppNavigation(remoteVm = remoteVm, mixerVm = mixerVm)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
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
        }
        return super.onKeyDown(keyCode, event)
    }
}
