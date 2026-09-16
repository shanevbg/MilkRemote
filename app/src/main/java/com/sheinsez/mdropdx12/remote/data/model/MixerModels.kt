package com.sheinsez.mdropdx12.remote.data.model

/** `<channel>|<faderId>` — the identity MIXER_ORDER, MIXER_SET and the PC all use. */
fun faderKey(channelId: String, faderId: String) = "$channelId|$faderId"

data class MixerFader(
    val channelId: String,
    val channelName: String,
    val provider: String,
    val faderId: String,
    val label: String,
    val volumePercent: Int,
    val muted: Boolean,
    val health: String,
    /**
     * A software endpoint another program owns. Sonar's eight sit at 1.000 and
     * ignore writes; the PC hides them and says a remote should too.
     */
    val virtual: Boolean,
    /**
     * False means this fader has a working volume but must NOT be muted. The
     * Sonar master is the case: its mute writes every render channel's mute and
     * loses their states coming back, so it is named on the wire rather than
     * discovered -- a client cannot find this out by trying, because the write
     * reports success.
     *
     * Absent means true: an older PC omits the field, and defaulting it false
     * would strip the mute button off every fader on that build.
     */
    val canMute: Boolean = true,
    /**
     * What Windows calls this channel — present ONLY when a short name has been
     * given on the PC, so its absence is itself the answer to "has this been
     * renamed". Carried because the record carries it; the row shows the short
     * name, which is the whole point of having chosen one.
     */
    val windowsName: String = "",
    /**
     * 0..100, or null. The PC reads it live from the device watcher rather than
     * from the cached channel, because MIXER_BATTERY re-reads batteries without
     * re-polling any provider and a cached figure would not move when it ran.
     *
     * null covers three ordinary cases, none of them an error: a Sonar channel
     * has no device behind it, plenty of devices report nothing, and an
     * inactive endpoint is deliberately unreported rather than frozen at the
     * reading it had on the way out. Never conflate it with 0.
     */
    val batteryPercent: Int? = null,
    /**
     * The user's own short name for this fader, chosen on either surface.
     * Empty means none, and the channel and label are then what to show.
     */
    val shortName: String = "",
    /**
     * Hidden by the user: a view preference only. It keeps its level, its
     * place in the order and every write verb -- so this is about a list that
     * is too long to find anything in, not about a fader being unavailable.
     */
    val hidden: Boolean = false,
    /**
     * The 1-based volume-hotkey groups this fader belongs to on the PC.
     *
     * Worth showing: these are the faders the PC's own volume keys move, and
     * a phone that does not say so leaves the user guessing why one row moves
     * when a key is pressed at the desk.
     */
    val groups: Set<Int> = emptySet(),
) {
    val key get() = faderKey(channelId, faderId)
}

data class MixerDevice(
    val id: String,
    val name: String,
    val windowsName: String,
    val flow: String,
    val active: Boolean,
    /** Monitor or HDMI audio — noise on a phone. */
    val displayAudio: Boolean,
    /** The Bluetooth hands-free endpoint: mic on, and it sounds terrible. */
    val handsfree: Boolean,
    val lastSeen: String,
    /** null when the device does not report one; never conflate with 0. */
    val batteryPercent: Int?,
)

/**
 * One row of the list the PC's Mixer tab actually DRAWS.
 *
 * The PC stores an order and then applies three view rules on top of it before
 * anything reaches the screen -- unmuted-first, failover pinning, and hiding
 * the endpoints a provider owns. MIXER_ORDER is the stored list; this is the
 * drawn one, and they disagree.
 */
data class MixerViewRow(
    val key: String,
    /**
     * This row's index in MIXER_ORDER space -- the coordinate a move is
     * expressed in. Render the view, move in the order: they are different
     * coordinate systems and this is the bridge between them.
     */
    val order: Int,
    /** The drawn row, or -1 when the PC is hiding it. */
    val pos: Int,
    /** Not drawn, for either reason below. */
    val hidden: Boolean,
    /**
     * Hidden because the USER hid it, as opposed to a provider-owned endpoint
     * the PC hides by default. Only this kind is the client's to reveal: the
     * other is the PC's own view flag.
     */
    val userHidden: Boolean = false,
    /**
     * Lifted to the top because it is a failover target. Reported per row
     * because a client cannot work it out for itself: it needs every route's
     * full allowlist and MIXER_ROUTE carries only the route's current device.
     */
    val pinned: Boolean,
)

/**
 * A whole MIXER_VIEW reply: the drawn rows, the arrangement token, and the
 * three view flags that produced them.
 */
data class MixerView(
    val rows: List<MixerViewRow> = emptyList(),
    /**
     * The arrangement token. Passed back with a move so the PC can refuse one
     * made against a list that has since changed -- which happens on its own
     * here, because the pinned failover device swaps out every few hours.
     */
    val rev: String = "",
    val sortUnmutedFirst: Boolean = false,
    val pinFailoverDevices: Boolean = false,
    val showVirtualEndpoints: Boolean = false,
    val showHiddenFaders: Boolean = false,
) {
    val byKey: Map<String, MixerViewRow> = rows.associateBy { it.key }
}
