package com.sheinsez.mdropdx12.remote.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.net.wifi.WifiManager
import android.util.Log
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import com.sheinsez.mdropdx12.remote.MainActivity
import com.sheinsez.mdropdx12.remote.MdrApp
import com.sheinsez.mdropdx12.remote.network.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the app reachable behind other apps, and optionally presents it as a
 * media device so the volume keys reach the PC's faders from anywhere.
 *
 * The service type is `connectedDevice`, never `mediaPlayback`, in both modes.
 * That is what this actually is — a remote control for a machine on the network
 * — and claiming media playback while playing none is a promise the platform
 * increasingly checks. The media SESSION is a separate thing layered on top,
 * and it needs no such claim to receive the keys.
 */
class RemoteForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private var session: MediaSession? = null
    private var dropJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Wi-Fi filters multicast and broadcast with the screen off, and the
        // PC announces itself by UDP beacon -- so without this the background
        // service would be awake and deaf.
        runCatching {
            val wifi = applicationContext
                .getSystemService(WifiManager::class.java)
            multicastLock = wifi?.createMulticastLock("MilkRemote")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        // A foreground service that cannot start must not take the app down
        // with it. The platform refuses a type whose permissions are not all
        // held, and it refuses by throwing -- which crashed the app on every
        // launch while the setting was on, with no way back except clearing
        // its data.
        val note = buildNotification("Connecting…")
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID, note,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(NOTIFICATION_ID, note)
            }
        }.isSuccess
        if (!started) {
            stopSelf()
            return
        }

        val app = application as MdrApp

        scope.launch {
            combine(
                app.connectionManager.connectionState,
                app.mediaDeviceEnabled,
            ) { state, wantMedia -> state to wantMedia }
                .collect { (state, wantMedia) ->
                    val connected = state == ConnectionState.Connected
                    Log.d(TAG, "state=$state wantMedia=$wantMedia session=${session != null}")
                    updateNotification(state)

                    if (wantMedia && connected) {
                        // Back before the timeout: the session it was about to
                        // lose is the one it still has.
                        dropJob?.cancel()
                        dropJob = null
                        acquireSession()
                    } else if (!wantMedia) {
                        dropJob?.cancel()
                        dropJob = null
                        releaseSession()
                    } else if (session != null && dropJob == null) {
                        // Connected is lost. Do NOT drop the session at once:
                        // this connection flaps -- a PC restart, a Wi-Fi blip --
                        // and surrendering the volume keys to whatever else is
                        // playing, then taking them back seconds later, is worse
                        // than holding them through a short gap.
                        dropJob = scope.launch {
                            delay(MEDIA_DROP_GRACE_MS)
                            releaseSession()
                            dropJob = null
                        }
                    }
                }
        }
    }

    private fun acquireSession() {
        if (session != null) return
        val app = application as MdrApp
        val controller = app.linkedFaders

        // Guarded for the same reason startForeground is: taking the volume
        // keys is an optional extra, and no optional extra is worth crashing
        // the app that the user is holding.
        // Guarded, but never silently: a swallowed exception here is a feature
        // that does nothing with no way to find out why.
        session = runCatching { buildSession(controller) }
            .onFailure { Log.w(TAG, "media session not created", it) }
            .getOrNull()
        Log.d(TAG, "acquireSession -> ${if (session != null) "active" else "failed"}")
    }

    private fun buildSession(controller: LinkedFaderController): MediaSession =
        MediaSession(this, "MilkRemote").apply {
            // A callback, even an empty one, is what makes the framework treat
            // this as a session that handles media buttons. Without it the
            // session was active and PLAYING and still not the media button
            // session -- "Media button session is null" -- so the volume keys
            // went to the local stream and onAdjustVolume was never called.
            // The Handler is not optional. setCallback builds one from the
            // CALLING thread's looper, and this runs on a coroutine worker
            // that has none -- which threw, and left the feature doing nothing
            // at all until the exception was logged rather than swallowed.
            setCallback(object : MediaSession.Callback() {}, Handler(Looper.getMainLooper()))
            // Metadata for the same reason: a session with none is not a
            // well-formed one to the parts of the system that pick a target,
            // and it is what the volume dialog shows while the rocker is held.
            setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "PC volume")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "MilkRemote")
                    .build(),
            )
            // RELATIVE, and deliberately not a playback state that says we are
            // playing anything. The rocker adjusts THIS device's volume, which
            // is exactly what a remote control is, and each press arrives as a
            // direction rather than a level.
            setPlaybackToRemote(object : VolumeProvider(
                VOLUME_CONTROL_RELATIVE, MAX_VOLUME, MAX_VOLUME / 2,
            ) {
                override fun onAdjustVolume(direction: Int) {
                    Log.d("MdrLinkedFaders", "onAdjustVolume direction=$direction")
                    if (direction == 0) return
                    controller.nudge(direction > 0)
                }
            })
            // STATE_PLAYING, and it has to be.
            //
            // Marked PAUSED this was honest and useless: Android routes the
            // volume keys to a session only while it considers that session to
            // be playing, so the rocker reached the faders exactly as long as
            // the app had focus, which is the case that never needed a session
            // at all.
            //
            // Claiming playback is NOT the same as taking audio focus, and this
            // deliberately does not take it -- that is the thing that would
            // pause whatever is actually playing. Nothing else is muted or
            // stopped by this; the rocker is redirected, which is the whole
            // point of the option and why it is off by default.
            //
            // No actions are advertised, so no transport controls appear that
            // would do nothing if pressed.
            setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                    .setActions(0L)
                    .build(),
            )
            isActive = true
        }

    private fun releaseSession() {
        session?.let {
            it.isActive = false
            it.release()
        }
        session = null
    }

    private fun buildNotification(text: String): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Background connection",
                    NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while MilkRemote stays connected to the PC."
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MilkRemote")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(state: ConnectionState) {
        val text = when (state) {
            ConnectionState.Connected -> "Connected to the PC"
            ConnectionState.AuthPending -> "Waiting for the PC to allow this device"
            ConnectionState.Connecting -> "Connecting…"
            ConnectionState.Disconnected -> "Not connected"
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        runCatching { multicastLock?.release() }
        multicastLock = null
        releaseSession()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val TAG = "MdrLinkedFaders"
        const val CHANNEL_ID = "mdr_background"
        const val NOTIFICATION_ID = 4271
        /** Long enough to ride out a PC restart or a Wi-Fi blip, short enough to give the keys back. */
        const val MEDIA_DROP_GRACE_MS = 10_000L
        const val MAX_VOLUME = 100
    }
}
