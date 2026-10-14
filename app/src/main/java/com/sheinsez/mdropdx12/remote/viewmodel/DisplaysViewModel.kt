package com.sheinsez.mdropdx12.remote.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sheinsez.mdropdx12.remote.MdrApp
import com.sheinsez.mdropdx12.remote.data.model.AllFullscreen
import com.sheinsez.mdropdx12.remote.data.model.ChildInstance
import com.sheinsez.mdropdx12.remote.data.model.DisplayProfile
import com.sheinsez.mdropdx12.remote.data.model.DisplayProfileStartup
import com.sheinsez.mdropdx12.remote.data.model.PcFeature
import com.sheinsez.mdropdx12.remote.data.model.DisplayInfo
import com.sheinsez.mdropdx12.remote.data.model.DisplayMode
import com.sheinsez.mdropdx12.remote.data.model.DisplayModeState
import com.sheinsez.mdropdx12.remote.data.model.MirrorState
import com.sheinsez.mdropdx12.remote.data.model.MixerQueryThrottle
import com.sheinsez.mdropdx12.remote.data.model.PresetCycle
import com.sheinsez.mdropdx12.remote.network.CommandBuilder
import com.sheinsez.mdropdx12.remote.network.ConnectionState
import com.sheinsez.mdropdx12.remote.network.DisplayProfileListing
import com.sheinsez.mdropdx12.remote.network.MessageParser
import com.sheinsez.mdropdx12.remote.network.RelayPolicy
import com.sheinsez.mdropdx12.remote.network.link.Carrier
import com.sheinsez.mdropdx12.remote.network.link.LinkState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class DisplaysViewModel(application: Application) : AndroidViewModel(application) {
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
     * Why the display map cannot be built, or null when it can.
     *
     * The map is seeded from DIAG_MIRRORS, whose reply is one record far
     * larger than a datagram, so it cannot come back through the mixer at
     * all. With nothing seeded the screen fell through to "Connect to
     * MDropDX12 to configure displays" -- advice to do the thing already
     * done, on a PC that was answering every other query.
     */
    private val _blocked = MutableStateFlow<String?>(null)
    val blocked: StateFlow<String?> = _blocked

    private val _mirrorState = MutableStateFlow<MirrorState?>(null)
    val mirrorState: StateFlow<MirrorState?> = _mirrorState

    /** Keyed by the DISPLAYn number, so it joins to DisplayInfo.deviceNumber. */
    private val _children = MutableStateFlow<Map<Int, ChildInstance>>(emptyMap())
    val children: StateFlow<Map<Int, ChildInstance>> = _children

    /**
     * Which display the controls act on. Null until the first reply, then seeded
     * from render_on= so it names the display actually hosting the render
     * window rather than defaulting to a display that may not even exist.
     */
    /** The main window's cycling. Global, not per display. */
    private val _presetCycle = MutableStateFlow<PresetCycle?>(null)
    val presetCycle: StateFlow<PresetCycle?> = _presetCycle

    /** What the PC says it supports, so profile controls appear only where they work. */
    val identity = link.identity

    /**
     * Watermark per display, keyed by display number.
     *
     * One switch per display is possible now that SET_DISPLAY_WATERMARK takes
     * any display and GET_DISPLAY_WATERMARK reads it back. It supersedes the
     * main-window-only pair below for everything the Displays tab shows.
     */
    private val _displayWatermark = MutableStateFlow<Map<Int, Boolean>>(emptyMap())
    val displayWatermark: StateFlow<Map<Int, Boolean>> = _displayWatermark

    private val _displayModes = MutableStateFlow<Map<Int, DisplayModeState>>(emptyMap())
    val displayModes: StateFlow<Map<Int, DisplayModeState>> = _displayModes

    /** The MAIN window's watermark, which GET_WATERMARK answers for. */
    private val _mainWatermark = MutableStateFlow(false)
    val mainWatermark: StateFlow<Boolean> = _mainWatermark

    private val _idleActive = MutableStateFlow(false)
    val idleActive: StateFlow<Boolean> = _idleActive

    private val _allFullscreen = MutableStateFlow(AllFullscreen())
    val allFullscreen: StateFlow<AllFullscreen> = _allFullscreen

    private val _profiles = MutableStateFlow<List<DisplayProfile>>(emptyList())
    val profiles: StateFlow<List<DisplayProfile>> = _profiles

    /**
     * Whether a listing has actually come back.
     *
     * An empty list means two different things -- not asked yet, and asked and
     * there are none -- and saying "None saved yet" about the first is a claim
     * nothing supports. It showed exactly that against a folder holding a dozen.
     */
    private val _profilesLoaded = MutableStateFlow(false)
    val profilesLoaded: StateFlow<Boolean> = _profilesLoaded

    private val _startup = MutableStateFlow(DisplayProfileStartup())
    val startup: StateFlow<DisplayProfileStartup> = _startup

    /**
     * Why the saved arrangements cannot be listed, or null when they can.
     *
     * The listing is refused through a mixer that cannot carry it while the
     * startup query beside it still travels, and that reply used to close a
     * listing nobody had sent: "0 saved", about a folder holding eighty-two.
     */
    private val _profilesBlocked = MutableStateFlow<String?>(null)
    val profilesBlocked: StateFlow<String?> = _profilesBlocked

    // A listing arrives a record at a time and is closed by the startup reply
    // asked for with it. See DisplayProfileListing for why that is its own
    // class rather than a few lines here.
    private val listing = DisplayProfileListing()

    /** A MIRRORS record has arrived since the display listing was last asked for. */
    private var mirrorsHeard = false

    /**
     * The display listing was refused since it was last asked for.
     *
     * Its own flag rather than a reading of [_blocked], which stays on screen
     * until an answer replaces it: the reason left there by an older mixer
     * would otherwise read as "refused just now" on the first ask through a
     * newer one, and stop that ask from being repeated.
     */
    private var mirrorsRefused = false
    private var mirrorsJob: Job? = null
    private var profilesJob: Job? = null
    private val profilesThrottle = MixerQueryThrottle()

    private val _selectedDisplay = MutableStateFlow<Int?>(null)
    val selectedDisplay: StateFlow<Int?> = _selectedDisplay

    /**
     * GET_CHILDREN answers with one CHILD| message per child display and then a
     * single CHILDREN_END, so lines are accumulated here and published as one
     * map at the terminator. Publishing per line would make the UI flicker
     * through partial states, and would never clear a child that has gone away.
     */
    private val pendingChildren = mutableMapOf<Int, ChildInstance>()

    init {
        // The screen mounts before GET_VERSION has come back, so anything
        // gated on a capability asked for at that moment asks for nothing and
        // never asks again -- which left the arrangements list saying
        // "Reading..." forever. Ask when the answer arrives instead.
        viewModelScope.launch {
            identity.collect { id ->
                if (PcFeature.DISPLAY_PROFILES in id) refreshProfiles()
                if (PcFeature.ALL_FULLSCREEN in id) {
                    link.send(CommandBuilder.getAllFullscreen())
                }
                if (PcFeature.IDLE_ACTIVE in id) {
                    link.send(CommandBuilder.getIdleActive())
                }
            }
        }

        viewModelScope.launch {
            link.messages.collect { record ->
                // ONE RECORD PER EMISSION now.
                //
                // This used to receive a whole profile listing in a single
                // message and iterate its lines, then publish at the end of
                // the EMISSION -- the only per-emission framing in the app,
                // and it worked solely because mdx12 packs the listing into
                // one pipe message. The link splits those, and the rule that
                // was left behind published after every row, so the list held
                // one arrangement: the last. The listing is closed by its
                // terminator now, which is what every other batch in the app
                // already does.
                val one = record.text
                when (val update = listing.accept(one)) {
                    is DisplayProfileListing.Update.Listed -> {
                        // The PC's order, rendered literally. It orders these
                        // by name BECAUSE the name is a timestamp, and its own
                        // next/previous profile stepping walks that same order
                        // -- so re-sorting here would put this list out of
                        // step with both the PC's window and its hotkeys.
                        _profiles.value = update.profiles
                        _startup.value = update.startup
                        _profilesLoaded.value = true
                    }
                    is DisplayProfileListing.Update.Startup -> _startup.value = update.startup
                    null -> Unit
                }
                run {
                    when {
                        one.startsWith("DISPLAY_WATERMARK|") ->
                            MessageParser.parseDisplayWatermark(one)
                                ?.let { _displayWatermark.value = it }

                        one.startsWith("DISPLAY_MODE|") ->
                            MessageParser.parseDisplayModes(one)
                                ?.let { _displayModes.value = it }

                        one.startsWith("WATERMARK=") ->
                            MessageParser.parseWatermark(one)?.let { _mainWatermark.value = it }

                        one.startsWith("IDLE_ACTIVE=") ->
                            MessageParser.parseIdleActive(one)?.let { _idleActive.value = it }

                        one.startsWith("ALL_FULLSCREEN=") ->
                            MessageParser.parseAllFullscreen(one)
                                ?.let { _allFullscreen.value = it }

                        // Saving, deleting or loading changes what the list
                        // should say, and none of them answers with a list.
                        // refreshProfiles is itself gated on the section
                        // being open, so this is free when it is not.
                        // Same provenance rule as the mirror echoes below: a
                        // profile saved at the PC would otherwise have every
                        // listening phone re-read the listing.
                        one.startsWith("DISPLAY_PROFILE_SAVED|") ||
                            one.startsWith("PROFILE_LOADED=") ->
                            if (record.via == Carrier.Tcp) refreshProfiles(changed = true)
                    }
                }

                val msg = record.text
                when {
                    msg.startsWith("MIRRORS|") -> {
                        val state = MessageParser.parseMirrors(msg)
                        _mirrorState.value = state
                        if (state != null) {
                            // Whoever asked: the record names every display
                            // it describes, so a stranger's is this phone's
                            // answer too. And an answer is the end of any
                            // reason that was on screen for not having one.
                            mirrorsHeard = true
                            _blocked.value = null
                            seedSelection(state)
                        }
                    }

                    msg.startsWith("PRESET_CYCLE=") -> {
                        _presetCycle.value = MessageParser.parsePresetCycle(msg)
                    }

                    msg.startsWith("CHILD|") -> {
                        MessageParser.parseChild(msg)?.let {
                            pendingChildren[it.deviceNumber] = it
                        }
                    }

                    msg == "CHILDREN_END" -> {
                        _children.value = pendingChildren.toMap()
                        pendingChildren.clear()
                    }

                    // The PC echoes these after a successful set. Re-reading is
                    // cheaper than mirroring its state machine here.
                    //
                    // ONLY OUR OWN ECHOES, which is the difference between a
                    // read-after-write and an amplifier. These are broadcasts:
                    // mdx12 sends every reply to every client, so a slider
                    // dragged at the PC arrives here as a stream of them, and
                    // answering each with refresh() -- seven to nine commands,
                    // one of them DIAG_MIRRORS, whose reply cannot even cross
                    // the relay -- turns one drag into tens of queries whose
                    // replies are relayed back to every subscriber. With two
                    // phones listening that is a mutual amplifier.
                    //
                    // Over TCP the echo is an ack of something this phone
                    // sent, so it keeps its original meaning.
                    msg.startsWith("MIRROR_OPACITY=") ||
                        msg.startsWith("MIRROR_CLICKTHRU=") ||
                        msg.startsWith("MIRROR_ENABLED=") ||
                        msg.startsWith("MIRROR_INDEPENDENT=") ||
                        msg.startsWith("DISPLAY_MODE=") ->
                        if (record.via == Carrier.Tcp) refresh()
                }
            }
        }

        // A reconnect leaves every cached value stale — the PC may have been
        // restarted, or its displays rearranged, while the phone was away.
        viewModelScope.launch {
            // THE LINK, not the socket. This asked the PC for its state when
            // the TCP socket connected, which with the visualizer's own
            // server switched off never happens -- so the queries fired once
            // in init, were refused because the VBAN session had not come up
            // yet, and were never asked again. The screen then stayed empty
            // for the rest of the session with nothing explaining why.
            //
            // AND WHAT THE MIXER CAN CARRY, because that changes what is
            // worth asking. The mixer on the PC can be replaced by one that
            // carries the display listing while this screen sits there
            // saying it cannot be had, and the link stays up across that --
            // so "the link came up" alone would never ask again.
            //
            // When a session starts the two arrive within a millisecond of
            // each other; refresh() takes a burst of reasons as one.
            combine(link.ready, link.carriesLargeReplies) { ready, large -> ready to large }
                .distinctUntilChanged()
                .filter { (ready, _) -> ready }
                .collect { refresh() }
        }

        // A query that was refused has to say so where its content would be.
        viewModelScope.launch {
            link.unsendable.collect { refusal ->
                if (RelayPolicy.verbOf(refusal.command) == "DIAG_MIRRORS") {
                    mirrorsRefused = true
                    _blocked.value = refusal.why
                }
            }
        }
    }

    /**
     * Ask, and ask again if nothing comes back.
     *
     * Through the mixer a large reply crosses in parts, every part is a
     * packet that has to arrive, and nothing is sent twice -- so the display
     * listing is lost about four times in a hundred on a link that loses two
     * packets in a hundred. A reply that does not come leaves no error to
     * show, only a screen that stays as it was.
     *
     * Over the visualizer's own socket the answer is back long before the
     * first wait ends, and this asks once.
     */
    private fun askUntil(answered: () -> Boolean, ask: () -> Unit): Job =
        viewModelScope.launch {
            ask()
            repeat(REASKS) {
                delay(REASK_MS)
                if (answered() || !link.ready.value) return@launch
                ask()
            }
        }

    private fun seedSelection(state: MirrorState) {
        val current = _selectedDisplay.value
        val stillPresent = current != null && state.monitors.any { it.deviceNumber == current }
        if (!stillPresent) {
            _selectedDisplay.value = state.primary?.deviceNumber
                ?: state.monitors.firstOrNull()?.deviceNumber
        }
    }

    fun select(deviceNumber: Int) {
        _selectedDisplay.value = deviceNumber
    }

    /**
     * Ask the PC for everything this screen shows.
     *
     * ONE ROUND FOR A BURST OF REASONS. Opening the tab used to ask twice,
     * every time: the screen asks as it appears, and this class asks when it
     * sees the link is up, which on a link that is already up is the same
     * instant. That was merely wasteful while the answers were small. Through
     * the mixer the display listing is now two packets that both have to
     * arrive and the arrangements are eighty-odd records, relayed to every
     * subscriber -- so a second round is twice the chance of losing one.
     *
     * A call made while a round is about to go is covered by it: the round
     * asks after the wait, so what it reads is at least as fresh as that call
     * wanted.
     */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            delay(SETTLE_MS)
            askForEverything()
        }
    }

    private var refreshJob: Job? = null

    private fun askForEverything() {
        mirrorsHeard = false
        mirrorsRefused = false
        mirrorsJob?.cancel()
        mirrorsJob = askUntil(
            // A refusal is an answer too: asking again would only be refused
            // again, and say so again.
            answered = { mirrorsHeard || mirrorsRefused },
            ask = { link.send(CommandBuilder.diagMirrors()) },
        )
        link.send(CommandBuilder.getChildren())
        link.send(CommandBuilder.getPresetCycle())
        link.send(CommandBuilder.getWatermark())
        link.send(CommandBuilder.getDisplayWatermark())
        link.send(CommandBuilder.getDisplayModes())
        refreshProfiles()
        if (PcFeature.ALL_FULLSCREEN in identity.value) {
            link.send(CommandBuilder.getAllFullscreen())
        }
    }

    /**
     * Walk away: every screen showing the visualiser, or every screen back as
     * it was. The PC restores each window exactly as found, so this is safe to
     * use without knowing what anything was.
     */
    /** The idle action, on demand: the walk-away state the PC already has. */
    fun setIdleActive(on: Boolean) {
        link.send(CommandBuilder.setIdleActive(on))
        link.send(CommandBuilder.getIdleActive())
    }

    fun setAllFullscreen(on: Boolean) {
        link.send(CommandBuilder.setAllFullscreen(on))
        link.send(CommandBuilder.getAllFullscreen())
    }

    // ── Saved arrangements ──────────────────────────────────────────────

    /**
     * Fetched with the rest of the screen, not deferred until the section is
     * opened.
     *
     * Deferring looked thriftier and was not: there is no count-only verb, so
     * the header cannot say "12 saved" without having asked for the twelve.
     * One query either way, and this one has the answer ready when the section
     * is opened instead of after it.
     */
    /**
     * @param changed the list is known to be different from the one last
     * asked for -- something was saved, loaded or deleted -- so a listing
     * already on its way may predate that and must not stand in for this one.
     */
    fun refreshProfiles(changed: Boolean = false) {
        if (PcFeature.DISPLAY_PROFILES !in identity.value) return
        // Asked a moment ago, and nothing is known to have changed: that
        // answer stands, or is on its way. Two things ask when the screen
        // opens -- the round of everything, and the PC saying it has
        // arrangements at all -- and through the mixer each listing is
        // eighty-odd records that every other subscriber is sent as well.
        //
        // A refusal is not an answer worth keeping, so it never holds off a
        // second try: the reason for it may be gone.
        val now = System.currentTimeMillis()
        if (!changed && _profilesBlocked.value == null && profilesThrottle.waitFor(now) > 0) return
        profilesThrottle.record(now)
        _profilesLoaded.value = false
        profilesJob?.cancel()
        profilesJob = askUntil(
            answered = { _profilesLoaded.value || _profilesBlocked.value != null },
            ask = ::askForProfiles,
        )
    }

    private fun askForProfiles() {
        // OFFERED, not sent, because the answer is needed here and now: the
        // startup query below travels even where the listing cannot, and its
        // reply closes a listing. Whether one was actually asked for has to
        // be settled before that reply can arrive.
        val refusal = link.offer(CommandBuilder.displayProfileList())
        _profilesBlocked.value = refusal?.why
        if (refusal == null) listing.asked() else listing.abandoned()
        link.send(CommandBuilder.displayProfileStartup())
    }

    /** An empty name banks a timestamp, which is how these sort. */
    fun saveProfile(name: String = "") {
        link.send(CommandBuilder.displayProfileSave(name.trim()))
        // Asked for here, as a delete is, rather than left to the PC's
        // echo: through the mixer that echo is deliberately not acted on --
        // it may be anybody's -- so a save from this phone changed nothing
        // on its own screen.
        refreshProfiles(changed = true)
    }

    fun loadProfile(name: String) =
        link.send(CommandBuilder.displayProfileLoad(name))

    fun deleteProfile(name: String) {
        link.send(CommandBuilder.displayProfileDelete(name))
        refreshProfiles(changed = true)
    }

    /**
     * Sets both halves at once: which profile, and that one loads at all.
     * An empty name switches startup loading off.
     */
    fun setStartupProfile(name: String) {
        link.send(CommandBuilder.displayProfileStartup(name))
        link.send(CommandBuilder.displayProfileStartup())
    }

    // ── The main window's cycling. GLOBAL: a child inheriting -1 follows it. ──

    fun setMainSequential(sequential: Boolean) {
        link.send(CommandBuilder.setPresetOrder(sequential))
        link.send(CommandBuilder.getPresetCycle())
        link.send(CommandBuilder.getWatermark())
        link.send(CommandBuilder.getDisplayWatermark())
    }

    fun setMainCycleSeconds(seconds: Float) {
        link.send(CommandBuilder.setTimeBetweenPresets(seconds))
        link.send(CommandBuilder.getPresetCycle())
        link.send(CommandBuilder.getWatermark())
        link.send(CommandBuilder.getDisplayWatermark())
    }

    fun setMainLocked(locked: Boolean) {
        link.send(CommandBuilder.setPresetLock(locked))
        link.send(CommandBuilder.getPresetCycle())
        link.send(CommandBuilder.getWatermark())
        link.send(CommandBuilder.getDisplayWatermark())
    }

    /**
     * How a display is being driven right now.
     *
     * The reported tier is the answer when the PC has given one; the fallback
     * is the old inference, for the moment before the first GET_DISPLAY_MODE
     * lands. That inference cannot tell a mirror from a display holding its
     * own preset in the parent process, because neither runs a child.
     *
     * `reported` is a PARAMETER rather than a read of the flow inside this
     * function, and deliberately: a plain call reading `_displayModes.value`
     * gives Compose nothing to observe, so the screen drew whatever the map
     * held when it first composed and never moved -- a display the PC had on
     * the preset tier sat there reading Mirror. The caller collects the flow
     * and hands the entry in, which is a dependency Compose can see.
     */
    fun modeOf(display: DisplayInfo, reported: DisplayModeState?): DisplayMode =
        reported?.mode ?: when {
            _children.value.containsKey(display.deviceNumber) -> DisplayMode.Child
            display.enabled -> DisplayMode.Mirror
            else -> DisplayMode.Off
        }

    fun setMode(deviceNumber: Int, mode: DisplayMode) {
        link.send(CommandBuilder.setDisplayMode(deviceNumber, mode))
        refresh()
        if (mode == DisplayMode.Child) pollUntilChildSettles(deviceNumber)
    }

    /**
     * Move a display between drawing its own preset here and in an instance of
     * its own. Only meaningful once it is on the own-preset tier at all.
     */
    fun setIsolatedProcess(deviceNumber: Int, isolated: Boolean) =
        setMode(deviceNumber, if (isolated) DisplayMode.Child else DisplayMode.Preset)

    /**
     * SET_DISPLAY_MODE replies before the child exists. Spawning runs on the
     * PC's child worker: CreateProcess, then a wait for the new instance's pipe
     * to appear, with a 60s timeout. Until it answers, GET_CHILDREN reports the
     * display as absent — so one refresh would leave the card reading "starting"
     * forever even on success.
     */
    private fun pollUntilChildSettles(deviceNumber: Int) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            repeat(POLL_ATTEMPTS) {
                delay(POLL_INTERVAL_MS)
                link.send(CommandBuilder.getChildren())
                val state = _children.value[deviceNumber]?.state
                if (state == "ready" || state == "failed") return@launch
            }
        }
    }

    private var pollJob: Job? = null

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
        // 33 * 2s comfortably outstrips the PC's 60s pipe-appear timeout.
        const val POLL_ATTEMPTS = 33

        // Longer than a reply takes, and than its parts are kept waiting
        // for; short enough that someone looking at the screen sees it fill.
        const val REASK_MS = 3_000L
        // Asked three times in all. A reply that has failed to arrive three
        // times is not being lost by chance.
        const val REASKS = 2

        // Long enough for the several reasons to refresh at once to land
        // together; far too short to be seen as a wait.
        const val SETTLE_MS = 150L
    }

    /**
     * Moves the render window to this display, making it the primary.
     *
     * Takes the whole DisplayInfo because MOVE_TO_DISPLAY is addressed by
     * position in the monitor enumeration, not by the DISPLAYn number the rest
     * of the display commands use — passing deviceNumber here moves the window
     * to the wrong monitor whenever the two disagree.
     */
    fun makePrimary(display: DisplayInfo) {
        link.send(CommandBuilder.moveToMonitorPosition(display.monIndex + 1))
        refresh()
    }

    fun setDisplayOpacity(deviceNumber: Int, value: Int) =
        link.send(CommandBuilder.setMirrorOpacity(deviceNumber, value))

    /** The MAIN window's watermark. Absolute, and readable, so it is a switch. */
    fun setPrimaryWatermark(enabled: Boolean) {
        link.send(CommandBuilder.setWatermark(enabled))
        link.send(CommandBuilder.getWatermark())
        link.send(CommandBuilder.getDisplayWatermark())
    }

    /**
     * Watermark on every mirror at once, which is the only other scope the PC
     * has -- there is no per-display watermark.
     *
     * The verb is a TOGGLE, so this sends it only when the current state
     * disagrees with what was asked for. That is a small race against anything
     * changing it at the same moment, and it self-corrects on the next read;
     * an absolute setter would remove it entirely.
     */
    fun setMirrorWatermark(enabled: Boolean) {
        link.send(CommandBuilder.setMirrorWatermark(enabled))
        refresh()
    }

    /** Always-on-top for the primary and every mirror; there is no per-display form. */
    fun setAlwaysOnTop(on: Boolean) {
        link.send(CommandBuilder.setAlwaysOnTop(on))
        refresh()
    }

    fun raiseWindow() = link.send(CommandBuilder.raiseWindow())

    /**
     * Bring one display to the front.
     *
     * Only a display running its own instance has a window of its own to
     * raise. The primary's window is the one a mirror and an in-process
     * own-preset display are both drawn into, so raising it covers them too.
     */
    fun raiseDisplay(deviceNumber: Int, isPrimary: Boolean) =
        link.send(
            if (ownsItsWindow(deviceNumber) && !isPrimary) {
                CommandBuilder.raiseDisplayWindow(deviceNumber)
            } else {
                CommandBuilder.raiseWindow()
            },
        )

    /**
     * The counterpart. LOWER_WINDOW drops always-on-top first at the PC end,
     * or the move would stay inside the topmost band and do nothing visible.
     */
    fun lowerDisplay(deviceNumber: Int, isPrimary: Boolean) =
        link.send(
            if (ownsItsWindow(deviceNumber) && !isPrimary) {
                CommandBuilder.lowerDisplayWindow(deviceNumber)
            } else {
                CommandBuilder.lowerWindow()
            },
        )

    /**
     * Whether this display has a window of its own to move in the z-order.
     *
     * Only the Child tier does. A mirror and an in-process own-preset display
     * are both drawn into the main window, so the instance-wide verb is the
     * whole of what is available for them.
     */
    private fun ownsItsWindow(deviceNumber: Int): Boolean =
        _displayModes.value[deviceNumber]?.mode?.let { it == DisplayMode.Child }
            ?: _children.value.containsKey(deviceNumber)

    /** Watermark one display on its own. Readable, so this drives a switch. */
    fun setDisplayWatermark(deviceNumber: Int, enabled: Boolean) {
        link.send(CommandBuilder.setDisplayWatermark(deviceNumber, enabled))
        link.send(CommandBuilder.getDisplayWatermark())
    }

    fun setDisplayClickThrough(deviceNumber: Int, enabled: Boolean) =
        link.send(CommandBuilder.setMirrorClickThru(deviceNumber, enabled))

    fun setDisplayIndependent(deviceNumber: Int, enabled: Boolean) =
        link.send(CommandBuilder.setMirrorIndependent(deviceNumber, enabled))

    // ── Per-display preset control, for a display in child mode ───────────────

    fun displayNext(deviceNumber: Int) = displayStep(deviceNumber, next = true)

    fun displayPrev(deviceNumber: Int) = displayStep(deviceNumber, next = false)

    /**
     * The addressed verb, with the relay kept for a PC that still needs it.
     *
     * DISPLAY_NEXT= used to refuse a child-owned display with `no_surface`:
     * the handler looked for an in-process render surface and a child has
     * none, so Previous and Next were dead on exactly the displays running a
     * preset of their own. The PC has the child branch now, and it is the
     * better route -- it steps the sequence the child is actually keeping,
     * where the relay only fires a signal. The fix shipped alongside the
     * tiers, so `displaytiers` is what says this PC has it.
     */
    private fun displayStep(deviceNumber: Int, next: Boolean) {
        val needsRelay = PcFeature.DISPLAY_TIERS !in identity.value &&
            _children.value.containsKey(deviceNumber)
        link.send(
            when {
                needsRelay -> CommandBuilder.childPresetStep(deviceNumber, next)
                next -> CommandBuilder.displayNext(deviceNumber)
                else -> CommandBuilder.displayPrev(deviceNumber)
            },
        )
        link.send(CommandBuilder.getChildren())
    }

    fun setDisplayOrder(deviceNumber: Int, sequential: Boolean) {
        link.send(CommandBuilder.setDisplayOrder(deviceNumber, sequential))
        link.send(CommandBuilder.getChildren())
    }

    /** 0 stops cycling; anything above it is the interval in seconds. */
    fun setDisplayCycleSeconds(deviceNumber: Int, seconds: Float) {
        link.send(CommandBuilder.setDisplayTimeBetween(deviceNumber, seconds))
        link.send(CommandBuilder.getChildren())
    }

    fun setGlobalOpacity(value: Int) = link.send(CommandBuilder.setMirrorOpacity(value))
    fun setGlobalClickThrough(enabled: Boolean) =
        link.send(CommandBuilder.setMirrorClickThru(enabled))

    fun setMirrorMaxFps(fps: Int) = link.send(CommandBuilder.setMirrorMaxFps(fps))
    fun wipeMirrors() = link.send(CommandBuilder.setMirrorWipe())
}
