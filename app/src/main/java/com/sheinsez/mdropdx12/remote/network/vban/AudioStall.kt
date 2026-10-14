package com.sheinsez.mdropdx12.remote.network.vban

/**
 * Audio was asked for and has stopped arriving.
 *
 * THE SIGNAL THAT MATTERS, and a different one from whether the mixer is
 * answering at all. A peer can keep getting ping replies and state records
 * while its audio has stopped dead -- the emission grant lost with an expired
 * peer, the sender's stream re-armed for somebody else, a port that is now
 * answering control and not media. Reachability reports everything as fine in
 * every one of those, because by its measure it is.
 *
 * So the test is on the thing that was actually wanted. Audio arrives about
 * 188 times a second; two seconds of none, when some was asked for, is not a
 * hiccup.
 *
 * The response is graduated, because the two causes have very different costs.
 * Re-asking for audio is one small record and fixes a lost grant without
 * disturbing a working control channel. Re-authenticating is the bigger
 * hammer, and only earns its keep once the cheap answer has been tried and
 * the silence has outlasted the server's own peer expiry.
 *
 * Pure, and takes its clock as an argument, so the thresholds can be argued
 * about in a test rather than waited out on the rig.
 */
class AudioStall(
    /** Re-ask for audio after this much silence. */
    private val nudgeAfterMs: Long = 2_000L,
    /**
     * Re-authenticate after this much.
     *
     * Past the server's 10 s peer expiry would be too late to be useful and
     * well short of it would re-auth over a stall the nudge was about to fix,
     * so this sits between the nudge and the expiry.
     */
    private val reauthAfterMs: Long = 6_000L,
) {
    enum class Action {
        None,

        /** Re-send the audio request. Cheap, idempotent, fixes a lost grant. */
        Nudge,

        /** Re-authenticate and replay everything this session asked for. */
        Reauth,
    }

    private var lastHeard: Long? = null
    private var nudged = false
    private var reauthed = false

    /** Milliseconds since audio last arrived, or null if none ever has. */
    fun silentForMs(nowMs: Long): Long? = lastHeard?.let { nowMs - it }

    fun reset() {
        lastHeard = null
        nudged = false
        reauthed = false
    }

    /**
     * Audio has just been asked for; start counting from here.
     *
     * Needed because "no audio yet" and "audio stopped" are the same thing to
     * a stopwatch with nothing on it. Without a starting mark a session that
     * had never received a packet would either accuse immediately or never.
     *
     * UNCONDITIONAL, which matters on the second call. This is reached when a
     * session re-establishes itself, and the mark still standing then is from
     * BEFORE the outage -- so leaving it in place would measure the whole
     * outage against a link that has just come back and escalate again on the
     * very next tick, for ever.
     */
    fun armed(nowMs: Long) {
        lastHeard = nowMs
        nudged = false
        reauthed = false
    }

    /** One audio packet arrived. */
    fun onAudio(nowMs: Long) {
        lastHeard = nowMs
        nudged = false
        reauthed = false
    }

    /**
     * What to do about the silence, if anything.
     *
     * Each escalation fires ONCE per stall. Re-sending the nudge every tick
     * would turn one stall into a stream of records, and re-authenticating
     * every tick against a PC that is simply off is the retry storm this has
     * to avoid.
     */
    fun check(nowMs: Long, wantAudio: Boolean): Action {
        if (!wantAudio) return Action.None
        val since = lastHeard ?: return Action.None
        val gap = nowMs - since
        // A clock that went backwards -- an NTP step, a suspend -- is not
        // evidence of a stall. Start again from here rather than reading it as
        // a negative gap that can never escalate.
        if (gap < 0) {
            lastHeard = nowMs
            return Action.None
        }
        if (gap >= reauthAfterMs && !reauthed) {
            reauthed = true
            return Action.Reauth
        }
        if (gap >= nudgeAfterMs && !nudged) {
            nudged = true
            return Action.Nudge
        }
        return Action.None
    }
}
