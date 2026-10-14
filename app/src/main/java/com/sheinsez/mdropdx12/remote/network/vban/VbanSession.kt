package com.sheinsez.mdropdx12.remote.network.vban

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import com.sheinsez.mdropdx12.remote.audio.VbanAudioPlayer
import com.sheinsez.mdropdx12.remote.data.DeviceIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

enum class LostReason { Denied, Locked, BadPin, Timeout, Stopped }

sealed class VbanSessionState {
    object Idle : VbanSessionState()
    object Pinging : VbanSessionState()
    object AuthPending : VbanSessionState()
    object Streaming : VbanSessionState()
    data class Lost(val reason: LostReason) : VbanSessionState()
}

/**
 * One ephemeral-port socket carrying every sub-protocol.
 *
 * Ephemeral deliberately. The phone pings FROM this socket, so audio, frames
 * and TXT replies all come back to its source port — there is nothing to
 * bind. A fixed port would repeat the MdnsDiscovery trap, where a bind
 * failure swallowed by a catch leaves a feature silently dead, and it would
 * clash with any other VBAN app on the phone.
 */
class VbanSession(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<VbanSessionState>(VbanSessionState.Idle)
    val state: StateFlow<VbanSessionState> = _state

    private val _pcState = MutableStateFlow<VbanState?>(null)
    val pcState: StateFlow<VbanState?> = _pcState

    private val _lagMs = MutableStateFlow(0)
    val lagMs: StateFlow<Int> = _lagMs

    /**
     * The PC is sending silence because it has nothing to send -- no render
     * device, so its mix has no clock. Invisible in the audio itself; only
     * the starved counter shows it. See [SilenceDetector].
     */
    /**
     * Is audio actually playing on this phone?
     *
     * Distinct from the session being up, which it no longer implies: a
     * control-only link is Streaming in the protocol sense while playing
     * nothing at all. The stream card keys its Play button on this, not on
     * the session state.
     */
    private val _audioOn = MutableStateFlow(false)
    val audioOn: StateFlow<Boolean> = _audioOn

    /**
     * Paused from a media controller: audio is still ON, and silent.
     *
     * Only meaningful while [audioOn]; anything that turns audio off or
     * starts a session clears it, so a stream started later never comes up
     * mysteriously quiet.
     */
    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted

    private val _pcSilent = MutableStateFlow(false)
    val pcSilent: StateFlow<Boolean> = _pcSilent

    /**
     * The mixer has stopped answering pings.
     *
     * Distinct from the session state, which cannot tell: Streaming is only
     * ever left by the user stopping it or a fresh MDXM_AUTHSTATE arriving,
     * and a PC that has gone away sends neither. See [Reachability].
     */
    private val _unreachable = MutableStateFlow(false)
    val unreachable: StateFlow<Boolean> = _unreachable

    /**
     * The mixer's own client table, as of the last time it was asked.
     *
     * Asked when audio stalls rather than polled: it is a diagnosis, not a
     * reading, and the answer is only interesting when something is wrong.
     */
    private val _peers = MutableStateFlow<List<VbanPeer>>(emptyList())
    val peers: StateFlow<List<VbanPeer>> = _peers

    /**
     * `MDXM_ERR|msg=…` from the mixer: it refused something we sent.
     *
     * An event rather than a state, because each one belongs to a particular
     * command and two in a row are two refusals, not one.
     */
    private val _pcRefusal = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val pcRefusal: SharedFlow<String> = _pcRefusal

    /** What the mixer said was wrong, the last time it was asked. */
    private val _fault = MutableStateFlow(StreamFault.None)
    val fault: StateFlow<StreamFault> = _fault

    private val _identity = MutableStateFlow<VbanCodec.Ping0Reply?>(null)
    val identity: StateFlow<VbanCodec.Ping0Reply?> = _identity

    private val _frames = MutableSharedFlow<Pair<Int, ByteArray>>(extraBufferCapacity = 8)
    val frames: SharedFlow<Pair<Int, ByteArray>> = _frames

    /**
     * MDropDX12's records, relayed through the mixer, verbatim.
     *
     * THIS IS A FIREHOSE, NOT ANSWERS. mdx12 broadcasts every reply to every
     * client it has, so this carries replies to commands other clients sent --
     * the PC's own UI, another phone -- and unprompted engine pushes as well.
     * A consumer must filter by verb and must never read the next record as
     * the answer to what it just asked.
     *
     * Deliberately not a StateFlow: these are events, and conflating two
     * `PRESET=` pushes into one loses a preset change.
     */
    private val _relay = MutableSharedFlow<String>(extraBufferCapacity = 128)
    val relay: SharedFlow<String> = _relay

    /**
     * What the mixer says about its link to mdx12, or null if it has not said.
     *
     * Null is meaningfully different from a state with `enabled=0`: the first
     * means this PC has never answered a forwarding query -- an older mixer
     * that does not know the verb -- and the second means it answered and
     * forwarding is switched off.
     */
    private val _fwdState = MutableStateFlow<FwdState?>(null)
    val fwdState: StateFlow<FwdState?> = _fwdState

    /**
     * The mixer's own channels and devices: each complete `MDXM_STATE` reply,
     * as the records between its BEGIN and END.
     *
     * Raw on purpose. What a record means on a screen is not the session's
     * business, and [MdxmMixerRows] is where that is decided and tested.
     *
     * DROP_OLDEST, unlike [relay]. A relayed record is an event and losing
     * one loses news; this is a reading, and a newer one makes the older one
     * worthless -- so a consumer that falls behind should be handed the
     * latest, not made to work through a queue of stale ones.
     */
    private val _mixerState = MutableSharedFlow<List<String>>(
        extraBufferCapacity = 2,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val mixerState: SharedFlow<List<String>> = _mixerState

    /** Processes the mixer can see, from the last MDXM_FWDTARGETS answer. */
    private val _fwdTargets = MutableStateFlow<List<FwdTarget>>(emptyList())
    val fwdTargets: StateFlow<List<FwdTarget>> = _fwdTargets

    /** For sinks that under-report their own latency. Bluetooth hint ~150 ms. */
    var manualOffsetMs: Int = 0

    /** Which phone output the stream should render to; survives a restart. */
    var preferredOutputId: Int = com.sheinsez.mdropdx12.remote.audio.PhoneOutput.AUTOMATIC_ID
        set(value) {
            field = value
            player?.preferredOutputId = value
        }

    private var job: Job? = null
    private var socket: DatagramSocket? = null
    private var player: VbanAudioPlayer? = null
    private var target: Triple<InetAddress, Int, String>? = null
    private var txtFrame = 0L

    /** Where this session was told to connect, so the socket can be rebuilt. */
    private data class Params(
        val host: String,
        val port: Int,
        val pin: String,
        val streamName: String,
    )

    /**
     * Kept so [rebind] can start a fresh socket without the ViewModel.
     *
     * A rebuild is a repair, not a reconnection: the user did not ask for it
     * and must not see the link go down and come back. Reaching up to the
     * layer that holds the preferences would mean going through `start`,
     * which calls `stop`, which reports `Lost(Stopped)` -- a "Not connected"
     * flash and a dropped forwarding subscription, for a fault the session
     * can fix without either.
     */
    private var params: Params? = null

    /**
     * The network the current socket is stamped with.
     *
     * Android fixes that stamp when the socket is created and never updates
     * it, so this is the one thing that says whether the socket still belongs
     * to the network the phone is actually on. See [RebindPolicy].
     */
    private var boundNetwork: Network? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * The radio's own account of the link, as delivered to our callback.
     *
     * Asking `getNetworkCapabilities` directly returns `transportInfo`
     * redacted to null for an ordinary app -- the real thing is only handed
     * to a REGISTERED callback. We already register one for rebinding, so the
     * reading costs nothing extra; querying for it does not work at all.
     *
     * Volatile because it is written on the ConnectivityManager's thread and
     * read on the ping loop's.
     */
    @Volatile
    private var liveWifi: android.net.wifi.WifiInfo? = null

    /** This phone's id, as the mixer lists it. Resolved once per session. */
    private var myDeviceId: String = ""

    /**
     * One thread for everything this class puts on the wire.
     *
     * Ordering, and a single writer for `txtFrame`. The ping loop already
     * had its own IO thread; this gives the same guarantee to records sent
     * from a tap.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val sendDispatcher = Dispatchers.IO.limitedParallelism(1)

    // Replayed after any re-auth following peer expiry (an AP roam, a Wi-Fi
    // drop), or previews and audio silently stay off on every roam.
    private var wantAudio = true
    private var wantFrames = false
    private var wantFps = 2.0f

    /**
     * Whether this phone wants MDropDX12's replies relayed to it.
     *
     * NOT replayed with the other wants, and that is the point. The others go
     * back on the auth edge, which is enough for them because peer expiry on
     * this phone is always followed by a re-auth the user caused: audio
     * stopping is audible, so they press Stop and Play and the edge comes
     * round again.
     *
     * A subscription has no such symptom. Expiry produces no auth edge at all
     * -- nothing here observes it, `_state` stays Streaming, and the ping loop
     * keeps sending MDXM_VBAN rather than MDXM_AUTH -- so a replay keyed on
     * auth would never fire, commands would quietly stop arriving, and the UI
     * would still say everything was fine. A control channel whose only
     * symptom is buttons not working is the worst failure available here, so
     * this is re-asserted on every ping tick instead (§4.5 makes the verb
     * idempotent precisely so that is cheap).
     */
    private var wantFwdSub = false

    private val silence = SilenceDetector()
    private val reach = Reachability()

    // Is the mixer still ANSWERING us, which reachability cannot tell us --
    // it is credited by any packet, and mdxmixer answers PING0 even to a
    // device it has deauthorized. See ControlLiveness.
    private val control = ControlLiveness()
    private val stall = AudioStall()

    // And when re-authenticating has stopped helping, because the socket
    // itself is stamped with a network that no longer exists. See
    // RebindPolicy -- this is the only fault here that a new socket fixes and
    // nothing else does.
    private val rebinder = RebindPolicy()

    // And when the stream is locked, the network is fine, and playout is
    // running with no lead at all -- which pops continuously and recovers
    // only by hand. See BufferHealth.
    private val bufferHealth = BufferHealth()

    // And when the Wi-Fi DOWNLINK has collapsed, which no other instrument
    // here can see: everything else measures the link by sending something,
    // and the uplink survives a walk that kills the downlink. See
    // DownlinkHealth.
    private val downlinkHealth = DownlinkHealth()

    /**
     * How much room the Wi-Fi downlink has left.
     *
     * The one fault this app can neither fix nor work around, and therefore
     * the one most worth naming: no socket rebuild, buffer depth or profile
     * change carries a stream the radio cannot deliver. Saying so beats a
     * reconnect spinner that implies something is still being tried.
     */
    private val _downlink = MutableStateFlow(Downlink.Unknown)
    val downlink: StateFlow<Downlink> = _downlink

    /** So the first reading says where it came from, even when it is Unknown. */
    private var loggedLinkSource = false

    /**
     * Sequence gaps measured where packets ARRIVE, before the playout queue.
     *
     * `inserts` is counted inside `player.submit`, which runs on the far side
     * of a bounded DROP_OLDEST channel -- so a packet this phone discarded
     * because playout fell behind is indistinguishable there from one the
     * network lost. The two want opposite responses, and for eleven minutes
     * the diagnostics called a phone-side discard "network loss" while
     * `net=3 ms` and the PC reported `dropped=0`.
     *
     * Counted here, the difference is the answer: gaps seen at arrival are
     * the network's, and `inserts` beyond them are ours.
     */
    private var arrivalGaps = 0
    private var lastArrivedNuFrame: Long? = null
    private val pingSentAt = mutableMapOf<Long, Long>()
    private var networkMs = 0
    private var srcLatencyMs = 0

    companion object {
        /** Public so the reachability tolerance can be checked against the expiry. */
        const val PING_INTERVAL_MS = 2_000L

        /** The server expires a peer after 10 s without a ping. */
        const val PEER_EXPIRY_MS = 10_000L

        /**
         * How long a glitch stays on screen after it stops happening.
         *
         * Fifteen ticks, so about thirty seconds. Long enough that somebody
         * who heard a pop and looked down still finds out what it was; short
         * enough that it clears, which is the whole difference between this
         * and the lifetime latch it replaces. The LOG does not use it -- a
         * log line wants the instant, not the memory.
         */
        const val TROUBLE_MEMORY_TICKS = 15

        /**
         * Pure, so the machine can be checked without a socket.
         *
         * Denied and Locked are TERMINAL with no auto-retry: the server
         * counts strikes per IP, so a client that keeps trying turns a
         * 60-second lockout into a permanent one.
         */
        fun nextState(current: VbanSessionState, auth: AuthState): VbanSessionState = when (auth) {
            AuthState.Ok -> VbanSessionState.Streaming
            AuthState.Pending -> VbanSessionState.AuthPending
            AuthState.Denied -> VbanSessionState.Lost(LostReason.Denied)
            AuthState.Locked -> VbanSessionState.Lost(LostReason.Locked)
            AuthState.BadPin -> VbanSessionState.Lost(LostReason.BadPin)
        }
    }

    /**
     * Bring the link up.
     *
     * [profile] NULL MEANS CONTROL ONLY: no AudioTrack is built and no audio
     * is asked for. That distinction is the whole point of this signature.
     * The session used to exist solely to play audio -- the only way to start
     * one was the Play button -- so with nothing playing there was no socket,
     * no authentication and no way to send a command at all. Every screen
     * reported "Not connected" and every button did nothing, on a PC that was
     * running and reachable.
     *
     * Audio can be added later with [enableAudio] and taken away again with
     * [stopAudio], neither of which disturbs the link.
     */
    fun start(
        host: String,
        port: Int,
        pin: String,
        streamName: String,
        profile: LatencyProfile?,
        wantAudio: Boolean = profile != null,
    ) {
        stop()
        _state.value = VbanSessionState.Pinging
        this.wantAudio = wantAudio
        _audioOn.value = wantAudio
        _muted.value = false
        player = profile?.let { p ->
            VbanAudioPlayer(context, p).also { it.preferredOutputId = preferredOutputId }
        }
        params = Params(host, port, pin, streamName)
        watchNetwork()
        launchIo()
    }

    /**
     * Open the socket and run the three loops on it.
     *
     * Separate from [start] because the socket is the one part of a session
     * that can need replacing while everything above it -- the wants, the
     * player, the approval -- is still good. See [rebind].
     */
    private fun launchIo() {
        val p = params ?: return
        val host = p.host
        val port = p.port
        val pin = p.pin
        val streamName = p.streamName
        job = scope.launch(Dispatchers.IO) {
            val sock = runCatching {
                DatagramSocket().apply {
                    soTimeout = 1000
                    // Room to absorb a burst while this thread is busy.
                    //
                    // The default is small enough that a stream of ~188
                    // packets a second overruns it during any stall, and the
                    // packets lost that way are lost ON THIS PHONE while the
                    // PC reports dropped=0 -- which is exactly how they were
                    // first reported. A request, not a guarantee: the OS may
                    // give less.
                    runCatching { receiveBufferSize = 512 * 1024 }
                }
            }.getOrNull()
            if (sock == null) {
                _state.value = VbanSessionState.Lost(LostReason.Timeout)
                return@launch
            }
            socket = sock
            // STAMPED NOW, AND NEVER AGAIN. Whatever network is default at
            // this instant is the one every datagram from this socket will be
            // routed onto for its whole life, so this is the only moment the
            // pairing can be recorded. Compared later against the network the
            // phone is actually on; see the callback in watchNetwork.
            boundNetwork = runCatching {
                context.getSystemService(ConnectivityManager::class.java)?.activeNetwork
            }.getOrNull()
            val addr = runCatching { InetAddress.getByName(host) }.getOrNull()
            if (addr == null) {
                _state.value = VbanSessionState.Lost(LostReason.Timeout)
                return@launch
            }
            target = Triple(addr, port, streamName)
            val deviceId = DeviceIdentity.get(context)
            // Kept, because finding this phone in the mixer's client table
            // happens on the receive path, which is not a suspend context and
            // must not be made one to re-read a value that cannot change.
            myDeviceId = deviceId
            val deviceName = Build.MODEL ?: "Android"
            launch { pingLoop(sock, addr, port, streamName, pin, deviceId, deviceName) }
            // Its own thread, because its whole job is to block on the track.
            launch(Dispatchers.IO) { playoutLoop() }
            receiveLoop(sock, streamName)
        }
    }

    /**
     * Replace the socket, keeping the session.
     *
     * NOT a reconnection. The approval, the wants, the player and the output
     * device all survive; only the file descriptor is new. The state drops to
     * `Pinging` because that is honestly where a fresh socket starts -- it
     * has to re-authenticate -- but the user is never shown "Not connected"
     * and the audio track is not rebuilt, so a rebind costs one round trip.
     *
     * The player is kept deliberately. Its queue holds audio from before the
     * gap, which arrives as a stream that has jumped a long way forward --
     * precisely the case `JitterPolicy.RESTART_BEHIND_PACKETS` exists to
     * recognise, so the re-lock is already handled and rebuilding the track
     * would only add a device switch to a fault the user did not cause.
     *
     * Safe to call from inside the ping loop: the relaunch is owned by
     * `scope`, not by the job being cancelled, so cancelling the caller does
     * not cancel the repair.
     */
    private fun rebind(why: String) {
        if (params == null) return
        android.util.Log.w("MdrVban", "rebinding the socket: $why")
        val old = job
        job = null
        runCatching { socket?.close() }
        socket = null
        target = null
        // The debts belong to the dead socket. Carried over, the fresh one
        // would be condemned for silence it was never given a chance to break.
        reach.reset()
        control.reset()
        stall.reset()
        // The lead is gone with the socket, and the next packet will be the
        // first of a new arrival sequence. Judging either against what the
        // dead socket saw would re-prime on the gap the outage itself made.
        bufferHealth.reset()
        lastArrivedNuFrame = null
        _unreachable.value = false
        // AND THE PC'S LAST WORD WITH THEM. These are readings, not facts,
        // and holding them across a dead socket is how a session that had not
        // reached the mixer for fifteen minutes went on reporting
        // `emitting=true peers=1` -- which read as a healthy stream with a
        // silent sink and sent a whole debugging session after the phone's
        // audio routing.
        _pcState.value = null
        _peers.value = emptyList()
        _fault.value = StreamFault.None
        _fwdSubAsserted.value = false
        _state.value = VbanSessionState.Pinging
        scope.launch {
            runCatching { old?.cancelAndJoin() }
            launchIo()
        }
    }

    /**
     * Rebuild the socket the moment the phone changes network.
     *
     * The backstop in the ping loop would get there on its own, but only
     * after sixteen seconds of a link that is already dead -- and it cannot
     * say why. This can: a default network that is not the one the socket was
     * stamped with is a certainty, not a suspicion, and it is available the
     * instant it happens.
     *
     * Registered for the session rather than per socket, and guarded, because
     * a remote control that cannot watch the network is merely slower to
     * recover, which is not worth a crash.
     */
    private fun watchNetwork() {
        if (netCallback != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val was = boundNetwork ?: return
                // Only a DIFFERENT network. onAvailable also fires for the
                // one already carrying us, and rebuilding a working socket
                // because it was announced again would drop audio for a round
                // trip every time the phone re-evaluated its connection.
                if (was != network) rebind("default network changed")
            }

            override fun onLost(network: Network) {
                // Nothing to rebuild onto yet. The socket is already dead,
                // but a new one would be stamped with whatever replaces this
                // -- or with nothing at all -- so wait for onAvailable.
                if (network == boundNetwork) {
                    android.util.Log.i("MdrVban", "the network carrying the session went away")
                }
                liveWifi = null
            }

            /**
             * The only place the link rates can actually be read.
             *
             * Fires on every rate re-negotiation, which is frequent -- so
             * this is also fresher than anything the 2 s tick could fetch.
             */
            override fun onCapabilitiesChanged(
                network: Network,
                caps: android.net.NetworkCapabilities,
            ) {
                liveWifi =
                    if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                        caps.transportInfo as? android.net.wifi.WifiInfo
                    } else {
                        null
                    }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
            .onSuccess { netCallback = cb }
            .onFailure { android.util.Log.w("MdrVban", "cannot watch the network", it) }
    }

    private suspend fun pingLoop(
        sock: DatagramSocket,
        addr: InetAddress,
        port: Int,
        streamName: String,
        pin: String,
        deviceId: String,
        deviceName: String,
    ) {
        var transaction = 1L
        while (currentCoroutineContext().isActive) {
            if (_state.value is VbanSessionState.Lost) return
            // PING0 is both the subscription renewal and the latency probe:
            // the reply echoes this id, and RTT/2 is the network term.
            val req = VbanCodec.buildPing0Request("VBAN Service", transaction)
            pingSentAt[transaction] = System.currentTimeMillis()
            if (pingSentAt.size > 32) pingSentAt.keys.take(16).forEach { pingSentAt.remove(it) }
            // A failed send is swallowed: on a roaming phone ENETUNREACH is
            // routine and the next tick two seconds later usually succeeds.
            // The watchdog, not this, decides the session is gone.
            runCatching { sock.send(DatagramPacket(req, req.size, addr, port)) }
            reach.onPingSent()
            _unreachable.value = !reach.reachable
            _counters.value = readCounters()
            // THE DOWNLINK, every tick, whether or not audio is playing.
            //
            // Not gated on wantAudio: the rate collapsing is worth knowing
            // before Play is pressed, and the commands travel the same link.
            val link = com.sheinsez.mdropdx12.remote.network.WifiLink.read(context, liveWifi)
            val wasDownlink = _downlink.value
            _downlink.value = downlinkHealth.onTick(link.rxMbps)
            if (_downlink.value != wasDownlink || !loggedLinkSource) {
                loggedLinkSource = true
                // The SOURCE is logged, not just the numbers. A reader that
                // silently answers "unknown" is the same shape of fault as
                // every lying instrument found tonight: the feature does
                // nothing and there is no way to find out why.
                android.util.Log.i(
                    "MdrAudio",
                    "downlink ${_downlink.value} rx=${link.rxMbps}Mbps " +
                        "tx=${link.txMbps}Mbps rssi=${link.rssi}dBm via ${link.source}",
                )
            }
            // Logged only when something is actually wrong, which is why it
            // can simply stay. Audio faults have been reported three times now
            // and each round cost one install to add logging and another to
            // take it out; a line that is silent on a healthy stream is worth
            // more than a cleaner tick. Every field distinguishes one cause
            // from another.
            // `rising`, NOT a lifetime total. This line is documented as
            // silent on a healthy stream, and that silence is the signal --
            // but every counter it used to test is cumulative, and a track
            // underruns about nine times while its cushion first fills, so it
            // latched within seconds of every session and never cleared. It
            // has printed every two seconds for as long as it has existed.
            if (_counters.value.rising || _pcSilent.value) {
                android.util.Log.i(
                    "MdrAudio",
                    "${_counters.value} selfInflicted=${_counters.value.selfInflicted} " +
                        "| pc emitting=${_pcState.value?.emitting} " +
                        "peers=${_pcState.value?.peers} starved=${_pcState.value?.starved} " +
                        "dropped=${_pcState.value?.dropped} depthMs=${_pcState.value?.depthMs} " +
                        "behindMs=${_pcState.value?.behindMs} | lagMs=${_lagMs.value} " +
                        "fault=${_fault.value}",
                )
            }
            // THE LAG BREAKDOWN, UNCONDITIONALLY, at debug level.
            //
            // The line above is deliberately silent on a healthy stream, and
            // that is right for a FAULT line. It is wrong for this one: lag
            // is a number somebody is actively trying to reduce on a stream
            // that is working perfectly, and gating the breakdown on trouble
            // hides it in exactly the case it is wanted.
            //
            // lagMs is a sum of five terms and showing only the total made
            // every latency report unanswerable -- four separate theories
            // about one figure were argued and discarded before anyone could
            // see which term was large. Measured here once it was visible: of
            // 360 ms on a wired Pixel, ~238 was outputLatencyMs, the sink's
            // own, three times everything else combined.
            android.util.Log.d(
                "MdrLag",
                "lagMs=${_lagMs.value} = net=$networkMs + src=$srcLatencyMs" +
                    " + buffered=${_counters.value.bufferedMs}" +
                    " + output=${_counters.value.outputLatencyMs}" +
                    " + manual=$manualOffsetMs" +
                    // queued is what is ACTUALLY in the track; buffered is
                    // its allocated SIZE. They were the same number until
                    // now, which is how latency sitting at the ceiling was
                    // indistinguishable from latency sitting at the floor.
                    "  | queued=${player?.queuedMs}ms" +
                    // THE RADIO, EVERY TICK, not only when its verdict
                    // changes.
                    //
                    // DownlinkHealth logs on transitions, which is right for
                    // a verdict and useless for proving the verdict was
                    // informed. After a walk that stayed Fine throughout,
                    // there was no way to tell a live reading of a healthy
                    // link from a reading frozen at connect time -- both say
                    // Fine, and the feature would look identical either way.
                    // An independent sampler had to be run on the phone to
                    // establish that the rate really had stayed above the
                    // threshold. One number on a line that already prints
                    // retires that whole exercise.
                    "  | rx=${link.rxMbps} tx=${link.txMbps} rssi=${link.rssi}",
            )
            // AUDIO STOPPING IS THE SIGNAL, and it is a different one from
            // the mixer going quiet altogether.
            //
            // A peer can keep getting ping replies and state records while its
            // audio has stopped dead: the emission grant lost with an expired
            // peer, the sender re-armed for somebody else. Reachability calls
            // all of those fine, because by its measure they are -- so the
            // test has to be on the thing that was actually asked for.
            //
            // Graduated, because the two causes cost very different amounts to
            // address. Re-asking for audio is one small record and fixes a lost
            // grant without disturbing a working control channel; dropping to
            // Pinging re-authenticates and replays everything, which is worth
            // it only once the cheap answer has been tried.
            if (_state.value == VbanSessionState.Streaming) {
                // Gated on the PC SAYING IT IS SENDING, which is the
                // difference between a stall and an idle PC.
                //
                // No audio arriving is only a fault if some was being sent.
                // With nothing playing on the PC the mixer reports
                // `emitting=0` and sends nothing, correctly -- and reading
                // that as a stall produced exactly the cycle it was meant to
                // cure: nudge at two seconds, re-authenticate at six, re-arm,
                // round again every few seconds for as long as the PC had
                // nothing to play.
                //
                // `emitting=1` with no packets reaching us is the real stall.
                // `emitting=1` with packets full of zeroes is a third thing
                // again, and SilenceDetector already names that one.
                val emitting = _pcState.value?.emitting == true
                when (stall.check(System.currentTimeMillis(), wantAudio && emitting)) {
                    // ASK THE MIXER WHY, rather than guess from the silence.
                    //
                    // A timeout can only say "no audio for two seconds", and
                    // every cause of that wants a different answer: an expired
                    // peer needs re-authenticating, a dropped emission grant
                    // needs one small record, a path that has gone needs
                    // neither because neither fixes it, and a PC waiting for
                    // someone to click Approve needs to be left alone.
                    //
                    // The mixer's own client table separates all four -- an
                    // expired peer is simply ABSENT from it -- so the
                    // suspicion asks a question and the answer decides. See
                    // handleRecord, where the batch is diagnosed.
                    AudioStall.Action.Nudge ->
                        sendTxt(sock, addr, port, streamName, MdxmCommands.peersQuery())

                    // The backstop, for when the question itself goes
                    // unanswered. A table that arrived would have been acted
                    // on and would have re-armed the clock, so reaching here
                    // means the mixer is not answering at all.
                    AudioStall.Action.Reauth -> _state.value = VbanSessionState.Pinging
                    AudioStall.Action.None -> Unit
                }
            }
            // And the backstop: nothing at all heard from the mixer.
            //
            // MDXM_AUTH is only sent while NOT Streaming, and Streaming was
            // only ever left by the user stopping the session or a fresh
            // MDXM_AUTHSTATE arriving. So once the far end went away, this
            // loop sat in the `else` branch below sending state queries for
            // ever -- and after ten seconds the server had expired the peer,
            // so every one of those was refused as unauthorized and nothing
            // re-authenticated. The session reported Streaming the whole time
            // and the only way back was stopping and starting playback by
            // hand.
            //
            // Dropping to Pinging puts the loop back on the auth branch. The
            // socket is not touched: it was never the problem, the peer
            // registration behind it was. When the far end answers, the
            // Streaming edge fires replayWants() and audio, previews and the
            // forwarding subscription all come back on their own. A refusal
            // still ends it -- Denied, Locked and BadPin are terminal in
            // nextState -- so this cannot become a retry storm against a PC
            // that is saying no.
            if (!reach.reachable && _state.value == VbanSessionState.Streaming) {
                _state.value = VbanSessionState.Pinging
            }
            // AND THE BACKSTOP BEHIND THAT ONE.
            //
            // Dropping to Pinging re-authenticates, and for every cause but
            // one that is the entire repair. The exception is a socket
            // stamped with a network that has gone: re-authenticating sends
            // MDXM_AUTH down the same dead descriptor, for ever, and the only
            // symptom is a session that says "Reaching the PC…" and never
            // arrives. Fifteen minutes of that was observed while ICMP to the
            // same address answered in 9 ms.
            //
            // Unconditional on state, deliberately. The demotion above is
            // gated on Streaming because that is the only state it can leave;
            // this one has to work precisely in the state that demotion puts
            // us in, which is where the old code had nothing left to try.
            val stalled = rebinder.stalledTicks + 1
            if (rebinder.onTick(!reach.reachable)) {
                rebind("nothing heard for $stalled ticks and re-auth is not helping")
                return
            }
            // AND THE FOURTH FAILURE: locked, reachable, receiving, and
            // playing with no lead whatever.
            //
            // Nothing above can see this one. The mixer answers, so
            // Reachability is content; audio arrives, so AudioStall is
            // content; the packets are in sequence, so JitterPolicy is
            // content -- and the track is empty, so every ordinary jitter
            // becomes a hole and the stream pops without pause. It recovered
            // only by stopping and starting playback by hand, which is
            // exactly what re-priming does.
            if (bufferHealth.onTick(
                    queuedMs = player?.queuedMs ?: 0,
                    playing = wantAudio && player != null,
                    emitting = _pcState.value?.emitting == true,
                )
            ) {
                android.util.Log.w(
                    "MdrAudio",
                    "re-priming: played with no lead for 3 ticks " +
                        "(inserts=${_counters.value.inserts} " +
                        "arrivalGaps=${_counters.value.arrivalGaps} " +
                        "selfInflicted=${_counters.value.selfInflicted})",
                )
                player?.reprime()
            }
            transaction++
            if (_state.value != VbanSessionState.Streaming) {
                // Re-sent while pending; the server dedupes by device id, so
                // this does not spam the approval prompt.
                sendTxt(sock, addr, port, streamName, MdxmCommands.auth(pin, deviceId, deviceName))
            } else {
                sendTxt(sock, addr, port, streamName, MdxmCommands.vbanQuery())
                // THE QUERY ABOVE IS ALSO THE LIVENESS PROBE. If the mixer
                // stops answering it we have been deauthorized -- most often
                // because the PC restarted and lost its in-memory approval --
                // and nothing else will tell us: PING0 keeps being answered,
                // so `reach` stays happy while the session receives nothing
                // at all. Re-assert AUTH rather than dropping to Pinging: it
                // is idempotent, an already-approved device gets ok=1 and
                // nothing visible happens, and it avoids flipping the UI to
                // "connecting" on a link that may be perfectly fine.
                if (control.onQuerySent()) {
                    sendTxt(sock, addr, port, streamName,
                            MdxmCommands.auth(pin, deviceId, deviceName))
                }
                if (wantFwdSub) {
                    // Every tick, not once. The server drops the grant with
                    // the peer after 10 s of silence, and a phone in Doze
                    // crosses that easily; re-asserting is idempotent and
                    // costs one small record, which is a better trade than a
                    // control channel that dies invisibly. The MDXM_FWDSTATE
                    // this earns back is state the diagnostics want anyway.
                    sendTxt(sock, addr, port, streamName, MdxmCommands.fwdSub(true))
                    _fwdSubAsserted.value = true
                }
            }
            delay(PING_INTERVAL_MS)
        }
    }

    private fun sendTxt(
        sock: DatagramSocket,
        addr: InetAddress,
        port: Int,
        streamName: String,
        record: String,
    ) {
        // Null only if the record exceeds the 1436-byte payload limit, which
        // no command this client sends can reach.
        val pkt = VbanCodec.buildTxt(streamName, txtFrame++, record) ?: return
        runCatching { sock.send(DatagramPacket(pkt, pkt.size, addr, port)) }
            // KEPT. A swallowed send is invisible by construction -- the
            // record simply never happens -- and that cost an afternoon when
            // every command from a tap was failing with
            // NetworkOnMainThreadException inside this very runCatching.
            // Silent while sends succeed.
            .onFailure { android.util.Log.w("MdrVban", "send failed for '$record': $it") }
    }

    /** One audio packet, copied off the receive buffer and queued for playout. */
    private data class AudioPacket(
        val format: VbanCodec.AudioFormat,
        val pcm: ByteArray,
        val nuFrame: Long,
    )

    /**
     * Audio waiting to be written to the track.
     *
     * Bounded and DROP_OLDEST, because the alternative is back-pressuring the
     * socket, and that is the problem this queue exists to solve. Sixty-four
     * packets is about a third of a second at 48 kHz -- deep enough to ride
     * out a write that blocks, shallow enough that nothing useful accumulates
     * behind a sink that has genuinely stopped.
     */
    private val playout = kotlinx.coroutines.channels.Channel<AudioPacket>(
        capacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /**
     * Writes queued audio to the track, and blocks here instead of on the
     * socket.
     *
     * The jitter policy travels with the write rather than staying on the
     * receive side: it decides what to do about a gap by looking at what the
     * track is doing, so it belongs next to the track.
     */
    private suspend fun playoutLoop() {
        for (packet in playout) {
            player?.submit(
                packet.format,
                packet.pcm,
                0,
                packet.pcm.size,
                packet.nuFrame,
            )
            recomputeLag()
        }
    }

    private suspend fun receiveLoop(sock: DatagramSocket, streamName: String) {
        val buf = ByteArray(VbanCodec.MAX_PACKET)
        val assembler = MdxmCommands.ReplyAssembler()
        // Per socket, like the assembler beside it: a rebuilt socket is a new
        // conversation, and half a record from the old one belongs to nobody.
        val parts = RelayParts()
        val frameAssembler = FrameAssembler()
        while (currentCoroutineContext().isActive) {
            val dp = DatagramPacket(buf, buf.size)
            try {
                sock.receive(dp)
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                return
            }
            val p = VbanCodec.parse(buf, dp.length) ?: continue
            // ANY packet from the PC proves the PC is there.
            //
            // This used to be credited only by a PING0 reply, which made the
            // watchdog depend on one specific probe surviving while ~188 audio
            // packets a second were already arriving to say the same thing.
            // Three delayed or lost replies then demoted a perfectly healthy
            // streaming session back to re-authenticating, every six seconds,
            // for ever -- the session reading "Connecting to the mixer" on a
            // link that was carrying audio the whole time.
            //
            // Checked against the address we are talking to, so a stray VBAN
            // source elsewhere on the network cannot vouch for this one.
            if (dp.address == target?.first) reach.onHeard().also {
                if (it) _unreachable.value = false
                // The backoff is forgiven HERE and nowhere else. A rebind
                // resets reachability, so its verdict is optimistic for two
                // or three ticks on no evidence; crediting that wiped the
                // backoff every rebuild and produced thirteen rebinds at a
                // flat twelve-second interval. An arriving datagram is the
                // only proof a socket works.
                rebinder.onHeard()
            }
            when (p.header.proto) {
                VbanCodec.PROTO_SERVICE -> VbanCodec.parsePing0Reply(p)?.let { reply ->
                    _identity.value = reply
                    pingSentAt.remove(reply.transactionId)?.let { sentAt ->
                        networkMs = LagEstimate.fromRtt((System.currentTimeMillis() - sentAt).toInt())
                    }
                    recomputeLag()
                }

                VbanCodec.PROTO_AUDIO -> {
                    val fmt = VbanCodec.parseAudio(p) ?: continue
                    stall.onAudio(System.currentTimeMillis())
                    // HANDED OFF, NOT WRITTEN HERE.
                    //
                    // AudioTrack.write(byte[], int, int) is the BLOCKING
                    // overload, and with a 40 ms track holding 35-40 ms it is
                    // full essentially always -- so this loop spent most of
                    // its life inside the write, not reading the socket. Two
                    // measured consequences, one cause:
                    //
                    //   * a PING0 reply sat in the socket buffer until the
                    //     loop came back for it, and networkMs is stamped on
                    //     the READ -- so it reported 107 ms against a real
                    //     round trip of 4-17 ms, and that overstatement went
                    //     straight into the headline lag figure;
                    //   * the receive buffer overflowed while nothing drained
                    //     it, which is packets lost on this phone while the
                    //     PC correctly reports dropped=0.
                    //
                    // The copy is required: `buf` is reused by the next
                    // receive, so queueing a slice of it would hand the
                    // writer whatever arrives next.
                    val pcm = p.data.copyOfRange(
                        VbanCodec.HEADER_SIZE,
                        VbanCodec.HEADER_SIZE + p.dataLen,
                    )
                    // COUNTED HERE, BEFORE THE QUEUE CAN LOSE IT. Everything
                    // downstream measures holes after a DROP_OLDEST channel
                    // has had its say, so it cannot tell a packet the network
                    // lost from one this phone threw away. This can.
                    lastArrivedNuFrame?.let { prev ->
                        val forward = (p.header.nuFrame - prev) and 0xFFFFFFFFL
                        // Same serial-number arithmetic JitterPolicy uses: the
                        // top half of the space is behind us, not a gap of two
                        // billion packets.
                        if (forward in 2L..0x80000000L) arrivalGaps++
                    }
                    lastArrivedNuFrame = p.header.nuFrame
                    // Dropping the oldest beats stalling the socket: a packet
                    // this far behind is one the playout would re-lock past
                    // anyway, and nothing else on this socket should wait for
                    // it.
                    playout.trySend(AudioPacket(fmt, pcm, p.header.nuFrame))
                }

                VbanCodec.PROTO_TXT -> {
                    if (p.header.streamName != streamName) continue
                    // A TXT REPLY IS THE ONE THING THAT PROVES WE ARE STILL
                    // AUTHORIZED. An unauthorized peer's records are dropped
                    // in silence by the mixer, so this cannot arrive for a
                    // session that has been deauthorized -- unlike a PING0
                    // reply, which is answered statelessly to anyone. Credited
                    // here and nowhere else, which is what makes the silence
                    // meaningful.
                    control.onReplyHeard()
                    // The frame is the DATAGRAM's, so it is noted here, once,
                    // whether or not any record below reaches the assembler.
                    // See ReplyAssembler.onDatagram for the two ways counting
                    // it per record discarded replies that had arrived whole.
                    assembler.onDatagram(p.header.nuFrame)
                    val text = VbanCodec.parseTxt(p) ?: continue
                    // Only the line ending comes off, NOT surrounding
                    // whitespace. This used to trim() every record, which was
                    // harmless while every record was the mixer's own -- but a
                    // relayed MDropDX12 payload passes through here too, and
                    // mdx12 treats text it does not recognise as a script
                    // line, where leading whitespace is part of the content.
                    // Trimming it would silently alter a command on its way to
                    // the visualizer. Verb matching does its own trimming.
                    for (record in text.lineSequence()
                        .map { it.trimEnd('\r') }
                        .filter { it.isNotBlank() }) {
                        handleRecord(record, assembler, parts)
                    }
                }

                VbanCodec.PROTO_FRAME -> frameAssembler.accept(p)?.let { _frames.tryEmit(it) }
            }
        }
    }

    private suspend fun handleRecord(
        record: String,
        assembler: MdxmCommands.ReplyAssembler,
        parts: RelayParts,
    ) {
        MdxmCommands.parseAuthState(record)?.let { auth ->
            val was = _state.value
            _state.value = nextState(was, auth)
            // An approval may be a different mixer process answering. A
            // part's id is unique only while the mixer runs and starts again
            // from zero when it restarts, so half a record held from before
            // would be finished by a part of some other record after.
            //
            // On ANY approval, not only a fresh one: a mixer that restarts
            // while this session reads Streaming is re-authenticated without
            // the state ever leaving Streaming -- deliberately, so the screen
            // does not flicker to "connecting" -- and that is precisely the
            // restart this is for.
            if (auth == AuthState.Ok) parts.clear()
            // A fresh approval after a roam has to put back what this session
            // asked for, or previews and audio quietly stay off.
            if (_state.value == VbanSessionState.Streaming && was != VbanSessionState.Streaming) {
                replayWants()
            }
            return
        }
        MdxmCommands.parseVbanState(record)?.let {
            // The moment the PC starts sending, the stall clock has to start
            // too. Its mark is from whenever audio was last asked for, which
            // on a PC that has been idle is long ago -- so without this the
            // first state record saying `emitting=1` would be followed
            // straight away by a nudge and a re-authentication, against a
            // stream that had not had a chance to arrive yet.
            if (it.emitting && _pcState.value?.emitting != true) {
                stall.armed(System.currentTimeMillis())
            }
            _pcState.value = it
            srcLatencyMs = it.srcLatencyMs
            _pcSilent.value = silence.onState(it.starved, it.emitting)
            recomputeLag()
            return
        }
        // Checked before the batch assembler, because a relayed record can
        // arrive in the middle of an unrelated MDXM_BEGIN..MDXM_END batch --
        // the relay is asynchronous and shares the TXT channel with replies --
        // and feeding it to the assembler would put mdx12's bytes into the
        // mixer's answer.
        MdxmCommands.parseRelay(record)?.let {
            // SUSPENDS rather than dropping. This was tryEmit on a flow whose
            // overflow policy is SUSPEND, which means tryEmit returns false
            // and THROWS THE RECORD AWAY once the buffer is full. A reply of
            // sixty-one rows arrives as a burst, the buffer filled, and the
            // records lost were the last ones -- including PRESET_ROWS_END,
            // the only thing that publishes the list. Sixty-one rows reached
            // the phone and the screen showed "0 of 0" with a spinner, for
            // want of the record that says "that is all of them".
            //
            // Back-pressuring the receive loop is the right trade: the UDP
            // socket buffer absorbs a burst far better than a dropped
            // terminator can be recovered from, because it cannot be.
            _relay.emit(it)
            return
        }
        // A relayed record too large for one packet, a slice at a time. Taken
        // here for the reason a whole one is: a part can land in the middle of
        // an unrelated MDXM_BEGIN..MDXM_END batch. Once the last slice is in
        // hand the record is exactly what would have followed
        // `MDXM_FWDR|msg=`, so it leaves by the same door and nothing
        // downstream can tell how it crossed.
        //
        // A record that never completes is simply never emitted. Nothing is
        // sent twice, so whoever needed the answer has to ask again -- see
        // RelayParts for why it is dropped rather than waited for.
        MdxmCommands.parsePart(record)?.let { part ->
            parts.accept(part, System.currentTimeMillis())?.let { _relay.emit(it) }
            return
        }
        // THE PC SAYING NO, which was invisible until now.
        //
        // `parseErr` existed and nothing called it, so a forward the mixer
        // refused -- forwarding off, no target, a queue that is full -- fell
        // through to the batch assembler and vanished. The command simply did
        // not happen, with no refusal on the phone and nothing in any log,
        // which is indistinguishable from the command never having been sent.
        MdxmCommands.parseErr(record)?.let {
            android.util.Log.i("MdrLink", "the PC refused something: $it")
            _pcRefusal.tryEmit(it)
            return
        }
        MdxmCommands.parseFwdState(record)?.let {
            _fwdState.value = it
            return
        }
        assembler.accept(record)?.let { batch ->
            val targets = batch.mapNotNull { MdxmCommands.parseFwdTarget(it) }
            if (targets.isNotEmpty()) _fwdTargets.value = targets
            val peers = batch.mapNotNull { MdxmCommands.parseVbanPeer(it) }
            if (peers.isNotEmpty()) onPeerTable(peers)
            // Every listing shares one bracket, so this one is recognised by
            // what is in it rather than by having been asked for.
            if (MdxmMixerRows.isState(batch)) _mixerState.tryEmit(batch)
        }
    }

    /**
     * The mixer's answer to "why is no audio arriving".
     *
     * Reached only because a stall asked, so the absence of our row or a
     * dropped grant is news rather than a state to be polled for. Each fault
     * gets the narrowest response that can fix it, which is the entire reason
     * for asking rather than escalating on a timer.
     */
    private fun onPeerTable(peers: List<VbanPeer>) {
        _peers.value = peers
        val mine = StreamDiagnosis.mine(peers, myDeviceId)
        val fault = StreamDiagnosis.diagnose(mine, wantAudio)
        _fault.value = fault
        when (fault) {
            StreamFault.AudioGrantLost -> {
                // The cheap one, and the common one after a re-registration.
                setAudio(true)
                // Give the restored grant the same fresh start a recovery
                // gets, or the next tick accuses it of a stall it has had no
                // chance to end.
                stall.armed(System.currentTimeMillis())
            }
            StreamFault.Refused ->
                _state.value = VbanSessionState.Lost(LostReason.Denied)
            StreamFault.AwaitingApproval -> {
                _state.value = VbanSessionState.AuthPending
                stall.armed(System.currentTimeMillis())
            }
            StreamFault.NotRegistered, StreamFault.PathBroken ->
                if (StreamDiagnosis.needsReauth(fault)) {
                    _state.value = VbanSessionState.Pinging
                }
            StreamFault.None -> Unit
        }
    }

    private fun replayWants() {
        setAudio(wantAudio)
        if (wantFrames) setFrames(true, wantFps)
        if (wantFwdSub) setFwdSub(true)
        // Start the stall clock from the moment audio was asked for, not from
        // whenever the last packet happened to arrive -- which on a recovery
        // is before the outage and would re-escalate immediately.
        if (wantAudio) stall.armed(System.currentTimeMillis())
    }

    private fun recomputeLag() {
        val p = player ?: return
        _lagMs.value = LagEstimate.total(
            LagTerms(
                networkMs = networkMs,
                srcLatencyMs = srcLatencyMs,
                bufferDepthMs = p.bufferedMs,
                outputLatencyMs = p.outputLatencyMs,
                manualOffsetMs = manualOffsetMs,
            ),
        )
    }

    fun setGainPercent(percent: Int) = send(MdxmCommands.gain(percent))

    fun setAudio(on: Boolean) {
        wantAudio = on
        _audioOn.value = on
        send(MdxmCommands.audio(on))
    }

    /**
     * Start playing on this phone, on a link that is already up.
     *
     * Builds the AudioTrack if there is not one, because a control-only
     * session deliberately has none.
     */
    fun enableAudio(profile: LatencyProfile) {
        if (player == null) {
            player = VbanAudioPlayer(context, profile)
                .also { it.preferredOutputId = preferredOutputId }
        }
        setMuted(false)
        stall.armed(System.currentTimeMillis())
        setAudio(true)
    }

    /**
     * Pause or resume, for a live feed: the track goes silent and keeps
     * playing, and the mixer keeps sending. See [muted].
     */
    fun setMuted(on: Boolean) {
        _muted.value = on
        player?.muted = on
    }

    /**
     * Stop playing, and KEEP THE LINK. The commands still have to get there.
     *
     * Distinct from [stop], which takes the socket down with it. The stream
     * card's Stop button means this one: the user is done listening, not done
     * with the remote.
     */
    fun stopAudio() {
        setAudio(false)
        _muted.value = false
        player?.stop()
        player = null
        stall.reset()
        _lagMs.value = 0
        _counters.value = Counters()
    }

    fun setFrames(on: Boolean, fps: Float) {
        wantFrames = on
        wantFps = fps
        send(MdxmCommands.frames(on, fps))
    }

    /**
     * Ask the mixer to relay MDropDX12's replies to this phone.
     *
     * The want is remembered and re-asserted on every ping tick; see
     * [wantFwdSub] for why that is not left to the auth-edge replay.
     */
    /**
     * [fwdSubAsserted] is what a caller should wait on, NOT the return of
     * this. The want can be set before there is a socket to put it on, and a
     * screen that queried on the strength of having ASKED got its answer
     * relayed to nobody -- the request had not left yet.
     */
    fun setFwdSub(on: Boolean) {
        wantFwdSub = on
        val sent = send(MdxmCommands.fwdSub(on))
        _fwdSubAsserted.value = on && sent
    }

    /**
     * The subscription request has actually gone out on the wire.
     *
     * Set here and on every ping tick that re-asserts it, so a want recorded
     * before the socket existed becomes true as soon as the loop gets to it.
     */
    private val _fwdSubAsserted = MutableStateFlow(false)
    val fwdSubAsserted: StateFlow<Boolean> = _fwdSubAsserted

    /**
     * Send one MDropDX12 command through the mixer.
     *
     * Returns the refusal if the command cannot be sent at all, so the caller
     * can say why rather than letting it vanish. A null return means it went
     * out -- which is NOT a promise it arrived, still less that mdx12 ran it:
     * `MDXM_OK` only ever meant "accepted into a queue", and what a command
     * actually did shows up on [relay].
     */
    fun forward(command: String): Forward.Refused? {
        return when (val f = MdxmCommands.forward(command)) {
            is Forward.Refused -> f
            is Forward.Record -> {
                send(f.text)
                null
            }
        }
    }

    fun queryFwdState() = send(MdxmCommands.fwdStateQuery())

    fun queryFwdTargets() = send(MdxmCommands.fwdTargetsQuery())

    /**
     * Ask the mixer for its own channels and devices. The answer, when it
     * arrives whole, is on [mixerState].
     *
     * NOT forwarded and not for the visualizer: this is the mixer's own verb,
     * answered by the mixer, and works with MDropDX12 closed.
     */
    fun queryMixerState(): Boolean = send(MdxmCommands.stateQuery())

    /**
     * One of the mixer's own write verbs, already built -- a fader, a mute.
     *
     * Kept apart from [forward], which wraps a command for the visualizer. A
     * mixer record sent down that door is refused by name, and a visualizer
     * command sent down this one would be an unknown verb.
     */
    fun sendMixer(record: String): Boolean = send(record)

    /**
     * NEVER writes on the caller's thread.
     *
     * Every one of these is reached from a button: a tap runs on the main
     * thread, Android refuses network I/O there, and the refusal arrives as
     * NetworkOnMainThreadException INSIDE the runCatching that wraps the
     * socket write -- so the command vanished with no exception, no log and
     * no refusal. Every control in the app did nothing, while the ping loop's
     * own records went out fine because it already lives on an IO thread.
     * That is what "nothing works except the song and the mixer" was.
     *
     * One thread rather than the IO pool, so records keep their order and
     * `txtFrame` has a single writer.
     *
     * The Boolean is now "accepted for sending" -- there is a socket and a
     * target -- rather than "written", which no caller can know synchronously
     * any more.
     */
    private fun send(record: String): Boolean {
        val sock = socket ?: return false
        val t = target ?: return false
        scope.launch(sendDispatcher) { sendTxt(sock, t.first, t.second, t.third, record) }
        return true
    }

    /** The counters as they were last tick, to tell a rise from a total. */
    private var previousCounters = Counters()

    /** Ticks of trouble still worth showing a person after it stops. */
    private var troubleTicks = 0

    /**
     * Read the counters, and work out whether anything is going wrong NOW.
     *
     * A function rather than a property because it remembers: the whole point
     * is the DELTA, and a getter that quietly mutates state between reads is
     * a trap for whoever reads it twice.
     *
     * Every counter here is cumulative for the life of the player, which is
     * right for a tally and useless as a test. `underruns > 0` was standing in
     * for "something is wrong", and since a track underruns about nine times
     * while its cushion first fills, it was true within seconds of every
     * session and stayed true for ever. The fault line that is documented as
     * silent on a healthy stream -- where the silence IS the signal -- has
     * therefore printed every two seconds since it was written, and a whole
     * night of debugging could not use its presence to tell a faulting stream
     * from a working one.
     */
    private fun readCounters(): Counters {
        val p = player ?: run {
            previousCounters = Counters()
            troubleTicks = 0
            return Counters()
        }
        val now = Counters(
            p.underruns, p.inserts, p.restarts, p.drops, p.writeErrors, p.bufferedMs,
            p.outputLatencyMs, srcLatencyMs, p.queuedMs, arrivalGaps, p.reprimes,
        )
        // `drops` is deliberately absent: a reordered or duplicated packet is
        // noted and discarded, costs nothing audible, and is not a fault to
        // put in front of anybody.
        val rising = now.underruns > previousCounters.underruns ||
            now.inserts > previousCounters.inserts ||
            now.restarts > previousCounters.restarts ||
            now.writeErrors > previousCounters.writeErrors
        troubleTicks = if (rising) TROUBLE_MEMORY_TICKS else (troubleTicks - 1).coerceAtLeast(0)
        previousCounters = now
        return now.copy(rising = rising, recentlyTroubled = troubleTicks > 0)
    }

    /**
     * Why the audio is breaking up, in the only terms that separate the causes.
     *
     * "Some crackling" has three unrelated explanations and they are
     * indistinguishable by ear: the buffer starving on this phone, packets
     * going missing on the network, and the PC emitting well-formed silence
     * because it has no clock. Each of those has a different fix and two of
     * them are not on this device at all, so guessing costs a whole debugging
     * session.
     *
     * Published on the 2 s state tick rather than per packet -- the figures
     * are for a person reading them, and 188 updates a second is not.
     */
    private val _counters = MutableStateFlow(Counters())
    val counters: StateFlow<Counters> = _counters

    data class Counters(
        /** The AudioTrack ran dry. Too little buffer, or the phone is busy. */
        val underruns: Int = 0,
        /** Holes small enough to paper over with silence. Network loss. */
        val inserts: Int = 0,
        /** Holes too big to paper over, so the stream re-locked. */
        val restarts: Int = 0,
        /** Reordered or duplicated packets. */
        val drops: Int = 0,
        /** The track refused a write. A dead track, not a starving one. */
        val writeErrors: Int = 0,
        val bufferedMs: Int = 0,
        /**
         * The sink's OWN latency, below our buffer -- AudioTimestamp-derived.
         *
         * Logged because `lagMs` is a sum of five terms and showing only the
         * total made every latency report unanswerable: four separate
         * theories about a 440 ms figure were argued and discarded before
         * anyone could see which term was large. On the reporter's wired
         * Pixel this turned out to be roughly 240 ms of the 360 -- three
         * times everything else put together, and invisible until now.
         */
        val outputLatencyMs: Int = 0,
        /** The PC's share, as it reported it. The remaining term is network. */
        val srcLatencyMs: Int = 0,
        /** Audio ACTUALLY in the track. Zero for long is the fault BufferHealth names. */
        val queuedMs: Int = 0,
        /**
         * Sequence gaps counted where packets ARRIVE, before the playout queue.
         *
         * The companion to [inserts], and the only way to read it. Inserts
         * well above this are holes this phone made by discarding from a full
         * playout queue; inserts tracking it are the network's.
         */
        val arrivalGaps: Int = 0,
        /** Times the track was flushed and given a fresh cushion. */
        val reprimes: Int = 0,
        /**
         * Something got worse on THIS tick.
         *
         * The test every cumulative counter here cannot perform. See
         * [readCounters] for what reading a lifetime total as a fault cost.
         */
        val rising: Boolean = false,
        /**
         * Something got worse within the last [TROUBLE_MEMORY_TICKS] ticks.
         *
         * For the screen rather than the log. A glitch that lasted one tick
         * is still worth naming to the person who heard it, and a line that
         * appears and vanishes before it can be read explains nothing --
         * but it has to clear eventually, or it is the latch again.
         */
        val recentlyTroubled: Boolean = false,
    ) {

        /**
         * Holes this phone made, as opposed to ones the network made.
         *
         * Never negative: a gap can be seen on arrival and then re-locked
         * past rather than inserted, so arrivals can legitimately exceed
         * inserts.
         */
        val selfInflicted get() = (inserts - arrivalGaps).coerceAtLeast(0)
    }

    fun stop() {
        job?.cancel()
        job = null
        // BEFORE the socket closes, so an in-flight rebind cannot outlive
        // this: it re-reads params and gives up when they are gone.
        params = null
        netCallback?.let { cb ->
            runCatching {
                context.getSystemService(ConnectivityManager::class.java)
                    ?.unregisterNetworkCallback(cb)
            }
        }
        netCallback = null
        boundNetwork = null
        rebinder.reset()
        runCatching { socket?.close() }
        socket = null
        player?.stop()
        player = null
        pingSentAt.clear()
        silence.reset()
        _pcSilent.value = false
        reach.reset()
        control.reset()      // a new session must not inherit the old one's debt
        stall.reset()
        bufferHealth.reset()
        arrivalGaps = 0
        lastArrivedNuFrame = null
        previousCounters = Counters()
        troubleTicks = 0
        downlinkHealth.reset()
        _downlink.value = Downlink.Unknown
        _peers.value = emptyList()
        // The PC's last reading is not a fact about the next session. Left
        // behind it reports a mixer that is emitting to peers that no longer
        // include this phone, which is exactly how a dead link read as a
        // healthy one in the diagnostics.
        _pcState.value = null
        _fault.value = StreamFault.None
        _audioOn.value = false
        _muted.value = false
        _fwdSubAsserted.value = false
        _unreachable.value = false
        _counters.value = Counters()
        // The forwarding view belongs to the session that asked for it. Left
        // behind, a stale "connected to mdx12, pid 4242" would be read as
        // current by the next session and by any screen drawing it.
        _fwdState.value = null
        _fwdTargets.value = emptyList()
        target = null
        networkMs = 0
        srcLatencyMs = 0
        _lagMs.value = 0
        if (_state.value != VbanSessionState.Idle) {
            _state.value = VbanSessionState.Lost(LostReason.Stopped)
        }
    }
}
