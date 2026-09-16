package com.sheinsez.mdropdx12.remote

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import com.sheinsez.mdropdx12.remote.data.settingsDataStore
import com.sheinsez.mdropdx12.remote.network.discovery.MdnsDiscovery
import com.sheinsez.mdropdx12.remote.service.ConnectionManager
import com.sheinsez.mdropdx12.remote.service.LinkedFaderController
import com.sheinsez.mdropdx12.remote.service.RemoteForegroundService
import com.sheinsez.mdropdx12.remote.viewmodel.SettingsViewModel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MdrApp : Application() {
    val applicationScope = CoroutineScope(SupervisorJob())

    val connectionManager by lazy {
        ConnectionManager(applicationScope).also {
            it.mdnsDiscovery = MdnsDiscovery(this)
        }
    }

    // The same settings the Settings screen edits, read here because the
    // background service outlives every ViewModel that could hold them.
    //
    // LAZY, every one of them. `preferencesDataStore` reaches for the
    // application context, which does not exist yet while Application is
    // being constructed -- touching it from a property initializer fails to
    // instantiate the Application at all, and the app cannot start.
    val mediaDeviceEnabled: Flow<Boolean> by lazy {
        settingsDataStore.data
        .map { it[SettingsViewModel.KEY_MEDIA_DEVICE] ?: false }
            .distinctUntilChanged()
    }

    private val backgroundServiceEnabled: Flow<Boolean> by lazy {
        settingsDataStore.data
        .map { it[SettingsViewModel.KEY_BACKGROUND_SERVICE] ?: false }
            .distinctUntilChanged()
    }

    private val linkedFaderKeys: Flow<Set<String>> by lazy {
        settingsDataStore.data
        .map { prefs ->
            prefs[SettingsViewModel.KEY_LINKED_FADERS]
                ?.lines()?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        }
            .distinctUntilChanged()
    }

    private val rockerStep: Flow<Int> by lazy {
        settingsDataStore.data
        .map {
            VolumeRocker.clampStepPercent(
                it[SettingsViewModel.KEY_VOLUME_ROCKER_STEP_PERCENT]
                    ?: VolumeRocker.DEFAULT_STEP_PERCENT,
            )
        }
            .distinctUntilChanged()
    }

    /** Moves the linked faders with no Activity in front. */
    val linkedFaders by lazy {
        LinkedFaderController(connectionManager, applicationScope, linkedFaderKeys, rockerStep)
    }

    override fun onCreate() {
        super.onCreate()
        // The service is started and stopped by the setting alone, so turning
        // it off takes the notification away immediately rather than at the
        // next launch.
        applicationScope.launch {
            backgroundServiceEnabled.collect { on ->
                val intent = Intent(this@MdrApp, RemoteForegroundService::class.java)
                if (on) ContextCompat.startForegroundService(this@MdrApp, intent)
                else stopService(intent)
            }
        }
    }
}
