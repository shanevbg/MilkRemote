package com.sheinsez.mdropdx12.remote.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sheinsez.mdropdx12.remote.MdrApp
import com.sheinsez.mdropdx12.remote.data.model.MixerDevice
import com.sheinsez.mdropdx12.remote.data.model.MixerFader
import com.sheinsez.mdropdx12.remote.data.model.MixerMoveIntent
import com.sheinsez.mdropdx12.remote.data.model.MixerView
import com.sheinsez.mdropdx12.remote.data.model.MixerViewRow
import com.sheinsez.mdropdx12.remote.data.model.MixerQueryThrottle
import com.sheinsez.mdropdx12.remote.data.model.orderDeltaForVisibleMove
import com.sheinsez.mdropdx12.remote.data.model.viewFadersForDisplay
import com.sheinsez.mdropdx12.remote.data.model.nudgeLinked
import com.sheinsez.mdropdx12.remote.network.CommandBuilder
import com.sheinsez.mdropdx12.remote.network.ConnectionState
import com.sheinsez.mdropdx12.remote.network.MessageParser
import com.sheinsez.mdropdx12.remote.network.RelayPolicy
import com.sheinsez.mdropdx12.remote.network.vban.MdxmMixerRows
import com.sheinsez.mdropdx12.remote.network.vban.MdxmMixerState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * The queries this screen is built out of. A refusal of any one of them means
 * the list cannot be drawn, so the wait has to end.
 */
private val MIXER_QUERIES = setOf("MIXER_STATE", "MIXER_VIEW", "MIXER_ORDER")

/**
 * How long the mixer may take to answer before the screen says it has not.
 *
 * Several polls' worth. One lost reply is ordinary on Wi-Fi and is asked for
 * again a second later; this is for the link where they are all being lost.
 */
private const val MIXER_WAIT_MS = 5_000L

class MixerViewModel(application: Application) : AndroidViewModel(application) {
    private val connectionManager = (application as MdrApp).connectionManager

    /**
     * Where MDropDX12 commands go.
     *
     * Not the TCP socket. The link routes each command by its verb --
     * through the mixer for everything the relay can carry, down the
     * visualizer's own socket for the handful of replies too large for a
     * datagram -- so a screen states what it wants and does not choose a
     * wire. See RelayPolicy for which is which.
     */
    private val link = (application as MdrApp).mdxLink

    /**
     * The mixer's own faders, read from the mixer.
     *
     * Whenever there is a mixer session, THIS is where the rows come from and
     * the link above is not asked for them at all. The visualizer only ever
     * republished what the mixer told it, and its answer is too large to come
     * back through the relay -- so going to the source is both the shorter
     * path and the only one that works with the visualizer's own socket off,
     * or with the visualizer not running.
     */
    private val mixer = (application as MdrApp).mdxmMixer

    /**
     * What steps the linked faders when the mixer is the source.
     *
     * Taken HERE, at construction, and not at the first key press. It is
     * built lazily and learns which faders are linked a moment after it is
     * built -- so reaching for it only when a key arrived meant the first
     * press found a controller that had been told of no links yet, answered
     * "nothing to move", and went to the phone's own volume instead.
     */
    private val linkedThroughMixer = (application as MdrApp).linkedFaders

    private val _fromMixer = MutableStateFlow(false)

    /**
     * The rows are the mixer's own, not the visualizer's.
     *
     * Drawn, because it changes what the screen can offer: the mixer does not
     * publish the order it draws its rows in, nor a way to move one.
     */
    val fromMixer: StateFlow<Boolean> = _fromMixer

    /** What the PC says it supports, so a gap can be named rather than hidden. */
    val identity = link.identity

    /**
     * Every fader as one flat list, in the PC's MIXER_ORDER sequence. The PC is
     * the source of truth for positioning, so this is its order rendered
     * literally rather than a shape of the phone's own.
     */
    private val _faders = MutableStateFlow<List<MixerFader>>(emptyList())
    val faders: StateFlow<List<MixerFader>> = _faders

    private val _devices = MutableStateFlow<List<MixerDevice>>(emptyList())
    val devices: StateFlow<List<MixerDevice>> = _devices

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    /**
     * Why this screen cannot fill itself, or null when it can.
     *
     * Drawn where the faders would be. The alternative, which is what it did,
     * is a spinner that never stops: the reply it is waiting for was refused
     * before it left, and nothing told the screen.
     */
    private val _blocked = MutableStateFlow<String?>(null)
    val blocked: StateFlow<String?> = _blocked

    private val _showVirtual = MutableStateFlow(false)
    val showVirtual: StateFlow<Boolean> = _showVirtual

    /**
     * The PC answered that the mixer is switched off, rather than answering
     * with nothing. Worth saying plainly: an empty screen otherwise reads as a
     * machine with no audio, and the user would go looking in the wrong place.
     */
    private val _unavailable = MutableStateFlow(false)
    val unavailable: StateFlow<Boolean> = _unavailable

    // A snapshot arrives in chunks between MIXER_BEGIN and MIXER_END. Held here
    // and published once at the terminator: publishing as records stream would
    // render a half-built list, and a reply that never terminates would mix two
    // snapshots together.
    private val pendingFaders = mutableListOf<MixerFader>()
    private val pendingDevices = mutableListOf<MixerDevice>()
    private var inSnapshot = false
    private var pendingUnavailable = false

    private var order: List<String> = emptyList()
    private var orderKeys: Set<String> = emptySet()
    private val pendingOrder = sortedMapOf<Int, String>()

    /**
     * The list the PC DRAWS, which is not the one it stores: it applies
     * unmuted-first, failover pinning and hiding on top of the stored order
     * before anything reaches its screen. Null until the PC answers -- one too
     * old to know MIXER_VIEW never will, and the stored order stands there.
     */
    private val _view = MutableStateFlow<MixerView?>(null)
    val view: StateFlow<MixerView?> = _view

    // MIXER_VIEW has no MIXER_BEGIN; MIXER_VIEW_END terminates it. Held here
    // and published at the terminator for the same reason a snapshot is: a
    // half-built list would render, and a reply that never terminates would
    // mix two of them together.
    private val pendingViewRows = mutableListOf<MixerViewRow>()

    /**
     * The arrangement token. Sent back with a move so the PC can refuse one
     * made against a list that has since changed -- which it does on its own:
     * the failover device pinned to the top swaps out every few hours, and the
     * row under a finger is then a different fader than the one the PC holds.
     */
    private var rev: String = ""

    // The move a user asked for, held until the PC settles it. Its rules --
    // one retry per press, and only a refusal arms one -- are its own tested
    // unit rather than three flags read across this file.
    private val move = MixerMoveIntent()

    private var currentFaders: List<MixerFader> = emptyList()

    // The PC reads Sonar over HTTP to answer a query, and Sonar is not robust
    // to being asked repeatedly, so queries are coalesced to at most one a
    // second. Changes do not need one at all: while subscribed they arrive as
    // pushed MIXER_FADER records.
    private val throttle = MixerQueryThrottle()
    private var queryJob: Job? = null
    private var queuedForce = false

    // MIXER_ORDER is the one query that costs nothing at the provider -- it
    // reads the snapshot rather than Sonar -- so it gets its own gate. It is
    // asked for only when a pushed fader turns out to be one the order has
    // never mentioned, which is how a device joining reaches the list without
    // anything polling for it.
    private val orderThrottle = MixerQueryThrottle()
    private var orderJob: Job? = null

    // The mixer has been asked and has not answered yet. See awaitMixer.
    private var mixerWaitJob: Job? = null

    init {
        viewModelScope.launch {
            // WHICH SOURCE, decided in one place. Everything below asks
            // `_fromMixer` rather than working it out again.
            mixer.present.collect { present ->
                if (present == _fromMixer.value) return@collect
                _fromMixer.value = present
                // The two sources' rows are not each other's: a list left
                // over from one would be drawn under the other's rules.
                forgetRows()
                if (present) {
                    // Questions queued for the visualizer are now nobody's.
                    queryJob?.cancel()
                    orderJob?.cancel()
                    if (screenInFront) {
                        mixer.watch(true)
                        awaitMixer()
                    }
                    showMixer(mixer.state.value)
                } else {
                    mixer.watch(false)
                    mixerWaitJob?.cancel()
                    if (screenInFront) {
                        subscribe(true)
                        refresh()
                    }
                }
            }
        }

        viewModelScope.launch {
            mixer.state.collect { state -> if (_fromMixer.value) showMixer(state) }
        }

        viewModelScope.launch {
            // One record per emission, both ways. The link splits a TCP batch
            // -- MIXER_STATE packs whole records into ~3000-character messages
            // and matching on the message as a whole kept only the first,
            // which looked like a mixer with one fader in it -- and a relayed
            // record is one record by construction.
            link.messages.collect { record -> handleRecord(record.text) }
        }

        viewModelScope.launch {
            connectionManager.connectionState.collect { state ->
                // Without this the spinner runs forever whenever a request goes
                // out and the link drops before MIXER_END comes back -- which is
                // what the screen showed with the PC's remote server switched
                // off. A spinner with nothing behind it is worse than an empty
                // list: it says "wait" when the answer is "ask again".
                //
                // Not while the mixer is the source. That wait is not on this
                // socket, and with the visualizer's server off the socket is
                // retrying for ever -- each failed attempt would stop a
                // spinner that was honestly waiting on something else.
                if (!_fromMixer.value && state != ConnectionState.Connected) {
                    _loading.value = false
                }
            }
        }

        // A QUESTION THAT WAS NEVER ASKED still has to end the wait.
        //
        // `_loading` used to be cleared by the TCP state leaving Connected,
        // and the comment there records exactly why -- the screen hung "with
        // the PC's remote server switched off". That collector cannot fire
        // any more, so a refused MIXER_STATE left the spinner running for
        // ever with no list and no explanation.
        viewModelScope.launch {
            link.unsendable.collect { refusal ->
                // A refusal of the VISUALIZER's queries, which are not what
                // this screen is waiting on while the mixer is its source. One
                // left in flight from before the source changed must not put
                // "cannot be drawn" over a list that is being drawn.
                if (_fromMixer.value) return@collect
                if (RelayPolicy.verbOf(refusal.command) in MIXER_QUERIES) {
                    _loading.value = false
                    _blocked.value = refusal.why
                }
            }
        }

        viewModelScope.launch {
            connectionManager.connectionState
                .filter { it == ConnectionState.Connected }
                .collect {
                    // Deliberately NOT subscribing here. The subscription is
                    // the Mixer screen's, released the moment it leaves the
                    // foreground; arming it on every connect would leave the PC
                    // polling for a screen nobody has opened.
                    //
                    // A subscription lives on the socket, so a reconnect
                    // silently ends it. The screen's own arming is driven by
                    // Android lifecycle events, and a TCP reconnect is not one
                    // -- so with the screen still open and nothing re-armed
                    // here, no push ever arrives again and every row keeps
                    // whatever it last heard. That is exactly how a slot went
                    // on showing muted while the PC and Sonar both said it was
                    // not.
                    //
                    // None of which is this socket's business while the mixer
                    // is the source: that path polls, and re-arms itself.
                    if (_fromMixer.value) return@collect
                    if (screenInFront) {
                        subscribe(true)
                        refresh()
                    } else if (_linked.value.isNotEmpty()) {
                        // No screen, so no pushes are wanted; but the volume
                        // keys still need levels to clamp their group against.
                        refresh()
                    }
                }
        }
    }

    /**
     * Draw what the mixer sent, or the wait for it.
     *
     * A null state is "not answered yet", never "no faders": the session can
     * be up for a moment before the first reply, and re-authenticating does
     * not clear what was last read.
     */
    private fun showMixer(state: MdxmMixerState?) {
        if (state == null) return
        mixerWaitJob?.cancel()
        _blocked.value = null
        _unavailable.value = false
        _loading.value = false
        _devices.value = state.devices
        reorder(state.faders)
    }

    /**
     * A wait that ends, one way or the other.
     *
     * A reply spans sixteen datagrams and any one of them lost discards it;
     * the poll asks again a second later, so on a working link this is never
     * seen. It is here for the link that is NOT working, where the alternative
     * is the spinner this screen has shipped twice already -- turning for ever
     * over nothing, with no word on why.
     */
    private fun awaitMixer() {
        if (mixer.state.value != null) return
        _loading.value = true
        mixerWaitJob?.cancel()
        mixerWaitJob = viewModelScope.launch {
            delay(MIXER_WAIT_MS)
            if (_fromMixer.value && mixer.state.value == null) {
                _loading.value = false
                _blocked.value = "The mixer has not sent its channels yet. Still asking."
            }
        }
    }

    /** Neither source's rows survive a change of source. */
    private fun forgetRows() {
        pendingFaders.clear()
        pendingDevices.clear()
        pendingViewRows.clear()
        pendingOrder.clear()
        inSnapshot = false
        order = emptyList()
        orderKeys = emptySet()
        rev = ""
        _view.value = null
        _devices.value = emptyList()
        _blocked.value = null
        _unavailable.value = false
        _loading.value = false
        reorder(emptyList())
    }

    private fun handleRecord(msg: String) {
        // The visualizer republishes the mixer's faders as its own records,
        // and they still arrive over the relay. While the mixer is the source
        // they are a second, later copy of rows already on screen.
        if (_fromMixer.value) return
        when {
            msg.isEmpty() -> Unit

            msg.startsWith("MIXER_BEGIN") -> {
                inSnapshot = true
                pendingFaders.clear()
                pendingDevices.clear()
                pendingUnavailable = false
            }

            msg.startsWith("MIXER_END") -> {
                inSnapshot = false
                _devices.value = pendingDevices.toList()
                _unavailable.value = pendingUnavailable
                reorder(pendingFaders.toList())
                _loading.value = false
            }

            // The PC says why there is nothing rather than returning an empty
            // list, so pass that reason through instead of discarding it.
            msg.startsWith("MIXER_HEALTH|") && msg.contains("health=unavailable") ->
                pendingUnavailable = true

            msg.startsWith("MIXER_FADER|") ->
                MessageParser.parseMixerFader(msg)?.let { f ->
                    if (inSnapshot) pendingFaders += f else patchFader(f)
                    // A slot's level used to be mirrored from here, because it
                    // was drawn as a row of its own and the PC pushes fader
                    // records but never a slot one. The row is gone: a slot is
                    // a marker on the fader it points at, and that fader has
                    // just been updated by the line above.
                    //
                    // The order is not pushed either. A fader nobody has placed is
                    // a device that has just joined, and it would otherwise sit
                    // at the bottom until the screen was next opened.
                    if (!inSnapshot && f.key !in orderKeys) requestArrangement()
                }

            msg.startsWith("MIXER_DEVICE|") ->
                MessageParser.parseMixerDevice(msg)?.let { pendingDevices += it }

            msg.startsWith("MIXER_ORDER|") -> {
                // Only the reply to a MOVE carries the token; a listing entry
                // does not. That is what tells the two apart on one prefix.
                val ack = MessageParser.parseMixerRev(msg)
                if (ack != null) {
                    move.acknowledged()
                    if (ack != rev) { rev = ack; requestArrangement() }
                } else {
                    MessageParser.parseMixerOrder(msg)?.let { (pos, key) ->
                        pendingOrder[pos] = key
                        // The listing's own terminator arrives separately, so
                        // the order is rebuilt on every entry rather than at
                        // an end.
                        order = pendingOrder.values.toList()
                        orderKeys = order.toSet()
                        reorder(currentFaders)
                    }
                }
            }

            msg.startsWith("MIXER_ORDER_END") ->
                MessageParser.parseMixerRev(msg)?.let { rev = it }

            msg.startsWith("MIXER_VIEW|") ->
                MessageParser.parseMixerViewRow(msg)?.let { pendingViewRows += it }

            msg.startsWith("MIXER_VIEW_END") ->
                MessageParser.parseMixerViewEnd(msg)?.let { end ->
                    rev = end.rev
                    _view.value = end.copy(rows = pendingViewRows.toList())
                    pendingViewRows.clear()
                    reorder(currentFaders)
                    move.onView()?.let { (key, direction) -> sendMove(key, direction) }
                }

            // The push carries the TOKEN only, never the list: eight
            // characters compared against what is held, and a fetch only when
            // they differ. A fader merely changing value does not move it.
            msg.startsWith("MIXER_VIEW_CHANGED") ->
                MessageParser.parseMixerRev(msg)?.let { if (it != rev) requestArrangement() }

            // Not a failure: the arrangement moved before the move landed.
            MessageParser.isMixerStale(msg) ->
                MessageParser.parseMixerRev(msg)?.let {
                    rev = it
                    move.refused()
                    requestArrangement()
                }
        }
    }

    private fun reorder(faders: List<MixerFader>) {
        currentFaders = faders
        _faders.value =
            if (_fromMixer.value) {
                // The mixer's own order, as sent. It has no MIXER_VIEW to
                // render: the order its window draws is not published.
                MdxmMixerRows.forDisplay(faders, _showVirtual.value)
            } else {
                viewFadersForDisplay(faders, _view.value, order, _showVirtual.value)
            }
    }

    /** A single record outside a snapshot replaces one fader in place. */
    private fun patchFader(f: MixerFader) {
        reorder(currentFaders.filterNot { it.key == f.key } + f)
    }

    /**
     * Whether the Mixer screen is in front. The subscription is armed from the
     * screen's lifecycle, and this is what lets a reconnect put it back.
     */
    private var screenInFront = false

    fun subscribe(on: Boolean) {
        screenInFront = on
        if (_fromMixer.value) {
            // The mixer refuses a subscription over the network, so "looking"
            // means polling it -- and only for as long as the screen is.
            mixer.watch(on)
            return
        }
        link.send(CommandBuilder.mixerSubscribe(on))
    }

    /**
     * @param force also asks the PC to re-read every provider first. That costs
     * an HTTP round-trip to Sonar, so it belongs to an explicit press of
     * Refresh and nothing automatic.
     */
    fun refresh(force: Boolean = false) {
        if (_fromMixer.value) {
            // `force` has no meaning here: it asks the VISUALIZER to re-read
            // its providers, and every answer from the mixer is already a
            // fresh reading of its own.
            mixer.refresh()
            awaitMixer()
            return
        }
        queuedForce = queuedForce || force
        // One in flight already: it has taken the force flag with it, and a
        // second batch would be exactly the hammering this guards against.
        if (queryJob?.isActive == true) return
        val wait = throttle.waitFor(System.currentTimeMillis())
        queryJob = viewModelScope.launch {
            if (wait > 0) delay(wait)
            val forceNow = queuedForce
            queuedForce = false
            throttle.record(System.currentTimeMillis())
            _loading.value = true
            pendingOrder.clear()
            if (forceNow) link.send(CommandBuilder.mixerRefresh())
            link.send(CommandBuilder.mixerOrder())
            // Both, always: MIXER_VIEW is the list to render, and MIXER_ORDER
            // is what is left to render from when the PC is too old to answer
            // it. Neither reads a provider, so the pair is still cheap.
            link.send(CommandBuilder.mixerView())
            link.send(CommandBuilder.mixerState())
            // Cheap, and the only thing that moves a battery reading: nothing
            // else refreshes it, because no device event announces it.
            link.send(CommandBuilder.mixerBattery())
        }
    }

    /**
     * The arrangement only -- stored order and drawn list -- at most once a
     * second. Cheap: both are answered from the snapshot, and no provider is
     * read.
     */
    private fun requestArrangement() {
        if (orderJob?.isActive == true) return
        val wait = orderThrottle.waitFor(System.currentTimeMillis())
        orderJob = viewModelScope.launch {
            if (wait > 0) delay(wait)
            orderThrottle.record(System.currentTimeMillis())
            pendingOrder.clear()
            pendingViewRows.clear()
            link.send(CommandBuilder.mixerOrder())
            link.send(CommandBuilder.mixerView())
        }
    }

    /**
     * Hide or reveal one fader. A view preference on the PC, shared by every
     * surface, so it is not the phone's own idea of what to show.
     */
    fun setFaderHidden(f: MixerFader, hidden: Boolean) {
        // The visualizer's verb, about the visualizer's list. The screen does
        // not offer it while the rows are the mixer's.
        if (_fromMixer.value) return
        link.send(CommandBuilder.mixerHide(f.channelId, f.faderId, hidden))
        requestArrangement()
    }

    /** The user's short name for a fader; an empty name clears it. */
    fun setFaderName(f: MixerFader, name: String) {
        if (_fromMixer.value) return
        link.send(CommandBuilder.mixerFaderName(f.channelId, f.faderId, name.trim()))
        refresh()
    }

    fun setShowVirtual(show: Boolean) {
        _showVirtual.value = show
        reorder(currentFaders)
    }

    // Writes are optimistic by contract: the PC replies with the value asked
    // for, not one read back, because waiting on a slow provider would block
    // its message pump. The row shows the request at once and the broadcast
    // that follows carries the truth.

    fun setFader(f: MixerFader, percent: Int) {
        if (_fromMixer.value) {
            // Drawn by the mixer source itself, which also holds the level
            // over a poll that was answered a moment too late to know of it.
            mixer.setVolume(f, percent)
            return
        }
        patchFader(f.copy(volumePercent = percent.coerceIn(0, 100)))
        link.send(CommandBuilder.mixerSet(f.channelId, f.faderId, percent))
    }

    fun muteFader(f: MixerFader, muted: Boolean) {
        if (_fromMixer.value) {
            mixer.setMute(f, muted)
            return
        }
        // MIXER_MUTE answers with MIXER_MUTED, not a fader line, so the row
        // would keep its old icon until the next snapshot without this.
        patchFader(f.copy(muted = muted))
        link.send(CommandBuilder.mixerMute(f.channelId, f.faderId, muted))
    }

    /**
     * Moves a fader one row up (-1) or down (+1) THE SCREEN.
     *
     * Addressed by key, never by row index: the list is rebuilt whenever the
     * arrangement moves, and an index would then point at a different fader.
     *
     * The delta that goes on the wire is not this one. A move is expressed in
     * MIXER_ORDER space, where the PC keeps a place for every device that is
     * merely switched off -- so a bare -1 usually landed on one of those and
     * moved nothing the user could see. orderDeltaForVisibleMove measures the
     * distance to the neighbouring DRAWN row instead.
     */
    fun moveFader(key: String, direction: Int) {
        // A move is expressed in the visualizer's stored order. The mixer has
        // no verb for one, so there is nothing true to send.
        if (_fromMixer.value) return
        val (k, d) = move.start(key, direction)
        sendMove(k, d)
    }

    private fun sendMove(key: String, direction: Int) {
        val v = _view.value
        if (v == null || v.rows.isEmpty()) {
            // No drawn list to measure against: a PC too old to answer
            // MIXER_VIEW, where one stored step is all a press can mean.
            link.send(CommandBuilder.mixerOrderMove(key, direction))
            move.abandon()
            requestArrangement()
            return
        }
        val delta = orderDeltaForVisibleMove(v, key, direction)
        // Already at that end of the list. Nothing to send, and nothing to
        // keep waiting for.
        if (delta == null || delta == 0) { move.abandon(); return }
        link.send(CommandBuilder.mixerOrderMove(key, delta, rev))
    }

    // -- The hardware volume keys -------------------------------------------

    private val _linked = MutableStateFlow<Set<String>>(emptySet())
    val linked: StateFlow<Set<String>> = _linked

    fun setLinked(keys: Set<String>) {
        val had = _linked.value.isNotEmpty()
        _linked.value = keys
        // Settings reaches us after startup, so the connection may already be
        // up and its one chance to refresh gone. Take the snapshot the keys
        // need — once, and only when there is now something to move.
        //
        // Not while the mixer is the source: the keys ask it themselves, each
        // press, and decline to step from a reading that is not recent.
        if (_fromMixer.value) return
        if (!had && keys.isNotEmpty() && currentFaders.isEmpty() &&
            connectionManager.connectionState.value == ConnectionState.Connected
        ) {
            refresh()
        }
    }

    /**
     * One rocker press. Returns false when nothing is linked, so the caller
     * falls back to whatever the rocker did before rather than swallowing the
     * key press.
     */
    fun nudgeLinkedFaders(step: Int, up: Boolean): Boolean {
        if (_fromMixer.value) {
            // One implementation of stepping the mixer's faders, and it is the
            // one that works with no screen at all. It also knows not to step
            // from an old reading, which this list is whenever the Mixer tab
            // is not the one in front. `step` is read there from the same
            // setting this was handed.
            return linkedThroughMixer.nudge(up)
        }
        val group = currentFaders.filter { it.key in _linked.value }
        if (group.isEmpty()) return false
        val next = nudgeLinked(group, step, up)
        group.forEach { f -> next[f.key]?.let { setFader(f, it) } }
        return true
    }

    override fun onCleared() {
        // The PC's count is process-wide: leaving without this makes it poll
        // for a screen nobody is looking at.
        subscribe(false)
        super.onCleared()
    }
}
