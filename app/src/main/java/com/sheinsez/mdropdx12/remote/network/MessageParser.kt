package com.sheinsez.mdropdx12.remote.network

import com.sheinsez.mdropdx12.remote.data.model.ChildInstance
import com.sheinsez.mdropdx12.remote.data.model.DisplayInfo
import com.sheinsez.mdropdx12.remote.data.model.DisplayMode
import com.sheinsez.mdropdx12.remote.data.model.DisplayModeState
import com.sheinsez.mdropdx12.remote.data.model.PresetCycle
import com.sheinsez.mdropdx12.remote.data.model.PresetGroup
import com.sheinsez.mdropdx12.remote.data.model.PresetRow
import com.sheinsez.mdropdx12.remote.data.model.MirrorState
import com.sheinsez.mdropdx12.remote.data.model.AllFullscreen
import com.sheinsez.mdropdx12.remote.data.model.DisplayProfile
import com.sheinsez.mdropdx12.remote.data.model.DisplayProfileStartup
import com.sheinsez.mdropdx12.remote.data.model.MixerDevice
import com.sheinsez.mdropdx12.remote.data.model.MixerFader
import com.sheinsez.mdropdx12.remote.data.model.MixerView
import com.sheinsez.mdropdx12.remote.data.model.MixerViewRow
import com.sheinsez.mdropdx12.remote.data.model.ServerIdentity
import com.sheinsez.mdropdx12.remote.data.model.VisualizerState

object MessageParser {
    fun parsePreset(message: String): String? {
        if (!message.startsWith("PRESET=")) return null
        val path = message.removePrefix("PRESET=")
        return path.substringAfterLast("\\").substringAfterLast("/").substringBeforeLast(".")
    }

    fun parseTrack(message: String): Triple<String, String, String>? {
        if (!message.startsWith("TRACK|")) return null
        val params = parseKeyValue(message.removePrefix("TRACK|"))
        return Triple(
            params["artist"] ?: "",
            params["title"] ?: "",
            params["album"] ?: "",
        )
    }

    fun parseOpacity(message: String): Int? {
        if (!message.startsWith("OPACITY=")) return null
        return message.removePrefix("OPACITY=").toIntOrNull()
    }

    fun parseWave(message: String, current: VisualizerState): VisualizerState? {
        if (!message.startsWith("WAVE|")) return null
        val params = parseKeyValue(message.removePrefix("WAVE|"))
        return current.copy(
            waveMode = params["MODE"]?.toIntOrNull() ?: current.waveMode,
            waveAlpha = params["ALPHA"]?.toFloatOrNull() ?: current.waveAlpha,
            waveScale = params["SCALE"]?.toFloatOrNull() ?: current.waveScale,
            waveZoom = params["ZOOM"]?.toFloatOrNull() ?: current.waveZoom,
            waveWarp = params["WARP"]?.toFloatOrNull() ?: current.waveWarp,
            waveRotation = params["ROTATION"]?.toFloatOrNull() ?: current.waveRotation,
            waveDecay = params["DECAY"]?.toFloatOrNull() ?: current.waveDecay,
            waveBrighten = params["BRIGHTEN"]?.let { it == "1" } ?: current.waveBrighten,
            waveDarken = params["DARKEN"]?.let { it == "1" } ?: current.waveDarken,
            waveSolarize = params["SOLARIZE"]?.let { it == "1" } ?: current.waveSolarize,
            waveInvert = params["INVERT"]?.let { it == "1" } ?: current.waveInvert,
            waveAdditive = params["ADDITIVE"]?.let { it == "1" } ?: current.waveAdditive,
            waveThick = params["THICK"]?.let { it == "1" } ?: current.waveThick,
        )
    }

    fun parseSettings(message: String, current: VisualizerState): VisualizerState? {
        if (!message.startsWith("SETTINGS|")) return null
        val params = parseKeyValue(message.removePrefix("SETTINGS|"))
        return current.copy(
            colorHue = params["HUE"]?.toFloatOrNull() ?: current.colorHue,
            fftAttack = params["FFTATTACK"]?.toFloatOrNull() ?: current.fftAttack,
            fftDecay = params["FFTDECAY"]?.toFloatOrNull() ?: current.fftDecay,
            varQuality = params["QUALITY"]?.toFloatOrNull() ?: current.varQuality,
        )
    }

    fun parseDeviceVolume(message: String, current: VisualizerState): VisualizerState? {
        if (!message.startsWith("DEVICE_VOLUME=")) return null
        // Format: DEVICE_VOLUME=0.75|muted=0
        val parts = message.removePrefix("DEVICE_VOLUME=").split("|")
        val vol = parts.firstOrNull()?.toFloatOrNull() ?: return null
        val muted = parts.firstOrNull { it.startsWith("muted=") }
            ?.removePrefix("muted=")?.toIntOrNull()?.let { it != 0 } ?: current.muted
        return current.copy(volume = vol, muted = muted)
    }

    fun parseDeviceMute(message: String, current: VisualizerState): VisualizerState? {
        if (!message.startsWith("DEVICE_MUTE=")) return null
        val muted = message.removePrefix("DEVICE_MUTE=").toIntOrNull()?.let { it != 0 } ?: return null
        return current.copy(muted = muted)
    }

    fun parseAudioDevices(message: String, current: VisualizerState): VisualizerState? {
        if (!message.startsWith("AUDIO_DEVICES|")) return null
        val params = parseKeyValue(message.removePrefix("AUDIO_DEVICES|"))
        val count = params["count"]?.toIntOrNull() ?: return null
        val devices = (0 until count).mapNotNull { i -> params["dev$i"] }
        val active = params["active"] ?: ""
        return current.copy(audioDevices = devices, activeDevice = active)
    }

    /**
     * "WATERMARK=<0|1>" -- the MAIN window's watermark, from GET_WATERMARK.
     *
     * Distinct from the MIRRORS line's `watermark=`, which is every mirror at
     * once. Setting one does not move the other.
     */
    fun parseWatermark(message: String): Boolean? {
        if (!message.startsWith("WATERMARK=")) return null
        return message.removePrefix("WATERMARK=").trim() == "1"
    }

    /**
     * "DISPLAY_WATERMARK|1=0,device=...,ownProcess=1|2=1,...|END"
     *
     * Display number to whether it is watermarked. The device name and who
     * owns the window come along too and are not needed here -- the number is
     * how every other display verb addresses one.
     */
    fun parseDisplayWatermark(message: String): Map<Int, Boolean>? {
        if (!message.startsWith("DISPLAY_WATERMARK|")) return null
        val out = mutableMapOf<Int, Boolean>()
        for (part in message.removePrefix("DISPLAY_WATERMARK|").split("|")) {
            if (part == "END" || part.isBlank()) continue
            val head = part.substringBefore(",")
            val n = head.substringBefore("=").trim().toIntOrNull() ?: continue
            out[n] = head.substringAfter("=", "").trim() == "1"
        }
        return out
    }

    /**
     * "DISPLAY_MODE|1=child,device=...,auto=0,independent=0|2=mirror,...|END"
     *
     * The PC's own answer to which tier each display is on, which the phone
     * used to infer from GET_CHILDREN membership. That inference cannot see
     * the difference between a mirror and a display holding its own preset in
     * the parent process, because neither runs a child.
     */
    fun parseDisplayModes(message: String): Map<Int, DisplayModeState>? {
        if (!message.startsWith("DISPLAY_MODE|")) return null
        val out = mutableMapOf<Int, DisplayModeState>()
        for (part in message.removePrefix("DISPLAY_MODE|").split("|")) {
            if (part == "END" || part.isBlank()) continue
            val fields = part.split(",")
            val head = fields.firstOrNull() ?: continue
            val n = head.substringBefore("=").trim().toIntOrNull() ?: continue
            val mode = DisplayMode.parse(head.substringAfter("=", "")) ?: continue
            out[n] = DisplayModeState(
                mode = mode,
                auto = fields.findValue("auto") == "1",
                independent = fields.findValue("independent") == "1",
            )
        }
        return out
    }

    fun parseMirrors(message: String): MirrorState? {
        if (!message.startsWith("MIRRORS|")) return null
        val sections = message.removePrefix("MIRRORS|").split("|")
        var active = false
        var independentDefault = false
        var alwaysOnTop = false
        var watermark = false
        var renderDisplay = ""
        var renderOpacity = 1f
        var renderFs = false
        var renderClickThru = false
        val monitors = mutableListOf<DisplayInfo>()

        for (section in sections) {
            when {
                section.startsWith("active=") -> {
                    active = section.removePrefix("active=") != "0"
                }
                section.startsWith("independent=") -> {
                    independentDefault = section.removePrefix("independent=") == "1"
                }
                section.startsWith("aot=") -> {
                    alwaysOnTop = section.removePrefix("aot=") == "1"
                }
                // The MIRROR watermark -- every mirror at once. The main
                // window's is a different flag entirely, read by GET_WATERMARK.
                section.startsWith("watermark=") -> {
                    watermark = section.removePrefix("watermark=") == "1"
                }
                section.startsWith("render_on=") -> {
                    val parts = parseCommaSeparated(section.removePrefix("render_on="))
                    renderDisplay = parts.firstOrNull() ?: ""
                    renderOpacity = parts.findValue("opacity")?.toFloatOrNull() ?: 1f
                    renderFs = parts.findValue("fs") == "1"
                    renderClickThru = parts.findValue("clickthru") == "1"
                }
                section.startsWith("mon") -> {
                    val idx = section.substringBefore("=").removePrefix("mon").toIntOrNull() ?: continue
                    val parts = parseCommaSeparated(section.substringAfter("="))
                    val deviceName = parts.firstOrNull() ?: ""
                    monitors.add(DisplayInfo(
                        monIndex = idx,
                        deviceName = deviceName,
                        // Never idx: mon0 is DISPLAY1 on a normal desktop, and every
                        // command addresses the display by the number in its name.
                        deviceNumber = DisplayInfo.deviceNumberOf(deviceName),
                        enabled = parts.findValue("enabled") == "1",
                        opacity = parts.findValue("opacity")?.toIntOrNull() ?: 100,
                        clickThrough = parts.findValue("clickthru") == "1",
                        independent = parts.findValue("independent") == "1",
                        skipped = parts.findValue("skipped") == "1",
                        portrait = parts.findValue("portrait") == "1",
                        displayRect = parseRect(parts.findValue("display") ?: ""),
                        // These only appear when the mirror has a monitorState;
                        // an unbuilt mirror reports "state=none" and nothing else.
                        visible = parts.findValue("visible") == "1",
                        ready = parts.findValue("ready") == "1",
                        path = parts.findValue("path") ?: "none",
                    ))
                }
            }
        }

        return MirrorState(
            active = active,
            independentDefault = independentDefault,
            alwaysOnTop = alwaysOnTop,
            renderDisplay = renderDisplay,
            renderOpacity = renderOpacity,
            renderFullscreen = renderFs,
            renderClickThrough = renderClickThru,
            mirrorWatermark = watermark,
            monitors = monitors,
        )
    }

    /**
     * One "PRESET_ROW|hash=..|name=..|rating=..|..." line from BROWSE_PRESETS.
     *
     * Split on the FIRST '=' per field only: a path routinely contains '=' in a
     * folder name, and names contain almost anything.
     */
    fun parsePresetRow(message: String): PresetRow? {
        if (!message.startsWith("PRESET_ROW|")) return null
        val f = parseKeyValue(message.removePrefix("PRESET_ROW|"))
        val name = f["name"] ?: return null
        return PresetRow(
            hash = f["hash"].orEmpty(),
            name = name,
            rating = f["rating"]?.toIntOrNull() ?: 0,
            flags = splitList(f["flags"]),
            tags = splitList(f["tags"]),
            folder = f["folder"].orEmpty(),
            useCount = f["used"]?.toIntOrNull() ?: 0,
            secondsShown = f["seconds"]?.toIntOrNull() ?: 0,
            lastUsed = f["lastUsed"].orEmpty(),
            missing = f["missing"] == "1",
            path = f["path"].orEmpty(),
        )
    }

    /**
     * "PRESET_CYCLE=<order>,<lock>,<interval>,<randomness>", e.g. 0,0,0.000,10.000
     *
     * Positional rather than key=value, unlike most replies, so the field order
     * is the contract: order, lock, interval, randomness.
     */
    fun parsePresetCycle(message: String): PresetCycle? {
        if (!message.startsWith("PRESET_CYCLE=")) return null
        val parts = message.removePrefix("PRESET_CYCLE=").split(",")
        if (parts.size < 4) return null
        fun num(i: Int) = parts[i].trim().replace(',', '.').toFloatOrNull() ?: 0f
        return PresetCycle(
            sequential = num(0) != 0f,
            locked = num(1) != 0f,
            intervalSeconds = num(2),
            randomnessSeconds = num(3),
        )
    }

    /** "PRESET_ROWS_END|total=..|offset=..|count=..|backfilled=.." */
    fun parsePresetRowsEnd(message: String): Triple<Int, Int, Int>? {
        if (!message.startsWith("PRESET_ROWS_END")) return null
        val f = parseKeyValue(message.removePrefix("PRESET_ROWS_END").removePrefix("|"))
        return Triple(
            f["total"]?.toIntOrNull() ?: 0,
            f["offset"]?.toIntOrNull() ?: 0,
            f["count"]?.toIntOrNull() ?: 0,
        )
    }

    /**
     * "FOLDERS|name=count|..." or "TAGS|name=count|...".
     *
     * Folder counts sum to MORE than the number of presets, deliberately: a
     * preset that exists in two places is counted in both. They are not a
     * partition, and must not be summed to infer a total.
     */
    fun parseGroups(message: String, prefix: String): List<PresetGroup>? {
        if (!message.startsWith(prefix)) return null
        val body = message.removePrefix(prefix).removePrefix("|")
        if (body.isBlank()) return emptyList()
        return body.split("|").mapNotNull { part ->
            val eq = part.lastIndexOf('=')            // a folder name may contain '='
            if (eq <= 0) return@mapNotNull null
            val count = part.substring(eq + 1).toIntOrNull() ?: return@mapNotNull null
            PresetGroup(part.substring(0, eq), count)
        }
    }

    private fun splitList(raw: String?): Set<String> =
        raw?.split(";")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    /**
     * One "CHILD|display=..|pid=..|preset=..|interval=..|order=..|state=.." line.
     *
     * GET_CHILDREN emits one per display in child mode and then CHILDREN_END, so
     * a machine with no child displays replies with the terminator alone.
     */
    fun parseChild(message: String): ChildInstance? {
        if (!message.startsWith("CHILD|")) return null
        val fields = parseKeyValue(message.removePrefix("CHILD|"))
        val deviceName = fields["display"] ?: return null
        return ChildInstance(
            deviceName = deviceName,
            deviceNumber = DisplayInfo.deviceNumberOf(deviceName),
            pid = fields["pid"]?.toLongOrNull() ?: 0L,
            preset = fields["preset"].orEmpty(),
            // swprintf_s writes %.3f in the C locale, but tolerate a comma anyway
            // rather than silently reading a cycle interval as zero.
            interval = fields["interval"]?.replace(',', '.')?.toFloatOrNull() ?: 0f,
            intervalRaw = fields["interval_raw"]?.replace(',', '.')?.toFloatOrNull() ?: 0f,
            sequentialOrder = fields["order"] == "1",
            state = fields["state"] ?: "absent",
        )
    }

    private fun parseKeyValue(s: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (part in s.split("|")) {
            val eq = part.indexOf('=')
            if (eq > 0) {
                map[part.substring(0, eq)] = part.substring(eq + 1)
            }
        }
        return map
    }

    /**
     * Smart comma splitter that respects parenthesized groups.
     * e.g., "\\.\DISPLAY1,renderwin=(0,0)-(1920,1080) 1920x1080,opacity=0.95"
     * splits to: ["\\.\DISPLAY1", "renderwin=(0,0)-(1920,1080) 1920x1080", "opacity=0.95"]
     */
    private fun parseCommaSeparated(s: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var start = 0
        for (i in s.indices) {
            when (s[i]) {
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 0) {
                    result.add(s.substring(start, i))
                    start = i + 1
                }
            }
        }
        result.add(s.substring(start))
        return result
    }

    private fun List<String>.findValue(key: String): String? {
        return firstOrNull { it.startsWith("$key=") }?.substringAfter("=")
    }

    private fun parseRect(s: String): DisplayInfo.Rect {
        val match = Regex("""\((-?\d+),(-?\d+)\)-\((-?\d+),(-?\d+)\)\s+(\d+)x(\d+)""").find(s)
        return if (match != null) {
            val (x, y, _, _, w, h) = match.destructured
            DisplayInfo.Rect(x.toInt(), y.toInt(), w.toInt(), h.toInt())
        } else {
            DisplayInfo.Rect(0, 0, 0, 0)
        }
    }

    // -- Audio mixer -----------------------------------------------------------

    private fun pctOf(raw: String?): Int {
        val v = raw?.replace(',', '.')?.toFloatOrNull() ?: 0f
        return (v * 100f).toInt().coerceIn(0, 100)
    }

    fun parseMixerFader(message: String): MixerFader? {
        if (!message.startsWith("MIXER_FADER|")) return null
        val f = parseKeyValue(message.removePrefix("MIXER_FADER|"))
        val ch = f["ch"] ?: return null
        val id = f["id"] ?: return null
        return MixerFader(
            channelId = ch,
            channelName = f["chname"].orEmpty(),
            provider = f["provider"].orEmpty(),
            faderId = id,
            label = f["label"].orEmpty(),
            volumePercent = pctOf(f["vol"]),
            muted = f["mute"] == "1",
            // Absent means false: an older PC omits the field entirely, and
            // defaulting it true would hide every fader on that build.
            health = f["health"].orEmpty(),
            virtual = f["virtual"] == "1",
            // Absent means true, for the same reason virtual absent means
            // false: an older PC omits it, and guessing the restrictive way
            // would remove a working control everywhere.
            canMute = f["canMute"] != "0",
            windowsName = f["chwindowsName"].orEmpty(),
            // -1 is the PC saying "no battery here", and an older one omits the
            // field entirely. Neither is 0%.
            batteryPercent = f["battery"]?.toIntOrNull()?.takeIf { it >= 0 },
            shortName = f["short"].orEmpty(),
            hidden = f["hidden"] == "1",
            // Comma-separated and 1-based, empty when it belongs to none.
            groups = f["groups"].orEmpty()
                .split(",").mapNotNull { it.trim().toIntOrNull() }.toSet(),
        )
    }

    fun parseMixerDevice(message: String): MixerDevice? {
        if (!message.startsWith("MIXER_DEVICE|")) return null
        val f = parseKeyValue(message.removePrefix("MIXER_DEVICE|"))
        val id = f["id"] ?: return null
        val batt = f["battery"]?.toIntOrNull() ?: -1
        return MixerDevice(
            id = id,
            name = f["name"].orEmpty(),
            windowsName = f["windowsName"].orEmpty(),
            flow = f["flow"].orEmpty(),
            active = f["active"] == "1",
            displayAudio = f["display"] == "1",
            handsfree = f["handsfree"] == "1",
            lastSeen = f["seen"].orEmpty(),
            batteryPercent = if (batt < 0) null else batt,
        )
    }

    /** "IDLE_ACTIVE=<0|1>" -- whether the idle action is on screen right now. */
    fun parseIdleActive(message: String): Boolean? {
        if (!message.startsWith("IDLE_ACTIVE=")) return null
        return message.removePrefix("IDLE_ACTIVE=").trim() == "1"
    }

    /**
     * "ALL_FULLSCREEN=<0|1>|primary=|mirrors_active=|children_up=|children_total="
     *
     * The counts are kept, not just the verdict: "nothing happened" and
     * "everything was already up" look identical from a phone otherwise.
     */
    fun parseAllFullscreen(message: String): AllFullscreen? {
        if (!message.startsWith("ALL_FULLSCREEN=")) return null
        val f = parseKeyValue(message)
        return AllFullscreen(
            all = f["ALL_FULLSCREEN"] == "1",
            primary = f["primary"] == "1",
            mirrorsActive = f["mirrors_active"] == "1",
            childrenUp = f["children_up"]?.toIntOrNull() ?: 0,
            childrenTotal = f["children_total"]?.toIntOrNull() ?: 0,
            known = true,
        )
    }

    /** "DISPLAY_PROFILE|file=<leaf>|name=<name>" — one per saved profile. */
    fun parseDisplayProfile(message: String): DisplayProfile? {
        if (!message.startsWith("DISPLAY_PROFILE|")) return null
        val f = parseKeyValue(message.removePrefix("DISPLAY_PROFILE|"))
        val file = f["file"].orEmpty()
        if (file.isEmpty()) return null
        return DisplayProfile(file = file, name = f["name"].orEmpty().ifEmpty { file })
    }

    /**
     * "DISPLAY_PROFILE_STARTUP|file=<leaf>|enabled=<0|1>"
     *
     * Both halves always, because a chosen profile with loading switched off
     * is the case a client most needs to be able to explain.
     */
    fun parseDisplayProfileStartup(message: String): DisplayProfileStartup? {
        if (!message.startsWith("DISPLAY_PROFILE_STARTUP|")) return null
        val f = parseKeyValue(message.removePrefix("DISPLAY_PROFILE_STARTUP|"))
        return DisplayProfileStartup(
            file = f["file"].orEmpty(),
            enabled = f["enabled"] == "1",
        )
    }

    /**
     * "VERSION=<v>|build=...|protocol=<n>|...|features=<a,b,c>" — the reply to
     * GET_VERSION.
     *
     * Requires the record to be a `|`-separated line, which is what tells it
     * apart from the unrelated `VERSION=<int>` script command that sets the
     * visualizer's version override.
     */
    fun parseIdentity(message: String): ServerIdentity? {
        if (!message.startsWith("VERSION=") || "|" !in message) return null
        val f = parseKeyValue(message)
        val version = f["VERSION"] ?: return null
        return ServerIdentity(
            version = version,
            protocol = f["protocol"]?.toIntOrNull() ?: 0,
            // Absent on a build that declares nothing, which is an answer and
            // not a failure.
            features = f["features"].orEmpty()
                .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        )
    }

    /**
     * "MIXER_VIEW|pos=<n>|order=<n>|hidden=<0|1>|pinned=<0|1>|key=<ch>|<fader>"
     *
     * One row of the list the PC DRAWS. `key=` is the last field, and the key
     * contains the separator, so it is everything after "|key=" -- splitting
     * the line on '|' truncates every key at its channel, silently.
     */
    fun parseMixerViewRow(message: String): MixerViewRow? {
        if (!message.startsWith("MIXER_VIEW|")) return null
        val at = message.indexOf("|key=")
        if (at < 0) return null
        val key = message.substring(at + 5)
        if (key.isBlank()) return null
        val head = message.substring(0, at)
        val f = parseKeyValue(head.removePrefix("MIXER_VIEW|"))
        return MixerViewRow(
            key = key,
            order = f["order"]?.toIntOrNull() ?: -1,
            pos = f["pos"]?.toIntOrNull() ?: -1,
            hidden = f["hidden"] == "1",
            userHidden = f["userHidden"] == "1",
            pinned = f["pinned"] == "1",
        )
    }

    /**
     * "MIXER_VIEW_END|count=|shown=|hidden=|rev=|sortUnmutedFirst=|
     *  pinFailoverDevices=|showVirtualEndpoints="
     *
     * Returned with no rows: the caller fills them from the records it
     * collected. Nothing is believed until this arrives, because a PC too old
     * to know MIXER_VIEW answers it with nothing at all and the stored order
     * has to stay in use there.
     */
    fun parseMixerViewEnd(message: String): MixerView? {
        if (!message.startsWith("MIXER_VIEW_END")) return null
        val f = parseKeyValue(message.removePrefix("MIXER_VIEW_END").removePrefix("|"))
        return MixerView(
            rev = f["rev"].orEmpty(),
            sortUnmutedFirst = f["sortUnmutedFirst"] == "1",
            pinFailoverDevices = f["pinFailoverDevices"] == "1",
            showVirtualEndpoints = f["showVirtualEndpoints"] == "1",
            showHiddenFaders = f["showHiddenFaders"] == "1",
        )
    }

    /**
     * The `rev=` of any mixer record carrying one -- the MIXER_VIEW_CHANGED
     * push, a MIXER_ORDER_END or MIXER_ORDER_REV reply, or a refusal.
     *
     * Read from BEFORE "|key=" when there is one: a key is the rest of the
     * line and would otherwise swallow the field.
     */
    fun parseMixerRev(message: String): String? {
        val at = message.indexOf("|key=")
        val head = if (at < 0) message else message.substring(0, at)
        val i = head.indexOf("|rev=")
        if (i < 0) return null
        return head.substring(i + 5).substringBefore('|').ifEmpty { null }
    }

    /**
     * A move refused because the arrangement moved underneath it. The PC
     * reorders on its own -- the pinned failover device swaps out every few
     * hours -- so this is a normal event, not a fault.
     */
    fun isMixerStale(message: String) =
        message.startsWith("MIXER_ERR|") && message.contains("reason=stale")

    /**
     * "MIXER_ORDER|pos=<n>|key=<channel>|<faderId>"
     *
     * The key itself contains a '|', so it is everything after "key=" rather
     * than one field of the split.
     */
    fun parseMixerOrder(message: String): Pair<Int, String>? {
        if (!message.startsWith("MIXER_ORDER|")) return null
        val at = message.indexOf("|key=")
        if (at < 0) return null
        val key = message.substring(at + 5)
        if (key.isBlank()) return null                 // the pos=-1 empty sentinel
        val pos = Regex("""pos=(-?\d+)""").find(message)
            ?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (pos < 0) return null
        return pos to key
    }
}
