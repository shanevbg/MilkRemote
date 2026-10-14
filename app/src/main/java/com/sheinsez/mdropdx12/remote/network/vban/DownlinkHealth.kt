package com.sheinsez.mdropdx12.remote.network.vban

/**
 * How much room the Wi-Fi downlink has left, in the only terms that predict
 * this stream dying.
 */
enum class Downlink {
    /** No reading. Not on Wi-Fi, too old an API, or the radio would not say. */
    Unknown,

    /** Room to spare. */
    Fine,

    /** Thinning. Audio is probably still clean; it is the warning before the fall. */
    Weak,

    /** Gone. At this rate the stream cannot land, whatever the signal bars say. */
    Collapsed,
}

/**
 * Watch the DOWNLINK rate, because that is the one that kills this stream.
 *
 * VBAN audio travels PC to phone. On 2026-10-10 a walk to the far end of the
 * house took the downlink from 272 Mbps to 36 while the UPLINK never moved
 * from 1134 -- so pings left fine, the session's ping loop kept ticking, the
 * association held, Android's connectivity check passed, and Android reported
 * the network as healthy throughout. Audio was dead for three and a half
 * minutes and nothing on the phone said why.
 *
 * Nothing Android surfaces measures sustained downlink throughput. The bars
 * are signal, and signal was -73 dBm: poor, but the sort of poor that still
 * reads as connected. The rate is the reading that matters and it is free.
 *
 * The rate also collapses BEFORE the buffer drains, which is the whole value:
 * a warning that arrives with the silence is an explanation, and one that
 * arrives before it is an instruction.
 *
 * Pure, so the thresholds are argued about here rather than tuned on the rig.
 */
class DownlinkHealth(
    /**
     * Below this, say something.
     *
     * NOT derived from the stream's bitrate. The audio is about 1.5 Mbps of
     * PCM, and reasoning from that would put the threshold near zero and
     * never fire -- the headline link rate is a negotiated modulation rate,
     * not throughput, and at the edge of range retries and airtime eat almost
     * all of it. The useful number comes from the measurements instead: 272
     * Mbps carried the stream perfectly, 136 carried it perfectly, 36 could
     * not carry it at all. A hundred sits in the gap with room on both sides.
     */
    private val weakBelowMbps: Int = 100,
    /**
     * Below this, the stream is not going to survive.
     *
     * Forty, just above the 36 Mbps measured while audio was dead. This is
     * the band where the advice stops being "it is getting thin" and becomes
     * "move closer", and no amount of buffering or re-locking helps.
     */
    private val collapsedBelowMbps: Int = 40,
    /**
     * Above this, stop saying it.
     *
     * Deliberately well clear of [weakBelowMbps]. Rate adaptation steps
     * between modulation levels constantly, several times a second on a busy
     * band, so a single threshold would flash a warning on and off at a
     * perfectly good distance. A hundred and fifty was measured as a healthy
     * working rate, so clearing there is honest rather than merely quiet.
     */
    private val clearAboveMbps: Int = 150,
    /**
     * Consecutive low readings before saying anything.
     *
     * Two, at the 2 s tick. One low sample is an ordinary dip -- the rate is
     * re-negotiated continuously and a single step down means nothing. Four
     * seconds of it is a position, not a blip.
     */
    private val tolerance: Int = 2,
) {
    private var lowTicks = 0
    private var current = Downlink.Unknown

    /** The last verdict, without taking a new reading. */
    val state: Downlink get() = current

    fun reset() {
        lowTicks = 0
        current = Downlink.Unknown
    }

    /**
     * One tick with a fresh reading.
     *
     * [rxLinkSpeedMbps] is negative when there is nothing to report -- not on
     * Wi-Fi, an API too old to ask, or the radio declining to answer. That is
     * Unknown rather than a fault: a phone on mobile data streaming this over
     * a VPN is unusual but not broken, and claiming its downlink had collapsed
     * would be a lie told confidently.
     */
    fun onTick(rxLinkSpeedMbps: Int): Downlink {
        if (rxLinkSpeedMbps < 0) {
            lowTicks = 0
            current = Downlink.Unknown
            return current
        }
        // Already warning: hold until it is clearly recovered, not merely
        // back above the trigger.
        val warning = current == Downlink.Weak || current == Downlink.Collapsed
        if (warning && rxLinkSpeedMbps < clearAboveMbps) {
            lowTicks = tolerance
            current =
                if (rxLinkSpeedMbps < collapsedBelowMbps) Downlink.Collapsed else Downlink.Weak
            return current
        }
        if (rxLinkSpeedMbps >= weakBelowMbps) {
            lowTicks = 0
            current = Downlink.Fine
            return current
        }
        lowTicks++
        if (lowTicks < tolerance) {
            // Not yet convinced. Report what was last believed rather than
            // inventing a state on one sample.
            if (current == Downlink.Unknown) current = Downlink.Fine
            return current
        }
        current =
            if (rxLinkSpeedMbps < collapsedBelowMbps) Downlink.Collapsed else Downlink.Weak
        return current
    }
}
