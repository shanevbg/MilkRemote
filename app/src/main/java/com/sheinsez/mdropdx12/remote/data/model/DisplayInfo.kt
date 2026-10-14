package com.sheinsez.mdropdx12.remote.data.model

/**
 * How a display is being driven. Mirrors the PC's SET_DISPLAY_MODE argument,
 * whose four tiers run cheapest first.
 *
 * Preset and Child are the same thing to look at -- this screen showing its own
 * preset rather than the primary's -- and differ only in where the rendering
 * happens. Preset draws it on the parent's render thread; Child spawns a
 * separate instance that owns the window. The screen therefore offers three
 * tiers and puts the Preset/Child choice on an "Isolated process" switch
 * underneath, which is the distinction in the terms it actually costs: a
 * process of its own survives a preset that wedges the parent, at the price of
 * a second instance's GPU and memory.
 */
enum class DisplayMode {
    Off,
    Mirror,

    /** Its own preset, drawn in the parent process. */
    Preset,

    /** Its own preset, in an instance of its own. */
    Child;

    /** Preset and Child both show this display a preset of its own. */
    val ownPreset get() = this == Preset || this == Child

    /** The tier the segmented control shows; isolation is a separate switch. */
    val tier get() = if (this == Child) Preset else this

    companion object {
        /** The three the user picks between. Child is reached via the switch. */
        val TIERS = listOf(Off, Mirror, Preset)

        fun parse(token: String): DisplayMode? = when (token.trim().lowercase()) {
            "off" -> Off
            "mirror" -> Mirror
            // The PC accepts "own" as a synonym for "preset" and may say either.
            "preset", "own" -> Preset
            "child" -> Child
            else -> null
        }
    }
}

/**
 * One display's entry in GET_DISPLAY_MODE.
 *
 * Authoritative where the old inference was not: a display in Preset mode runs
 * no child, so deducing the tier from GET_CHILDREN membership called it a
 * mirror.
 */
data class DisplayModeState(
    val mode: DisplayMode,
    /**
     * The cost gate promoted this display to its own process rather than the
     * user asking for it. Worth saying out loud, because the isolation switch
     * then reads as on without anybody having turned it on.
     */
    val auto: Boolean,
    /** Per-display independent render, as SET_MIRROR_INDEPENDENT sets it. */
    val independent: Boolean,
)

data class DisplayInfo(
    /** Enumeration order in DIAG_MIRRORS: mon0, mon1, mon2. NOT the command argument. */
    val monIndex: Int,
    /** e.g. "\\\\.\\DISPLAY2" */
    val deviceName: String,
    /**
     * The N in "\\.\DISPLAYN", and the only number the SET_MIRROR_* family and
     * SET_DISPLAY_MODE accept. It is NOT monIndex: on a three-monitor desktop
     * mon0 is DISPLAY1, so building a command from the enumeration index targets
     * the wrong monitor entirely.
     */
    val deviceNumber: Int,
    val enabled: Boolean,
    val opacity: Int,
    val clickThrough: Boolean,
    /** Per-display independent render — the in-process mirror sim, same preset. */
    val independent: Boolean,
    /** Mirror sits on the render window's own monitor, so it is not drawn. */
    val skipped: Boolean,
    val portrait: Boolean,
    val displayRect: Rect,
    val visible: Boolean,
    val ready: Boolean,
    /** none|orient|stretchMain|letterbox|copy|black|orientFail — why it looks how it looks. */
    val path: String,
) {
    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
        val right get() = x + w
        val bottom get() = y + h
    }

    companion object {
        private val DEVICE_NUMBER = Regex("""DISPLAY(\d+)""", RegexOption.IGNORE_CASE)

        /** "\\.\DISPLAY2" -> 2. Returns 0 when the name is not a DISPLAYn device. */
        fun deviceNumberOf(deviceName: String): Int =
            DEVICE_NUMBER.find(deviceName)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
}

/**
 * One per-display child instance, from GET_CHILDREN.
 *
 * The PC emits one CHILD| line per display whose config has bOwnProcess, then a
 * single CHILDREN_END. A display in mirror or off mode produces no line at all.
 */
data class ChildInstance(
    val deviceName: String,
    val deviceNumber: Int,
    val pid: Long,
    val preset: String,
    /** Effective seconds between automatic preset changes; 0 never cycles. */
    val interval: Float,
    /**
     * What this display is actually configured with, before inheritance: -1
     * means "use the parent's". The PC reports both because an effective 0 that
     * came from inheriting a parent which never cycles is a different thing to
     * explain than one the user set here.
     */
    val intervalRaw: Float,
    /** true = sequential, false = random. */
    val sequentialOrder: Boolean,
    /** absent | starting | ready | failed */
    val state: String,
) {
    val isRunning get() = state == "ready"

    /** True when this display has no interval of its own and follows the parent. */
    val inheritsInterval get() = intervalRaw < 0f
}

data class MirrorState(
    val active: Boolean,
    /** Global default for independent render, not a per-display value. */
    val independentDefault: Boolean,
    val alwaysOnTop: Boolean,
    /**
     * Watermark on every MIRROR at once, which is what the MIRRORS line's
     * `watermark=` reports. NOT the main window's, which is a separate flag
     * read by GET_WATERMARK.
     *
     * This is no longer the only per-display scope: SET_DISPLAY_WATERMARK
     * takes any display now and GET_DISPLAY_WATERMARK reads them all back.
     * This flag remains the all-mirrors one, and setting it does not move what
     * GET_DISPLAY_WATERMARK reports for an individual screen.
     */
    val mirrorWatermark: Boolean,
    /** Device name of the monitor hosting the render window — the primary. */
    val renderDisplay: String,
    val renderOpacity: Float,
    val renderFullscreen: Boolean,
    val renderClickThrough: Boolean,
    val monitors: List<DisplayInfo>,
) {
    val renderDisplayNumber: Int get() = DisplayInfo.deviceNumberOf(renderDisplay)

    /** The display currently hosting the render window, if it is in the list. */
    val primary: DisplayInfo? get() = monitors.firstOrNull { it.deviceName == renderDisplay }
}

/**
 * The main window's preset cycling, from GET_PRESET_CYCLE.
 *
 * This is the GLOBAL setting, not one display's. A child display whose own
 * interval is -1 inherits it, so changing this moves those displays too — which
 * is the behaviour, not a side effect, and the screen says so.
 *
 * Wire: PRESET_CYCLE=<order>,<lock>,<interval>,<randomness>
 */
data class PresetCycle(
    /** false = random, true = sequential. */
    val sequential: Boolean,
    val locked: Boolean,
    /** Seconds between automatic changes; 0 disables cycling entirely. */
    val intervalSeconds: Float,
    /** Extra seconds of randomness added on top of the interval. */
    val randomnessSeconds: Float,
) {
    val cycling get() = intervalSeconds >= 1f
}
