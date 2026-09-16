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
import com.sheinsez.mdropdx12.remote.data.model.PresetCycle
import com.sheinsez.mdropdx12.remote.network.CommandBuilder
import com.sheinsez.mdropdx12.remote.network.ConnectionState
import com.sheinsez.mdropdx12.remote.network.MessageParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class DisplaysViewModel(application: Application) : AndroidViewModel(application) {
    private val connectionManager = (application as MdrApp).connectionManager

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
    val identity = connectionManager.identity

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

    // A list arrives as one reply of many lines, so it is gathered fresh each
    // time rather than appended to: a second listing must replace the first,
    // not double it.
    private val pendingProfiles = mutableListOf<DisplayProfile>()
    private var sawProfileTerminator = false

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
                    connectionManager.send(CommandBuilder.getAllFullscreen())
                }
                if (PcFeature.IDLE_ACTIVE in id) {
                    connectionManager.send(CommandBuilder.getIdleActive())
                }
            }
        }

        viewModelScope.launch {
            connectionManager.messages.collect { raw ->
                // A profile listing is many lines in one message.
                for (line in raw.lineSequence()) {
                    val one = line.trim()
                    when {
                        one.startsWith("DISPLAY_PROFILE|") -> {
                            MessageParser.parseDisplayProfile(one)?.let { pendingProfiles += it }
                            sawProfileTerminator = true
                        }

                        // An empty folder answers with no DISPLAY_PROFILE lines
                        // at all, so the startup reply -- which is asked for in
                        // the same breath -- is what says the listing happened.
                        one.startsWith("DISPLAY_PROFILE_STARTUP|") -> sawProfileTerminator = true

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

                        one.startsWith("DISPLAY_PROFILE_STARTUP|") ->
                            MessageParser.parseDisplayProfileStartup(one)
                                ?.let { _startup.value = it }

                        // Saving, deleting or loading changes what the list
                        // should say, and none of them answers with a list.
                        // refreshProfiles is itself gated on the section
                        // being open, so this is free when it is not.
                        one.startsWith("DISPLAY_PROFILE_SAVED|") ||
                            one.startsWith("PROFILE_LOADED=") -> refreshProfiles()
                    }
                }
                if (sawProfileTerminator) {
                    sawProfileTerminator = false
                    _profilesLoaded.value = true
                }
                if (pendingProfiles.isNotEmpty()) {
                    // The PC's order, rendered literally. It orders these by
                    // name BECAUSE the name is a timestamp, and its own
                    // next/previous profile stepping walks that same order --
                    // so re-sorting here would put this list out of step with
                    // both the PC's window and its hotkeys.
                    _profiles.value = pendingProfiles.toList()
                    pendingProfiles.clear()
                }

                val msg = raw
                when {
                    msg.startsWith("MIRRORS|") -> {
                        val state = MessageParser.parseMirrors(msg)
                        _mirrorState.value = state
                        if (state != null) seedSelection(state)
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
                    // cheaper than mirroring its state machine here, and keeps
                    // the screen honest when another client changes something.
                    msg.startsWith("MIRROR_OPACITY=") ||
                        msg.startsWith("MIRROR_CLICKTHRU=") ||
                        msg.startsWith("MIRROR_ENABLED=") ||
                        msg.startsWith("MIRROR_INDEPENDENT=") ||
                        msg.startsWith("DISPLAY_MODE=") -> refresh()
                }
            }
        }

        // A reconnect leaves every cached value stale — the PC may have been
        // restarted, or its displays rearranged, while the phone was away.
        viewModelScope.launch {
            connectionManager.connectionState
                .filter { it == ConnectionState.Connected }
                .collect { refresh() }
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

    fun refresh() {
        connectionManager.send(CommandBuilder.diagMirrors())
        connectionManager.send(CommandBuilder.getChildren())
        connectionManager.send(CommandBuilder.getPresetCycle())
        connectionManager.send(CommandBuilder.getWatermark())
        connectionManager.send(CommandBuilder.getDisplayWatermark())
        connectionManager.send(CommandBuilder.getDisplayModes())
        refreshProfiles()
        if (PcFeature.ALL_FULLSCREEN in identity.value) {
            connectionManager.send(CommandBuilder.getAllFullscreen())
        }
    }

    /**
     * Walk away: every screen showing the visualiser, or every screen back as
     * it was. The PC restores each window exactly as found, so this is safe to
     * use without knowing what anything was.
     */
    /** The idle action, on demand: the walk-away state the PC already has. */
    fun setIdleActive(on: Boolean) {
        connectionManager.send(CommandBuilder.setIdleActive(on))
        connectionManager.send(CommandBuilder.getIdleActive())
    }

    fun setAllFullscreen(on: Boolean) {
        connectionManager.send(CommandBuilder.setAllFullscreen(on))
        connectionManager.send(CommandBuilder.getAllFullscreen())
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
    fun refreshProfiles() {
        if (PcFeature.DISPLAY_PROFILES !in identity.value) return
        pendingProfiles.clear()
        _profilesLoaded.value = false
        connectionManager.send(CommandBuilder.displayProfileList())
        connectionManager.send(CommandBuilder.displayProfileStartup())
    }

    /** An empty name banks a timestamp, which is how these sort. */
    fun saveProfile(name: String = "") {
        connectionManager.send(CommandBuilder.displayProfileSave(name.trim()))
    }

    fun loadProfile(name: String) =
        connectionManager.send(CommandBuilder.displayProfileLoad(name))

    fun deleteProfile(name: String) {
        connectionManager.send(CommandBuilder.displayProfileDelete(name))
        refreshProfiles()
    }

    /**
     * Sets both halves at once: which profile, and that one loads at all.
     * An empty name switches startup loading off.
     */
    fun setStartupProfile(name: String) {
        connectionManager.send(CommandBuilder.displayProfileStartup(name))
        connectionManager.send(CommandBuilder.displayProfileStartup())
    }

    // ── The main window's cycling. GLOBAL: a child inheriting -1 follows it. ──

    fun setMainSequential(sequential: Boolean) {
        connectionManager.send(CommandBuilder.setPresetOrder(sequential))
        connectionManager.send(CommandBuilder.getPresetCycle())
        connectionManager.send(CommandBuilder.getWatermark())
        connectionManager.send(CommandBuilder.getDisplayWatermark())
    }

    fun setMainCycleSeconds(seconds: Float) {
        connectionManager.send(CommandBuilder.setTimeBetweenPresets(seconds))
        connectionManager.send(CommandBuilder.getPresetCycle())
        connectionManager.send(CommandBuilder.getWatermark())
        connectionManager.send(CommandBuilder.getDisplayWatermark())
    }

    fun setMainLocked(locked: Boolean) {
        connectionManager.send(CommandBuilder.setPresetLock(locked))
        connectionManager.send(CommandBuilder.getPresetCycle())
        connectionManager.send(CommandBuilder.getWatermark())
        connectionManager.send(CommandBuilder.getDisplayWatermark())
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
        connectionManager.send(CommandBuilder.setDisplayMode(deviceNumber, mode))
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
                connectionManager.send(CommandBuilder.getChildren())
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
        connectionManager.send(CommandBuilder.moveToMonitorPosition(display.monIndex + 1))
        refresh()
    }

    fun setDisplayOpacity(deviceNumber: Int, value: Int) =
        connectionManager.send(CommandBuilder.setMirrorOpacity(deviceNumber, value))

    /** The MAIN window's watermark. Absolute, and readable, so it is a switch. */
    fun setPrimaryWatermark(enabled: Boolean) {
        connectionManager.send(CommandBuilder.setWatermark(enabled))
        connectionManager.send(CommandBuilder.getWatermark())
        connectionManager.send(CommandBuilder.getDisplayWatermark())
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
        connectionManager.send(CommandBuilder.setMirrorWatermark(enabled))
        refresh()
    }

    /** Always-on-top for the primary and every mirror; there is no per-display form. */
    fun setAlwaysOnTop(on: Boolean) {
        connectionManager.send(CommandBuilder.setAlwaysOnTop(on))
        refresh()
    }

    fun raiseWindow() = connectionManager.send(CommandBuilder.raiseWindow())

    /**
     * Bring one display to the front.
     *
     * Only a display running its own instance has a window of its own to
     * raise. The primary's window is the one a mirror and an in-process
     * own-preset display are both drawn into, so raising it covers them too.
     */
    fun raiseDisplay(deviceNumber: Int, isPrimary: Boolean) =
        connectionManager.send(
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
        connectionManager.send(
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
        connectionManager.send(CommandBuilder.setDisplayWatermark(deviceNumber, enabled))
        connectionManager.send(CommandBuilder.getDisplayWatermark())
    }

    fun setDisplayClickThrough(deviceNumber: Int, enabled: Boolean) =
        connectionManager.send(CommandBuilder.setMirrorClickThru(deviceNumber, enabled))

    fun setDisplayIndependent(deviceNumber: Int, enabled: Boolean) =
        connectionManager.send(CommandBuilder.setMirrorIndependent(deviceNumber, enabled))

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
        connectionManager.send(
            when {
                needsRelay -> CommandBuilder.childPresetStep(deviceNumber, next)
                next -> CommandBuilder.displayNext(deviceNumber)
                else -> CommandBuilder.displayPrev(deviceNumber)
            },
        )
        connectionManager.send(CommandBuilder.getChildren())
    }

    fun setDisplayOrder(deviceNumber: Int, sequential: Boolean) {
        connectionManager.send(CommandBuilder.setDisplayOrder(deviceNumber, sequential))
        connectionManager.send(CommandBuilder.getChildren())
    }

    /** 0 stops cycling; anything above it is the interval in seconds. */
    fun setDisplayCycleSeconds(deviceNumber: Int, seconds: Float) {
        connectionManager.send(CommandBuilder.setDisplayTimeBetween(deviceNumber, seconds))
        connectionManager.send(CommandBuilder.getChildren())
    }

    fun setGlobalOpacity(value: Int) = connectionManager.send(CommandBuilder.setMirrorOpacity(value))
    fun setGlobalClickThrough(enabled: Boolean) =
        connectionManager.send(CommandBuilder.setMirrorClickThru(enabled))

    fun setMirrorMaxFps(fps: Int) = connectionManager.send(CommandBuilder.setMirrorMaxFps(fps))
    fun wipeMirrors() = connectionManager.send(CommandBuilder.setMirrorWipe())
}
