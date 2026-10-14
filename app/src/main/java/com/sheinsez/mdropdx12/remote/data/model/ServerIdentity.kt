package com.sheinsez.mdropdx12.remote.data.model

/**
 * What the PC says it is, from GET_VERSION.
 *
 * The point of asking is `features`: the app degrades per FEATURE rather than
 * per build, with an independent fallback for the drawn fader order, the
 * arrangement token, the battery field and the push, and it will happily use
 * three of the four. A single version number would force all-or-nothing.
 */
data class ServerIdentity(
    val version: String = "",
    /**
     * Moves on any change a client can depend on, additive ones included. It
     * stood at 1 for the whole of the PC's v3 work under an older rule that
     * moved it only on breaking changes, so anything gating on `protocol >= 2`
     * should treat that as a floor and read `features` for the detail.
     */
    val protocol: Int = 0,
    /**
     * Append-only on the PC: a name, once shipped, keeps its meaning forever.
     * Absence means NOT DECLARED, which is not the same as not present — only
     * capabilities a client is expected to gate on are listed.
     */
    val features: Set<String> = emptySet(),
) {
    /** False until the PC has answered. Claim nothing about a PC that has not. */
    val known get() = version.isNotEmpty()

    operator fun contains(feature: String) = feature in features
}

/**
 * The capability names this app gates on, as the PC declares them.
 *
 * Spelled out rather than inlined because the PC's list is append-only and
 * clients in the wild are testing for these exact strings.
 */
object PcFeature {
    /** MIXER_VIEW and its terminator: the list the PC's mixer actually draws. */
    const val MIXER_VIEW = "mixerview"
    /** MIXER_ORDER_REV, the rev= guard on a move, and MIXER_ORDER_SET. */
    const val MIXER_REV = "mixerrev"
    /** MIXER_VIEW_CHANGED, pushed when the arrangement moves on its own. */
    const val MIXER_PUSH = "mixerpush"
    /** battery= on MIXER_FADER. */
    const val MIXER_BATTERY = "mixerbattery"
    /** chwindowsName= on MIXER_FADER. */
    const val MIXER_NAMES = "mixernames"

    /** DISPLAY_PROFILE_LIST/_SAVE/_LOAD/_DELETE/_STARTUP, addressed by name. */
    const val DISPLAY_PROFILES = "displayprofiles"

    /** SET_ALL_FULLSCREEN / GET_ALL_FULLSCREEN: the walk-away control. */
    const val ALL_FULLSCREEN = "allfullscreen"

    /** SET_IDLE_ACTIVE / GET_IDLE_ACTIVE: run the idle action on demand. */
    const val IDLE_ACTIVE = "idleactive"

    /** SET_MIRROR_WATERMARK: the absolute form, for every mirror at once. */
    const val MIRROR_WATERMARK = "mirrorwatermark"

    /**
     * LOWER_WINDOW alongside RAISE_WINDOW, so a display can be sent behind the
     * working windows as well as brought in front of them. Relayable, so it
     * reaches a child the way the raise does.
     */
    const val WINDOW_ZORDER = "windowzorder"

    /** SET_DISPLAY_WATERMARK / GET_DISPLAY_WATERMARK: one display at a time. */
    const val DISPLAY_WATERMARK = "displaywatermark"

    /**
     * The four tiers -- off, mirror, preset, child -- and GET_DISPLAY_MODE to
     * read which one a display is on.
     *
     * Also the gate for the addressed step verbs reaching a child. The fix
     * that gave DISPLAY_NEXT=/PREV= their child branch shipped in the same
     * merge as the tiers, so a PC advertising this has both; one that does not
     * needs Previous and Next relayed by hand.
     */
    const val DISPLAY_TIERS = "displaytiers"
}
