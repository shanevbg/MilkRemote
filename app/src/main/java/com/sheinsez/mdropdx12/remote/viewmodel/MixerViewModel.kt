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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

class MixerViewModel(application: Application) : AndroidViewModel(application) {
    private val connectionManager = (application as MdrApp).connectionManager

    /** What the PC says it supports, so a gap can be named rather than hidden. */
    val identity = connectionManager.identity

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

    init {
        viewModelScope.launch {
            connectionManager.messages.collect { raw ->
                // MIXER_STATE packs whole records into ~3000-character messages,
                // so one emission carries a couple of dozen of them. Matching on
                // the message as a whole would keep only the first and drop the
                // rest, which looks like a mixer with one fader in it.
                for (msg in raw.split("\n")) handleRecord(msg.trim())
            }
        }

        viewModelScope.launch {
            connectionManager.connectionState.collect { state ->
                // Without this the spinner runs forever whenever a request goes
                // out and the link drops before MIXER_END comes back -- which is
                // what the screen showed with the PC's remote server switched
                // off. A spinner with nothing behind it is worse than an empty
                // list: it says "wait" when the answer is "ask again".
                if (state != ConnectionState.Connected) _loading.value = false
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

    private fun handleRecord(msg: String) {
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
            viewFadersForDisplay(faders, _view.value, order, _showVirtual.value)
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
        connectionManager.send(CommandBuilder.mixerSubscribe(on))
    }

    /**
     * @param force also asks the PC to re-read every provider first. That costs
     * an HTTP round-trip to Sonar, so it belongs to an explicit press of
     * Refresh and nothing automatic.
     */
    fun refresh(force: Boolean = false) {
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
            if (forceNow) connectionManager.send(CommandBuilder.mixerRefresh())
            connectionManager.send(CommandBuilder.mixerOrder())
            // Both, always: MIXER_VIEW is the list to render, and MIXER_ORDER
            // is what is left to render from when the PC is too old to answer
            // it. Neither reads a provider, so the pair is still cheap.
            connectionManager.send(CommandBuilder.mixerView())
            connectionManager.send(CommandBuilder.mixerState())
            // Cheap, and the only thing that moves a battery reading: nothing
            // else refreshes it, because no device event announces it.
            connectionManager.send(CommandBuilder.mixerBattery())
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
            connectionManager.send(CommandBuilder.mixerOrder())
            connectionManager.send(CommandBuilder.mixerView())
        }
    }

    /**
     * Hide or reveal one fader. A view preference on the PC, shared by every
     * surface, so it is not the phone's own idea of what to show.
     */
    fun setFaderHidden(f: MixerFader, hidden: Boolean) {
        connectionManager.send(CommandBuilder.mixerHide(f.channelId, f.faderId, hidden))
        requestArrangement()
    }

    /** The user's short name for a fader; an empty name clears it. */
    fun setFaderName(f: MixerFader, name: String) {
        connectionManager.send(CommandBuilder.mixerFaderName(f.channelId, f.faderId, name.trim()))
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
        patchFader(f.copy(volumePercent = percent.coerceIn(0, 100)))
        connectionManager.send(CommandBuilder.mixerSet(f.channelId, f.faderId, percent))
    }

    fun muteFader(f: MixerFader, muted: Boolean) {
        // MIXER_MUTE answers with MIXER_MUTED, not a fader line, so the row
        // would keep its old icon until the next snapshot without this.
        patchFader(f.copy(muted = muted))
        connectionManager.send(CommandBuilder.mixerMute(f.channelId, f.faderId, muted))
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
        val (k, d) = move.start(key, direction)
        sendMove(k, d)
    }

    private fun sendMove(key: String, direction: Int) {
        val v = _view.value
        if (v == null || v.rows.isEmpty()) {
            // No drawn list to measure against: a PC too old to answer
            // MIXER_VIEW, where one stored step is all a press can mean.
            connectionManager.send(CommandBuilder.mixerOrderMove(key, direction))
            move.abandon()
            requestArrangement()
            return
        }
        val delta = orderDeltaForVisibleMove(v, key, direction)
        // Already at that end of the list. Nothing to send, and nothing to
        // keep waiting for.
        if (delta == null || delta == 0) { move.abandon(); return }
        connectionManager.send(CommandBuilder.mixerOrderMove(key, delta, rev))
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
