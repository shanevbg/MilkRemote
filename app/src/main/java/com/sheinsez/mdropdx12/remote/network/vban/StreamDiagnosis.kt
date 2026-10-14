package com.sheinsez.mdropdx12.remote.network.vban

/**
 * Why audio is not arriving, answered by the MIXER rather than guessed at.
 *
 * The timeout this replaces could only ever say "no audio for N seconds", and
 * every cause of that needs a different response: an expired peer needs
 * re-authenticating, a dropped emission grant needs one small record, a
 * network path that has gone needs neither because neither will fix it, and a
 * PC waiting for someone to click Approve needs to be left alone. Guessing
 * means doing the expensive thing for the cheap problem and the useless thing
 * for the real one.
 *
 * The mixer already knows all of it. `MDXM_VBANPEERS` is its own client table:
 * a phone that has been expired is simply ABSENT from it, and one whose audio
 * grant was dropped says so in a field. So the phone asks.
 */
enum class StreamFault {
    /** The mixer is serving us and audio should be arriving. Look elsewhere. */
    None,

    /**
     * We are not in the mixer's client table at all.
     *
     * The peer expired -- ten seconds without a ping, which a phone in Doze
     * crosses easily -- or the mixer restarted and never knew us. Either way
     * the registration is gone and only a fresh authentication brings it back.
     */
    NotRegistered,

    /**
     * We are there, authorized, and the mixer is not sending us audio.
     *
     * The grant is per-peer and died with the old registration. One
     * `MDXM_VBAN|audio=1` restores it, which is far cheaper than
     * re-authenticating and does not disturb anything else.
     */
    AudioGrantLost,

    /** Someone has to click Approve on the PC. Waiting is the only correct move. */
    AwaitingApproval,

    /** The PC said no. Terminal; retrying turns a brief lockout into a long one. */
    Refused,

    /**
     * The mixer says it is sending to us and nothing is arriving.
     *
     * Nothing local fixes this -- the grant is in place and the registration
     * is live -- so the packets are being lost between here and there. A fresh
     * registration is still worth one attempt, because it re-resolves the
     * address and port the mixer is sending to, and a phone that changed
     * network while the session was up is exactly this shape.
     */
    PathBroken,
}

object StreamDiagnosis {

    /**
     * CALL ONLY WHEN AUDIO IS NOT ARRIVING. That is the precondition the
     * whole table rests on.
     *
     * The same row means different things depending on it: an authorized peer
     * with its grant in place is perfectly healthy when audio is flowing, and
     * is [StreamFault.PathBroken] when it is not. This function cannot see
     * which, so the caller owns that decision -- it asks the question only
     * because something is already wrong.
     *
     * [mine] is this phone's own row, found by device id, or null if the mixer
     * has no row for it.
     */
    fun diagnose(mine: VbanPeer?, wantAudio: Boolean): StreamFault {
        if (mine == null) return StreamFault.NotRegistered
        // Checked before `authed`, because a denied peer can still be listed
        // and retrying it is the one thing that makes the situation worse.
        if (mine.denied || mine.authState == "denied") return StreamFault.Refused
        if (mine.awaitingApproval) return StreamFault.AwaitingApproval
        // Present but not authorized and not pending: the row is a shell from
        // a ping, with no authentication behind it.
        if (!mine.authed && mine.authState != "authorized") return StreamFault.NotRegistered
        if (wantAudio && !mine.audioOn) return StreamFault.AudioGrantLost
        if (!wantAudio) return StreamFault.None
        return StreamFault.PathBroken
    }

    /** Find this phone among the mixer's rows. */
    fun mine(rows: List<VbanPeer>, deviceId: String): VbanPeer? =
        rows.firstOrNull { it.deviceId == deviceId && it.deviceId.isNotEmpty() }

    /**
     * Whether the phone should re-authenticate for this fault.
     *
     * Deliberately NOT true for [StreamFault.AwaitingApproval] or
     * [StreamFault.Refused]. Re-sending AUTH while a prompt is on screen is
     * harmless but pointless, and re-sending it after a refusal is actively
     * harmful: the mixer counts strikes per address, so a client that keeps
     * trying turns a sixty-second lockout into a permanent one.
     */
    fun needsReauth(fault: StreamFault): Boolean = when (fault) {
        StreamFault.NotRegistered, StreamFault.PathBroken -> true
        StreamFault.None,
        StreamFault.AudioGrantLost,
        StreamFault.AwaitingApproval,
        StreamFault.Refused,
        -> false
    }

    /** What to tell someone holding the phone, or null if nothing is wrong. */
    fun explain(fault: StreamFault): String? = when (fault) {
        StreamFault.None -> null
        StreamFault.NotRegistered -> "Reconnecting to the mixer…"
        StreamFault.AudioGrantLost -> "Asking the PC for audio again…"
        StreamFault.AwaitingApproval -> "Waiting for approval on the PC"
        StreamFault.Refused -> "The PC refused this phone."
        StreamFault.PathBroken ->
            "The PC says it is sending, but nothing is arriving. Check the network."
    }
}
