package com.sheinsez.mdropdx12.remote.network

import com.sheinsez.mdropdx12.remote.data.model.DisplayMode
import com.sheinsez.mdropdx12.remote.data.model.PresetFilter
import java.util.Locale

object CommandBuilder {
    /**
     * Format a value for the wire, never for display.
     *
     * The PC parses these with _wtof, which stops at the first character that is
     * not part of a number. On a comma-decimal locale the platform default would
     * produce "0,50", which arrives as 0 — silently, since nothing rejects it.
     * Locale.ROOT keeps the decimal point regardless of the device's language.
     */
    fun wireFloat(value: Float, decimals: Int = 3): String =
        String.format(Locale.ROOT, "%.${decimals}f", value)

    // ButtonWindow-style aliases that are not in pipe_server.cpp s_signalTable.
    private val signalAliases = mapOf(
        "SIG_MIRROR" to "MIRROR",
        "SIG_MIRROR_WM" to "MIRROR_WM",
        "SIG_FULLSCREEN" to "FULLSCREEN",
        "SIG_WATERMARK" to "WATERMARK",
        "SIG_CAPTURE" to "CAPTURE",
        "SIG_BORDERLESS" to "BORDERLESS_FS",
        "BORDERLESS" to "BORDERLESS_FS",
    )

    fun signal(name: String): String {
        val trimmed = name.trim()
        val canonical = signalAliases[trimmed] ?: trimmed.removePrefix("SIG_")
        return "SIGNAL|$canonical"
    }
    fun sendKey(hexCode: String) = "SEND=$hexCode"
    fun raw(command: String) = command
    fun message(text: String) = "MSG|text=$text"

    fun wave(params: Map<String, String>): String {
        val parts = params.entries.joinToString("|") { "${it.key}=${it.value}" }
        return "WAVE|$parts"
    }

    // ── Displays ──────────────────────────────────────────────────────────────
    // Every "display" argument below is the N in \\.\DISPLAYN, which is NOT the
    // monN index DIAG_MIRRORS enumerates with. Use DisplayInfo.deviceNumber.

    fun setMirrorOpacity(value: Int) = "SET_MIRROR_OPACITY=$value"
    fun setMirrorOpacity(display: Int, value: Int) = "SET_MIRROR_OPACITY=$display,$value"
    /**
     * Every screen showing the visualiser, or every screen back as it was.
     *
     * Absolute, never a toggle: a surface that cannot read the PC's state at
     * the moment of the tap must say what it wants, not "change it".
     *
     * OFF sends 2, "leave fullscreen regardless", not 0. 0 restores what the
     * snapshot found, and when the windows were already fullscreen -- which
     * they usually are here -- that restores fullscreen and the switch does
     * nothing at all. That was forgejo#98, and 2 exists because of it.
     */
    fun setAllFullscreen(on: Boolean) = "SET_ALL_FULLSCREEN=${if (on) 1 else 2}"

    /**
     * Put each window back exactly as it was found -- the third form, and NOT
     * what a toggle wants.
     *
     * It restores the state the snapshot recorded, so when the windows were
     * already fullscreen it restores fullscreen and nothing moves. That is
     * correct for an undo and useless for an off switch, which is why the
     * switch above sends 2 (forgejo#98).
     */
    fun restoreAllFullscreen() = "SET_ALL_FULLSCREEN=0"

    fun getAllFullscreen() = "GET_ALL_FULLSCREEN"

    /**
     * Run the idle action now, as if the timer had fired, or end it.
     *
     * Independent of whether the idle timer itself is switched on, and unlike
     * SET_ALL_FULLSCREEN=0 this really does undo itself -- it has a state of
     * its own rather than a snapshot of what things were before.
     */
    fun setIdleActive(on: Boolean) = "SET_IDLE_ACTIVE=${if (on) 1 else 0}"

    fun getIdleActive() = "GET_IDLE_ACTIVE"

    // ── Display profiles, by name ──────────────────────────────────────
    //
    // The path-taking SAVE_/LOAD_DISPLAY_PROFILE= verbs still exist and are
    // left alone: they are for scripts. A remote cannot learn the profiles
    // folder, so it addresses profiles the way it addresses mixer and VFX
    // ones -- by name.

    fun displayProfileList() = "DISPLAY_PROFILE_LIST"

    /** An empty name banks a timestamp, which is how these are meant to sort. */
    fun displayProfileSave(name: String) = "DISPLAY_PROFILE_SAVE=$name"

    fun displayProfileLoad(name: String) = "DISPLAY_PROFILE_LOAD=$name"
    fun displayProfileDelete(name: String) = "DISPLAY_PROFILE_DELETE=$name"

    /** No argument queries; a name sets it and enables loading. */
    fun displayProfileStartup() = "DISPLAY_PROFILE_STARTUP"

    /** An empty name turns startup loading off without forgetting anything else. */
    fun displayProfileStartup(name: String) = "DISPLAY_PROFILE_STARTUP=$name"

    /**
     * Watermark mode on the PRIMARY window, absolutely.
     *
     * Separate from the per-display verb because the primary is the one whose
     * state can be read back -- the MIRRORS line carries it, and GET_WATERMARK
     * answers for it -- so this one can drive a real switch.
     */
    fun setWatermark(enabled: Boolean) = "SET_WATERMARK=${if (enabled) 1 else 0}"

    fun getWatermark() = "GET_WATERMARK"

    /** Watermark state for every display at once. */
    fun getDisplayWatermark() = "GET_DISPLAY_WATERMARK"

    /**
     * Watermark on every MIRROR at once, absolutely.
     *
     * A mirror has no window of its own, so this is the only way to watermark
     * one -- SET_DISPLAY_WATERMARK is the primary's. Absolute, so no read and
     * compare, and no race with anything changing it in between.
     */
    fun setMirrorWatermark(enabled: Boolean) =
        "SET_MIRROR_WATERMARK=${if (enabled) 1 else 0}"

    /**
     * Watermark mode for ONE display's window: click-through and low opacity
     * together, set absolutely rather than toggled.
     *
     * Absolute is what makes it usable as a quick control -- a toggle verb
     * cannot be trusted from a surface that does not know the current state,
     * and both halves land in one command instead of a click-through write
     * followed by an opacity write that the user watches happen separately.
     */
    /**
     * Watermark ONE display, whatever it is: the primary, an in-process
     * mirror, or a child. Persisted per display on the PC.
     *
     * This was refused for anything but the primary until the display rework;
     * it is the general form now, and GET_DISPLAY_WATERMARK reads it back, so
     * every display gets a switch rather than a pair of buttons.
     */
    fun setDisplayWatermark(display: Int, enabled: Boolean) =
        "SET_DISPLAY_WATERMARK=$display,${if (enabled) 1 else 0}"

    fun setMirrorClickThru(enabled: Boolean) = "SET_MIRROR_CLICKTHRU=${if (enabled) 1 else 0}"
    fun setMirrorClickThru(display: Int, enabled: Boolean) =
        "SET_MIRROR_CLICKTHRU=$display,${if (enabled) 1 else 0}"
    fun setMirrorEnabled(enabled: Boolean) = "SET_MIRROR_ENABLED=${if (enabled) 1 else 0}"
    fun setMirrorEnabled(display: Int, enabled: Boolean) =
        "SET_MIRROR_ENABLED=$display,${if (enabled) 1 else 0}"
    fun setMirrorIndependent(enabled: Boolean) = "SET_MIRROR_INDEPENDENT=${if (enabled) 1 else 0}"
    fun setMirrorIndependent(display: Int, enabled: Boolean) =
        "SET_MIRROR_INDEPENDENT=$display,${if (enabled) 1 else 0}"
    fun setMirrorMaxFps(fps: Int) = "SET_MIRROR_MAXFPS=$fps"
    fun getMirrorMaxFps() = "GET_MIRROR_MAXFPS"
    fun setMirrorWipe() = "SET_MIRROR_WIPE"

    /**
     * off | mirror | preset | child, cheapest first. `preset` gives the display
     * its own preset in the parent process; `child` gives it one in an instance
     * of its own.
     */
    fun setDisplayMode(display: Int, mode: DisplayMode) =
        "SET_DISPLAY_MODE=$display,${mode.name.lowercase()}"

    /** Replies DISPLAY_MODE| with one entry per display, then END. */
    fun getDisplayModes() = "GET_DISPLAY_MODE"

    /** Replies with one CHILD| line per child display, then CHILDREN_END. */
    fun getChildren() = "GET_CHILDREN"

    // ── Per-display preset control (child mode only) ──────────────────────────

    /** Show a preset on display N now. Transient — not its startup preset. */
    fun setDisplayPreset(display: Int, path: String) = "SET_DISPLAY_PRESET=$display,$path"

    /** The preset display N returns to when its instance restarts. */
    fun setDisplayStartupPreset(display: Int, path: String) =
        "SET_DISPLAY_STARTUP_PRESET=$display,$path"

    /** Folder display N cycles through; empty inherits the parent's. */
    fun setDisplayPresetDir(display: Int, path: String) = "SET_DISPLAY_PRESET_DIR=$display,$path"

    /** false = random, true = sequential. */
    fun setDisplayOrder(display: Int, sequential: Boolean) =
        "SET_DISPLAY_ORDER=$display,${if (sequential) 1 else 0}"

    /** Seconds between changes on display N. 0 never cycles, -1 inherits. */
    fun setDisplayTimeBetween(display: Int, seconds: Float) =
        "SET_DISPLAY_TIME_BETWEEN=$display,${wireFloat(seconds, 1)}"

    fun displayNext(display: Int) = "DISPLAY_NEXT=$display"
    fun displayPrev(display: Int) = "DISPLAY_PREV=$display"

    /**
     * Step a CHILD-owned display's preset, down the relay rather than through
     * DISPLAY_NEXT=.
     *
     * DISPLAY_NEXT=<N> answers `no_surface` whenever a child owns the display:
     * the PC's handler looks for an in-process MirrorSurface and a child has
     * none, so the buttons were dead on precisely the displays that have a
     * preset of their own. The relay reaches the child and the step lands.
     * Verified against build Sep 12 2026, protocol 9.
     */
    fun childPresetStep(display: Int, next: Boolean) =
        relayToDisplay(display, if (next) "SIGNAL|NEXT_PRESET" else "SIGNAL|PREV_PRESET")

    /** Relay any command to display N's child instance. A few are denied. */
    fun relayToDisplay(display: Int, command: String) = "DISPLAY|$display|$command"

    /**
     * Keep a display's own instance above everything else on that monitor.
     *
     * A child is brought to the front once when it starts and then behaves like
     * any other window, which is right by default -- pinning it leaves no way to
     * reach anything else on that screen. This is the deliberate version, for
     * when a display is genuinely given over to the visualiser.
     */
    /**
     * Always-on-top, on the primary and every mirror together. Instance-wide:
     * the PC has no per-display form, and the relay that used to fake one is
     * refused now that displays render in this process.
     */
    fun setAlwaysOnTop(on: Boolean) = "SET_ALWAYS_ON_TOP=${if (on) 1 else 0}"

    fun getAlwaysOnTop() = "GET_ALWAYS_ON_TOP"

    /** Bring a display's instance to the front now, without pinning it. */
    /** Raises this instance's window. Instance-wide; there is no per-display form. */
    fun raiseWindow() = "RAISE_WINDOW"

    /**
     * Bring ONE display's window to the front, without taking focus.
     *
     * Only a display running its own instance has a window to raise. A mirror
     * and an in-process own-preset display both draw into the primary's, so
     * for those the primary's own RAISE_WINDOW is the whole of it. RAISE_WINDOW
     * is not among the relay's denied verbs, and the PC's own displays page
     * raises a child exactly this way.
     *
     * There is no counterpart: nothing in the command set lowers a window, so
     * "send to back" cannot be offered yet.
     */
    fun raiseDisplayWindow(display: Int) = relayToDisplay(display, "RAISE_WINDOW")

    /**
     * The other half, asked for in mdropdx12 forgejo#237 and not shipped yet.
     * Gated on the `windowzorder` capability, so these are never sent to a PC
     * that would only refuse them.
     */
    fun lowerWindow() = "LOWER_WINDOW"

    fun lowerDisplayWindow(display: Int) = relayToDisplay(display, "LOWER_WINDOW")

    /**
     * Centres the render window — the primary — on a monitor.
     *
     * NOTE the argument is NOT the DISPLAYn device number every other command
     * here takes. MOVE_TO_DISPLAY walks m_displayOutputs and matches a 1-based
     * position in that enumeration, so this wants monIndex + 1. The two happen
     * to agree on a desktop whose displays are numbered 1..n with none missing,
     * which is exactly why passing the wrong one survives casual testing.
     *
     * It moves only: SWP_NOSIZE keeps the window's current size, so this does
     * not resize or fullscreen it on the destination.
     */
    fun moveToMonitorPosition(oneBasedMonitorIndex: Int) =
        "MOVE_TO_DISPLAY=$oneBasedMonitorIndex"
    fun diagMirrors() = "DIAG_MIRRORS"
    fun state() = "STATE"

    // ── Preset browsing (presets.json, not the preset folder) ─────────────────

    /** Load on the main window. */
    fun loadPreset(path: String) = "PRESET=$path"

    /**
     * One page of the annotation database.
     *
     * The PC filters and sorts before paging, so `total` in the terminator is
     * the size of the FILTERED set — which is the length a list paging inside a
     * folder needs, not the size of the database.
     */
    fun browsePresets(filter: PresetFilter, offset: Int, count: Int): String =
        buildString {
            append("BROWSE_PRESETS=$offset,$count")
            append(",sort=${filter.sort.wire}")
            if (filter.query.isNotBlank()) append(",q=${filter.query}")
            filter.folder?.let { append(",folder=$it") }
            filter.tag?.let { append(",tag=$it") }
            filter.flag?.let { append(",flag=$it") }
            if (filter.minRating > 0) append(",minrating=${filter.minRating}")
        }

    // ── Main-window preset cycling (GLOBAL, not per display) ──────────────────
    // A child whose own interval is -1 inherits these, so they move it too.

    fun getPresetCycle() = "GET_PRESET_CYCLE"
    /** false = random, true = sequential. */
    fun setPresetOrder(sequential: Boolean) = "SET_PRESET_ORDER=${if (sequential) 1 else 0}"
    fun setPresetLock(locked: Boolean) = "SET_PRESET_LOCK=${if (locked) 1 else 0}"
    /** Seconds between automatic changes; 0 disables cycling. */
    fun setTimeBetweenPresets(seconds: Float) =
        "SET_TIME_BETWEEN_PRESETS=${wireFloat(seconds, 1)}"

    fun getPresetFolders() = "GET_PRESET_FOLDERS"
    fun getPresetTags() = "GET_PRESET_TAGS"

    // Addressed by content hash, so a preset can be annotated WITHOUT being
    // loaded. The unaddressed forms still mean "the running preset".
    fun setPresetRatingFor(hash: String, rating: Int) = "SET_PRESET_RATING_FOR=$hash,$rating"
    fun setPresetFlagFor(hash: String, flag: String, on: Boolean) =
        "SET_PRESET_FLAG_FOR=$hash,$flag,${if (on) 1 else 0}"
    fun setPresetTagFor(hash: String, tag: String, on: Boolean) =
        "SET_PRESET_TAG_FOR=$hash,$tag,${if (on) 1 else 0}"

    fun colorHue(value: Float) = "COL_HUE=$value"
    fun colorSaturation(value: Float) = "COL_SATURATION=$value"
    fun colorBrightness(value: Float) = "COL_BRIGHTNESS=$value"
    fun hueAuto(enabled: Boolean) = "HUE_AUTO=${if (enabled) 1 else 0}"

    fun varTime(value: Float) = "VAR_TIME=$value"
    fun varIntensity(value: Float) = "VAR_INTENSITY=$value"
    fun varQuality(value: Float) = "VAR_QUALITY=$value"

    fun amp(left: Float, right: Float) = "AMP|l=$left|r=$right"
    fun fftAttack(value: Float) = "FFT_ATTACK=$value"
    fun fftDecay(value: Float) = "FFT_DECAY=$value"

    fun setDeviceVolume(value: Float) = "SET_DEVICE_VOLUME=${wireFloat(value, 2)}"
    fun setDeviceMute(muted: Boolean) = "SET_DEVICE_MUTE=${if (muted) 1 else 0}"
    fun getDeviceVolume() = "GET_DEVICE_VOLUME"
    fun toggleDeviceMute() = "TOGGLE_DEVICE_MUTE"
    fun getAudioDevices() = "GET_AUDIO_DEVICES"
    fun setAudioDevice(name: String) = "DEVICE=OUT|$name"

    fun shaderImport(json: String) = "SHADER_IMPORT=$json"
    fun shaderGlsl(code: String) = "SHADER_GLSL=$code"

    fun mediaPlayPause() = sendKey("0xB3")
    fun mediaNext() = sendKey("0xB0")
    fun mediaPrev() = sendKey("0xB1")

    fun nextPreset() = signal("NEXT_PRESET")
    fun prevPreset() = signal("PREV_PRESET")

    // -- Audio mixer -----------------------------------------------------------
    // Volume is 0..100 in the UI and 0..1 on the wire. wireFloat pins
    // Locale.ROOT: the PC parses with _wtof, which stops at a comma.

    private fun scalar(percent: Int) = wireFloat(percent.coerceIn(0, 100) / 100f, 3)

    /**
     * Gates ALL polling on the PC. Send 1 when the screen is in the foreground
     * and 0 the moment it is not — the count is process-wide, so a client that
     * never sends 0 leaves the PC polling for nobody.
     */
    fun mixerSubscribe(on: Boolean) = "MIXER_SUBSCRIBE=${if (on) 1 else 0}"

    /** Chunked between MIXER_BEGIN and MIXER_END. Read until the terminator. */
    fun mixerState() = "MIXER_STATE"
    fun mixerOrder() = "MIXER_ORDER"

    /**
     * Re-reads every provider now. Nothing polls unless a client is
     * subscribed, so a channel that appeared meanwhile would otherwise not
     * show. It reaches Sonar over HTTP, so it is not free.
     */
    fun mixerRefresh() = "MIXER_REFRESH"

    // The four MIXER_SLOT verbs are gone from here because they are gone from
    // the PC. It replaced two named slots, each pointing at one fader, with a
    // ticked GROUP of faders -- the same shape this app already uses for the
    // volume rocker. They are still listed in its HELP table, and a builder for
    // a verb that no longer exists is a trap: the PC does not refuse an
    // unrecognised command, it draws it on screen as text.
    fun mixerSet(channelId: String, faderId: String, percent: Int) =
        "MIXER_SET=$channelId|$faderId|${scalar(percent)}"
    fun mixerMute(channelId: String, faderId: String, muted: Boolean) =
        "MIXER_MUTE=$channelId|$faderId|${if (muted) 1 else 0}"

    /**
     * What this build is and what it supports. The `features=` list is the
     * useful part: the app degrades per feature, not per build.
     */
    fun getVersion() = "GET_VERSION"

    /**
     * Re-reads Bluetooth battery levels and nothing else, then pushes the fader
     * records to subscribers.
     *
     * Its own verb because battery is the one field in the snapshot that
     * changes with no event to announce it. MIXER_REFRESH would do the job too
     * and also re-read every provider including Sonar over HTTP, which is far
     * more than this needs.
     */
    fun mixerBattery() = "MIXER_BATTERY"

    /**
     * Hide or reveal one fader on every surface.
     *
     * A view preference only: the fader keeps its level, its place in the
     * order and every write verb. This is the answer to a list too long to
     * find anything in, not a way to disable a channel.
     */
    fun mixerHide(channelId: String, faderId: String, hidden: Boolean) =
        "MIXER_HIDE=$channelId|$faderId|${if (hidden) 1 else 0}"

    /** The hidden set, so a 'show hidden' toggle needs no walk of the state. */
    fun mixerHidden() = "MIXER_HIDDEN"

    /**
     * The user's short name for one fader. An empty name clears it back to
     * whatever the channel and the fader are called.
     */
    fun mixerFaderName(channelId: String, faderId: String, name: String) =
        "MIXER_FADER_NAME=$channelId|$faderId|$name"

    /**
     * The list the PC DRAWS. MIXER_ORDER is the list it STORES, and the two
     * differ: three view rules sit between them.
     */
    fun mixerView() = "MIXER_VIEW"

    /** The arrangement token alone, for the price of one short message. */
    fun mixerOrderRev() = "MIXER_ORDER_REV"

    /**
     * By fader key, never by row index.
     *
     * `delta` is in MIXER_ORDER space, not in drawn rows: moving a row one
     * place up the screen is a jump of however many stored positions lie
     * between it and the row above -- see orderDeltaForVisibleMove.
     *
     * `rev` is the arrangement the caller was looking at. Supplied, a move
     * made against a list that has since changed is refused rather than
     * applied to the wrong fader; omitted, this is the unguarded form the PC
     * has always accepted.
     */
    fun mixerOrderMove(key: String, delta: Int, rev: String = "") =
        "MIXER_ORDER_MOVE=$key|$delta" + if (rev.isEmpty()) "" else "|$rev"

    /**
     * A whole arrangement at once — the drag-to-reorder path, which a run of
     * swaps cannot express without writing every intermediate state to disk.
     *
     * Comma-separated, because a bar appears in every key and a comma in none.
     * The token is NOT optional here: a complete list asserts what the whole
     * order should be, so it has to be built from what the PC currently holds.
     */
    fun mixerOrderSet(rev: String, keys: List<String>) =
        "MIXER_ORDER_SET=$rev|" + keys.joinToString(",")
}
