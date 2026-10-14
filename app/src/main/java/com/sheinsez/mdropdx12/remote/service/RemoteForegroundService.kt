package com.sheinsez.mdropdx12.remote.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.net.wifi.WifiManager
import android.util.Log
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import com.sheinsez.mdropdx12.remote.MainActivity
import com.sheinsez.mdropdx12.remote.MdrApp
import com.sheinsez.mdropdx12.remote.R
import com.sheinsez.mdropdx12.remote.VolumeRocker
import com.sheinsez.mdropdx12.remote.network.CommandBuilder
import com.sheinsez.mdropdx12.remote.network.link.LinkState
import com.sheinsez.mdropdx12.remote.network.link.LinkStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the app reachable behind other apps, and holds the app's media
 * sessions -- the stream's player, and the volume keys' when nothing plays
 * here. See [SessionPlan] for why those are two.
 *
 * The service type is `connectedDevice` always, and `mediaPlayback` ONLY
 * while the VBAN stream is rendering.
 *
 * The rule here used to be "connectedDevice, never mediaPlayback", and it was
 * right for as long as the app only relayed commands: this is a remote control
 * for a machine on the network, and claiming media playback while playing none
 * is a promise the platform increasingly checks. Once real PCM reaches an
 * AudioTrack the premise changes — the claim becomes a description, and it is
 * what exempts the socket from Doze's network restrictions while the screen is
 * off. The media SESSION remains a separate thing layered on top, and still
 * needs no such claim to receive the keys.
 *
 * WHILE THE STREAM PLAYS, THE SESSION IS A PLAYER. A watch, the lock screen
 * and a headset's buttons all find a phone's player through its media
 * session, and for a long time this app had none while it played -- the
 * keys' session was deliberately let go so they reached the headphones -- so
 * none of them had anything to talk to. Now Stop stops the stream, Play
 * starts it, Pause mutes it, and the volume is whatever the keys move. See
 * [StreamTransport] for every one of those decisions.
 */
class RemoteForegroundService : Service() {

    /**
     * ON THE MAIN THREAD, where onDestroy runs, so cancelling this in
     * onDestroy actually stops the collectors.
     *
     * It ran on Default, and a collector already in flight on a pool thread
     * carried on past the cancel: when the header's Exit dropped the link and
     * stopped the service in the same instant, the "Not connected" update was
     * posted AFTER onDestroy had cleared the notification, and an ongoing
     * MilkRemote notification outlived its service. Nothing here blocks --
     * every send hands off to its own I/O dispatcher -- and the media sessions
     * it builds belong on the main thread anyway.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The stream's player. What the notification, a watch and a headset talk to. */
    private var player: MediaSession? = null

    /**
     * The media-device option's session, for the volume keys while nothing
     * plays here. See [SessionPlan] for why it is a second session.
     */
    private var keys: MediaSession? = null

    /** The grace period before [keys] is given up after the link drops. */
    private var dropJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    /**
     * What the notification's status line currently says.
     *
     * Kept because re-asserting the foreground type means handing
     * startForeground a notification, and building a fresh one with
     * placeholder text would blank a correct line every time audio started.
     */
    private var lastText: String = "Connecting…"

    /**
     * The stream has played -- in this process, or ever, as remembered in
     * settings.
     *
     * What keeps Play on offer after a Stop. Without it a watch that stopped
     * the stream would lose its player in the same breath, and the press that
     * was supposed to start it again would have nowhere to go.
     */
    private var played = false

    private var playback = StreamPlayback.Stopped
    private var nowPlaying = StreamTransport.nowPlaying(null, "")

    /**
     * What the player was last told, so an unchanged tick sends nothing.
     *
     * The link state alone ticks every time the PC's socket blinks, and each
     * metadata or volume change is something a watch redraws.
     */
    private var shownPlayback: StreamPlayback? = null
    private var shownNowPlaying: NowPlaying? = null
    private var volumeRemote: Boolean? = null

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
        val note = buildNotification()
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val types = foregroundTypes()
                startForeground(NOTIFICATION_ID, note, types)
                assertedTypes = types
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
            // THE LINK, not the TCP socket.
            //
            // This gated the media session and the notification on
            // MDropDX12's own connection, which with that server switched off
            // is Disconnected for ever -- so the session was never acquired,
            // the volume keys never reached the PC, and the notification read
            // "Not connected" while every control on screen worked.
            //
            // Its reconnect loop also cycles Connecting/Disconnected every few
            // seconds, and this is the line that logs it. That churn has been
            // mistaken for the VBAN control session failing; it is the dead
            // TCP loop, and ControlLiveness cannot produce it at all, since
            // all it ever does is re-send one AUTH record.
            val keys = combine(
                app.mdxLink.state,
                app.mediaDeviceEnabled,
                app.vbanStreaming,
                app.rockerLocal,
                app.vbanSession.muted,
            ) { state, wantMedia, streaming, local, muted ->
                // THE KEYS GO BACK TO THE HEADPHONES WHILE THE STREAM PLAYS.
                //
                // This session takes the volume keys GLOBALLY -- dumpsys
                // reports it as "the media button session" for the whole
                // device -- so MainActivity's identical check could only ever
                // cover the case where the app is in front, which is the one
                // case that did not need covering. With audio in your ears
                // and the phone in your pocket, every press moved a fader on
                // the PC and nothing moved on the headset.
                //
                // Holding it is also the wrong default by the rule already
                // settled in VolumeRocker: with audio playing the keys are
                // the phone's unless the user says otherwise.
                //
                // Now that the stream has a session of its own, "back to the
                // headphones" means that session drives the phone's own media
                // volume -- which is also what a watch's volume control then
                // moves -- rather than that there is no session at all.
                val handOver = VolumeRocker.localOutputOwnsKeys(
                    streaming = streaming,
                    linkWhenStreaming = local,
                )
                Inputs(state, pcVolume = wantMedia && !handOver, streaming, muted)
            }
            // The host NAME, not the identity: a fresh PING0 reply arrives
            // every two seconds with its own transaction id, so the identity
            // is never equal to the last one and every ping re-ran all of
            // this and re-posted the notification.
            val pcName = app.vbanSession.identity
                .map { it?.hostName.orEmpty() }
                .distinctUntilChanged()
            val shown = combine(app.pcTrack, pcName) { track, name ->
                StreamTransport.nowPlaying(track, name)
            }
            // ONE collector, so the session is only ever reconfigured from
            // one place at a time.
            combine(keys, shown, app.streamPlayedBefore) { k, s, before ->
                Triple(k, s, before)
            }
                .collect { (inputs, meta, before) ->
                    if (before) played = true
                    applyInputs(inputs, meta)
                }
        }

        // ASK WHAT IS PLAYING when the relay comes up with the session
        // showing the stream.
        //
        // A TRACK| push only arrives when the song CHANGES, so a session
        // shown halfway through a song would say "PC audio" until the next
        // one. STATE makes the visualizer say it now. Asked on the edge
        // rather than polled: once subscribed, the pushes keep it current --
        // and not on EVERY edge, which on a flapping link undid them.
        scope.launch {
            var lastAsk: Long? = null
            combine(app.mdxLink.ready, app.nowPlayingShown) { ready, shownNow -> ready && shownNow }
                .distinctUntilChanged()
                .collect { up ->
                    if (!up) return@collect
                    val now = System.currentTimeMillis()
                    val since = lastAsk?.let { now - it }
                    if (StreamTransport.shouldAskState(app.pcTrack.value != null, since)) {
                        lastAsk = now
                        app.mdxLink.send(CommandBuilder.state())
                    }
                }
        }
    }

    private data class Inputs(
        val state: LinkState,
        /** The volume keys should move the PC's linked faders. */
        val pcVolume: Boolean,
        val streaming: Boolean,
        val muted: Boolean,
    )

    private fun applyInputs(inputs: Inputs, meta: NowPlaying) {
        val connected = inputs.state is LinkState.Live
        if (inputs.streaming) played = true
        playback = StreamTransport.playback(inputs.streaming, inputs.muted)
        nowPlaying = meta
        lastText = statusText(inputs.state)

        // RE-ASSERT THE TYPE WHEN AUDIO STARTS OR STOPS.
        //
        // foregroundTypes() was read once, in onCreate, where audio is almost
        // never already playing -- so the mediaPlayback claim that exempts
        // the socket from Doze's network restrictions was made only by
        // accident, back when vbanStreaming answered true for a link that
        // was merely up. Now that it answers honestly, nothing would ever add
        // the type and a screen-off stream would be throttled.
        applyForegroundTypes(inputs.streaming)

        val plan = StreamTransport.plan(
            audioOn = inputs.streaming,
            playedBefore = played,
            pcVolumeWanted = inputs.pcVolume,
            connected = connected,
        )
        Log.d(TAG, "state=${inputs.state} pcVolume=${inputs.pcVolume} playback=$playback " +
            "plan=$plan")

        if (plan.player) showPlayer(remoteVolume = inputs.pcVolume) else releasePlayer()

        when {
            plan.pcVolume -> {
                // Back before the timeout: the session it was about to lose
                // is the one it still has.
                dropJob?.cancel()
                dropJob = null
                holdKeys()
            }
            keys != null && inputs.pcVolume && !inputs.streaming -> {
                // Connected is lost. Do NOT drop the session at once: this
                // connection flaps -- a PC restart, a Wi-Fi blip -- and
                // surrendering the volume keys to whatever else is playing,
                // then taking them back seconds later, is worse than holding
                // them through a short gap.
                if (dropJob == null) {
                    dropJob = scope.launch {
                        delay(MEDIA_DROP_GRACE_MS)
                        releaseKeys()
                        dropJob = null
                    }
                }
            }
            else -> {
                // The stream started (its player carries the keys now) or
                // the option was switched off: no grace for either.
                dropJob?.cancel()
                dropJob = null
                releaseKeys()
            }
        }
        (application as MdrApp).nowPlayingShown.value = player != null
        postNotification()
    }

    /**
     * Guarded for the same reason startForeground is: a media session is an
     * optional extra, and no optional extra is worth crashing the app that
     * the user is holding. Guarded, but never silently: a swallowed
     * exception here is a feature that does nothing with no way to find out
     * why.
     */
    private fun buildOrNull(what: String, build: () -> MediaSession): MediaSession? =
        runCatching(build)
            .onFailure { Log.w(TAG, "$what session not created", it) }
            .getOrNull()
            .also { Log.d(TAG, "$what session -> ${if (it != null) "built" else "failed"}") }

    /**
     * Both sessions take the same transport callback.
     *
     * A callback is what makes the framework treat a session as one that
     * handles media buttons. Without one the session was active and PLAYING
     * and still not the media button session -- "Media button session is
     * null" -- so the volume keys went to the local stream and onAdjustVolume
     * was never called.
     *
     * It used to be empty, because the session only carried the keys. Now it
     * is the stream's transport, and a headset's one button arrives here too:
     * the framework turns PLAY_PAUSE into onPlay or onPause from the state we
     * last published. The keys' session gets it as well, so that whichever of
     * the two the system hands a press to, the press means the same thing.
     *
     * The Handler is not optional. setCallback builds one from the CALLING
     * thread's looper, and this runs on a coroutine worker that has none --
     * which threw, and left the feature doing nothing at all until the
     * exception was logged rather than swallowed.
     */
    private fun MediaSession.takeTransport() {
        val stream = (application as MdrApp).stream
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() = stream.handle(TransportCommand.Play)
            override fun onPause() = stream.handle(TransportCommand.Pause)
            override fun onStop() = stream.handle(TransportCommand.Stop)
            override fun onCustomAction(action: String, extras: Bundle?) {
                if (action == StreamTransport.CUSTOM_STOP) stream.handle(TransportCommand.Stop)
            }
        }, Handler(Looper.getMainLooper()))
        setSessionActivity(openAppIntent())
    }

    private fun showPlayer(remoteVolume: Boolean) {
        val s = player
            ?: buildOrNull("player") { MediaSession(this, "MilkRemote").apply { takeTransport() } }
                ?.also { player = it }
            ?: return
        showPlayback(s, remoteVolume)
        // Active only once it has something to say: a session that goes
        // active before its first state is a blank player for an instant.
        if (!s.isActive) s.isActive = true
    }

    private fun holdKeys() {
        if (keys != null) return
        keys = buildOrNull("PC volume") {
            MediaSession(this, "MilkRemote PC volume").apply {
                takeTransport()
                showPcVolume(this)
                isActive = true
            }
        }
    }

    /**
     * The stream, as a player: what it is doing, what is playing, and which
     * volume a controller moves.
     */
    private fun showPlayback(s: MediaSession, remoteVolume: Boolean) {
        routeVolume(s, remoteVolume)
        if (shownNowPlaying != nowPlaying) {
            // Blank fields are left out rather than sent empty, which some
            // controllers draw as a line with nothing on it.
            val m = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, nowPlaying.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, nowPlaying.artist)
            if (nowPlaying.album.isNotBlank()) {
                m.putString(MediaMetadata.METADATA_KEY_ALBUM, nowPlaying.album)
            }
            s.setMetadata(m.build())
            shownNowPlaying = nowPlaying
        }
        if (shownPlayback != playback) {
            // A live feed has no position, so none is claimed: a controller
            // that is given one draws a progress bar for a song with no end.
            val state = PlaybackState.Builder()
                .setState(
                    StreamTransport.stateFor(playback),
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (playback == StreamPlayback.Playing) 1f else 0f,
                )
                .setActions(StreamTransport.actionsFor(playback))
            // Stop as a button the system player will actually draw; see
            // StreamTransport.CUSTOM_STOP for why ACTION_STOP alone is not.
            if (StreamTransport.offersStop(playback)) {
                state.addCustomAction(
                    PlaybackState.CustomAction.Builder(
                        StreamTransport.CUSTOM_STOP, "Stop", R.drawable.ic_stream_stop,
                    ).build(),
                )
            }
            s.setPlaybackState(state.build())
            shownPlayback = playback
        }
    }

    /**
     * The media-device option with nothing playing here: the keys move the
     * PC. Set once, when its session is built; nothing about it changes.
     */
    private fun showPcVolume(s: MediaSession) {
        s.setPlaybackToRemote(fadersProvider())
        // Metadata for the same reason: a session with none is not a
        // well-formed one to the parts of the system that pick a target,
        // and it is what the volume dialog shows while the rocker is held.
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "PC volume")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "MilkRemote")
                .build(),
        )
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
        s.setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                .setActions(0L)
                .build(),
        )
    }

    /**
     * Point the session's volume at the phone's own output or at the PC's
     * linked faders.
     *
     * LOCAL is the phone's media volume -- the same number the stream card's
     * slider and the hardware keys move -- so a watch's volume control and
     * the rocker can never disagree about what they are changing.
     *
     * REMOTE is RELATIVE, and deliberately not a level: each press arrives as
     * a direction, and the controller steps the linked faders by the rocker's
     * own step, exactly as a key press does.
     */
    private fun routeVolume(s: MediaSession, remote: Boolean) {
        if (volumeRemote == remote) return
        if (remote) {
            s.setPlaybackToRemote(fadersProvider())
        } else {
            s.setPlaybackToLocal(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
        }
        volumeRemote = remote
    }

    private fun fadersProvider(): VolumeProvider {
        val controller = (application as MdrApp).linkedFaders
        return object : VolumeProvider(VOLUME_CONTROL_RELATIVE, MAX_VOLUME, MAX_VOLUME / 2) {
            override fun onAdjustVolume(direction: Int) {
                Log.d("MdrLinkedFaders", "onAdjustVolume direction=$direction")
                if (direction == 0) return
                controller.nudge(direction > 0)
            }
        }
    }

    private fun releasePlayer() {
        player?.let {
            it.isActive = false
            it.release()
        }
        player = null
        shownPlayback = null
        shownNowPlaying = null
        volumeRemote = null
        (application as? MdrApp)?.nowPlayingShown?.value = false
    }

    private fun releaseKeys() {
        keys?.let {
            it.isActive = false
            it.release()
        }
        keys = null
    }

    /**
     * connectedDevice always; mediaPlayback only while the VBAN stream is
     * actually rendering.
     *
     * The type is a claim the platform verifies, so claiming playback with
     * nothing playing is how a service gets killed. Adding it while audio
     * runs is what keeps the socket out of Doze's network restrictions and
     * the CPU up, which is the whole reason the stream needs a service at
     * all.
     */
    private fun foregroundTypes(): Int {
        val app = application as? MdrApp
        return typesFor(app?.vbanStreaming?.value == true)
    }

    private fun typesFor(streaming: Boolean): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (streaming) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        return types
    }

    /** Last types successfully asserted, so an unchanged tick costs nothing. */
    private var assertedTypes: Int = -1

    /**
     * Add or drop the mediaPlayback claim as audio starts and stops.
     *
     * Guarded like the original startForeground, and for the same reason: the
     * platform refuses a type whose permissions are not all held, and it
     * refuses by throwing. A refusal here must cost the Doze exemption, not
     * the service.
     */
    private fun applyForegroundTypes(streaming: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val types = typesFor(streaming)
        if (types == assertedTypes) return
        val ok = runCatching {
            startForeground(NOTIFICATION_ID, buildNotification(), types)
        }.onFailure { Log.w(TAG, "foreground type $types refused", it) }.isSuccess
        if (ok) assertedTypes = types
    }

    /**
     * Open the app the way its launcher icon does: the running copy brought
     * forward if there is one, a fresh start if not.
     *
     * THE LAUNCHER'S OWN INTENT, action and category included. A bare
     * component intent does not match the task's root intent, so with the app
     * already open behind something else Android started a SECOND MainActivity
     * on top of the first -- Back then showed an identical remote instead of
     * leaving. The notification and the player's "open app" on the lock
     * screen and a watch all come through here.
     */
    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * A press on the notification's own buttons, delivered to [onStartCommand].
     *
     * Android 13 and later draw a media notification's buttons from the
     * session and call the session, so these are only pressed on older
     * phones -- and on those they are the only buttons there are.
     */
    private fun transportIntent(command: TransportCommand): PendingIntent = PendingIntent.getService(
        this,
        1 + command.ordinal,
        Intent(this, RemoteForegroundService::class.java).setAction(ACTION_PREFIX + command.name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun transportAction(icon: Int, label: String, command: TransportCommand) =
        Notification.Action.Builder(Icon.createWithResource(this, icon), label, transportIntent(command))
            .build()

    /**
     * The one notification this service has, drawn for whichever session it
     * holds.
     *
     * While the stream is the session, it is a MEDIA notification carrying
     * the session's token. That token is how a watch finds the phone's player
     * at all: Wear OS and Galaxy watches both build their media controls from
     * the media notifications on the phone, and a session with no
     * notification behind it is a player nobody can see.
     */
    private fun buildNotification(): Notification {
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
        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
        // The PLAYER's token, never the keys' session: that one claims to be
        // playing so the rocker reaches it, and a watch handed it would draw
        // a Pause button over a stream that is not there.
        val s = player
        if (s == null) {
            return b.setContentTitle("MilkRemote").setContentText(lastText).build()
        }
        b.setContentTitle(nowPlaying.title)
            .setContentText(nowPlaying.artist)
            .setSubText(lastText)
            // A player on the lock screen is the point; what is playing on
            // the PC is no secret from whoever is holding its remote.
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        b.addAction(
            if (playback == StreamPlayback.Playing) {
                transportAction(android.R.drawable.ic_media_pause, "Pause", TransportCommand.Pause)
            } else {
                transportAction(android.R.drawable.ic_media_play, "Play", TransportCommand.Play)
            },
        )
        val compact = if (playback == StreamPlayback.Stopped) {
            intArrayOf(0)
        } else {
            b.addAction(transportAction(R.drawable.ic_stream_stop, "Stop", TransportCommand.Stop))
            intArrayOf(0, 1)
        }
        b.setStyle(
            Notification.MediaStyle()
                .setMediaSession(s.sessionToken)
                .setShowActionsInCompactView(*compact),
        )
        return b.build()
    }

    private fun statusText(state: LinkState): String = when (state) {
        is LinkState.Live ->
            if (state.tcp) "Connected to the PC" else "Connected through the mixer"
        LinkState.Reaching -> "Connecting…"
        is LinkState.MixerOnly -> LinkStateMachine.explain(state) ?: "Connected to the mixer"
        LinkState.Stale -> "The PC has stopped answering"
        is LinkState.Lost -> "The PC refused this phone"
        LinkState.Idle -> "Not connected"
    }

    private fun postNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = intent?.action
            ?.takeIf { it.startsWith(ACTION_PREFIX) }
            ?.removePrefix(ACTION_PREFIX)
            ?.let { name -> TransportCommand.values().firstOrNull { it.name == name } }
        if (command != null) (application as MdrApp).stream.handle(command)
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { multicastLock?.release() }
        multicastLock = null
        dropJob?.cancel()
        releasePlayer()
        releaseKeys()
        scope.cancel()
        // The platform takes the FOREGROUND notification away with the
        // service, but not one posted with notify() in the moments before:
        // that lands after, as an ordinary ongoing notification nobody can
        // swipe away. Belt to the main-thread scope's braces -- see [scope].
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    companion object {
        const val TAG = "MdrLinkedFaders"
        const val CHANNEL_ID = "mdr_background"
        const val NOTIFICATION_ID = 4271
        /** Long enough to ride out a PC restart or a Wi-Fi blip, short enough to give the keys back. */
        const val MEDIA_DROP_GRACE_MS = 10_000L
        const val MAX_VOLUME = 100
        private const val ACTION_PREFIX = "com.sheinsez.mdropdx12.remote.media."
    }
}
