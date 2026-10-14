package com.sheinsez.mdropdx12.remote

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import com.sheinsez.mdropdx12.remote.data.settingsDataStore
import com.sheinsez.mdropdx12.remote.network.discovery.MdnsDiscovery
import com.sheinsez.mdropdx12.remote.service.ConnectionManager
import com.sheinsez.mdropdx12.remote.service.LinkControl
import com.sheinsez.mdropdx12.remote.service.LinkedFaderController
import com.sheinsez.mdropdx12.remote.service.PcTrack
import com.sheinsez.mdropdx12.remote.service.RemoteForegroundService
import com.sheinsez.mdropdx12.remote.service.StreamController
import com.sheinsez.mdropdx12.remote.service.StreamTransport
import com.sheinsez.mdropdx12.remote.viewmodel.SettingsViewModel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MdrApp : Application() {
    val applicationScope = CoroutineScope(SupervisorJob())

    /**
     * Application-scoped, like LinkedFaderController: the stream has to
     * survive navigation and the screen going off, and a ViewModel does not.
     */
    val vbanSession by lazy {
        com.sheinsez.mdropdx12.remote.network.vban.VbanSession(this, applicationScope)
    }

    /**
     * Whether audio is actually rendering, for the two things outside the
     * session that have to know: the foreground service's type claim, and
     * who owns the volume keys.
     */
    val vbanStreaming: kotlinx.coroutines.flow.StateFlow<Boolean> by lazy {
        // `audioOn`, NOT the session state. A control-only link is
        // `Streaming` in the protocol sense while rendering nothing at all --
        // that distinction is the whole reason the session can come up
        // without the Play button -- so reading the state here claimed audio
        // whenever the link was merely up. Both readers of this flow were
        // then wrong in the same breath: the service claimed
        // FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK with nothing playing, which
        // is the claim the platform kills a service for, and the volume keys
        // were handed to a phone output that was silent.
        vbanSession.audioOn
    }

    /**
     * Starts, stops and pauses the stream with no screen in front, for the
     * media session a watch talks to. The stream card goes through it too.
     */
    val stream by lazy { StreamController(this, vbanSession, applicationScope) }

    /** Both wires up from the stored addresses, or both down. See [LinkControl]. */
    val linkControl by lazy { LinkControl(this, connectionManager, vbanSession, stream) }

    /**
     * The header's Exit was pressed, and no screen has opened since.
     *
     * Holds the background service off WITHOUT touching its setting: quitting
     * once is not the same as never wanting the service again, and writing
     * the setting false would have meant the next launch came up without it.
     * Cleared when a screen is created again, which starts the service again
     * if the setting is on -- the process often outlives an Exit, so this has
     * to be a flow the collector in [onCreate] can see change, not a fact
     * checked once at startup.
     */
    private val exited = MutableStateFlow(false)

    /**
     * Stop everything this app runs in the background. The Activity removes
     * its own task afterwards.
     */
    fun exit() {
        linkControl.disconnect()
        exited.value = true
    }

    /**
     * What the PC says is playing: the visualizer's last `TRACK|`, from
     * either wire.
     *
     * Application-scoped because the reader is the media session, which
     * outlives every screen. Null until the PC has said anything at all.
     */
    val pcTrack: StateFlow<PcTrack?> by lazy {
        mdxLink.messages
            .mapNotNull { StreamTransport.parseTrack(it.text) }
            .stateIn(applicationScope, SharingStarted.Eagerly, null)
    }

    /**
     * The media session is showing the stream, and so is reading [pcTrack]
     * with no screen in front. Set by the background service.
     *
     * Not lazy, like [_foreground]: the link reads it from its initializer.
     */
    val nowPlayingShown = MutableStateFlow(false)

    /**
     * Is any screen of this app actually in front?
     *
     * Counted from the Activity callbacks rather than taken from
     * lifecycle-process, which is not a dependency here, and rather than from
     * ViewModel lifetime, which is the wrong signal: a ViewModel survives the
     * home button, so a collector living in one says "somebody is reading"
     * about a phone in a pocket.
     *
     * The relay subscription hangs off this. Audio is unaffected -- the
     * foreground service owns that -- so what stops when the screen goes away
     * is only the stream of replies nothing is left to draw.
     */
    private val _foreground = MutableStateFlow(false)
    val foreground: kotlinx.coroutines.flow.StateFlow<Boolean> = _foreground

    private var startedActivities = 0

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

    /**
     * Do the volume keys belong to this phone's own output while it plays?
     *
     * Here as well as in the ViewModels because the media session that takes
     * the keys lives in the foreground service, which outlives every
     * ViewModel and cannot reach one. Without it the service held the keys
     * globally whenever the link was up, and the Activity's own check could
     * not help: the media button session wins wherever the app is not in
     * front, which is most of the time somebody is wearing headphones.
     */
    val rockerLocal: Flow<Boolean> by lazy {
        settingsDataStore.data
        .map {
            it[SettingsViewModel.KEY_VBAN_ROCKER_LOCAL] ?: VolumeRocker.ROCKER_LOCAL_DEFAULT
        }
            .distinctUntilChanged()
    }

    /**
     * The stream has played on this phone before, as remembered across
     * restarts. Read by the service, which offers the stopped player on it.
     * See [SettingsViewModel.KEY_VBAN_PLAYED].
     */
    val streamPlayedBefore: Flow<Boolean> by lazy {
        settingsDataStore.data
            .map {
                it[SettingsViewModel.KEY_VBAN_PLAYED] == true ||
                    // Anyone who was playing before this key existed has
                    // played; without this the first restart after the
                    // update would lose them their player anyway.
                    it[SettingsViewModel.KEY_VBAN_AUDIO_ON] == true
            }
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

    /**
     * The mixer's own faders, read from the mixer rather than through the
     * visualizer. Application-scoped for the volume keys, which need levels
     * with no screen in front.
     */
    val mdxmMixer by lazy {
        com.sheinsez.mdropdx12.remote.network.vban.MdxmMixer(vbanSession, applicationScope)
    }

    /** Moves the linked faders with no Activity in front. */
    val linkedFaders by lazy {
        LinkedFaderController(mdxLink, mdxmMixer, applicationScope, linkedFaderKeys, rockerStep)
    }

    /**
     * The one place that knows there are two ways to reach the visualizer.
     *
     * Application-scoped for the same reason as the session it routes through:
     * a link has to outlive the screen that happened to be in front. Declared
     * after both carriers so neither is constructed from inside the other's
     * initializer.
     */
    val mdxLink by lazy {
        com.sheinsez.mdropdx12.remote.network.link.MdxLink(
            vbanSession,
            connectionManager,
            applicationScope,
            foreground,
            nowPlayingShown,
        )
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) {
                startedActivities++
                _foreground.value = true
            }

            override fun onActivityStopped(activity: android.app.Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                _foreground.value = startedActivities > 0
            }

            // A screen opening is the end of an Exit. The process often
            // outlives one, so this is where the service comes back.
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {
                exited.value = false
            }
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        // The service is started and stopped by the setting, so turning it off
        // takes the notification away immediately rather than at the next
        // launch -- and by the header's Exit, which stops it without
        // changing the setting.
        applicationScope.launch {
            combine(backgroundServiceEnabled, exited) { on, gone -> on && !gone }
                .distinctUntilChanged()
                .collect { on ->
                    val intent = Intent(this@MdrApp, RemoteForegroundService::class.java)
                    if (on) ContextCompat.startForegroundService(this@MdrApp, intent)
                    else stopService(intent)
                }
        }
    }
}
