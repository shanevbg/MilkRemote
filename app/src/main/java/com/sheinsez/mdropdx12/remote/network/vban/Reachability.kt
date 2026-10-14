package com.sheinsez.mdropdx12.remote.network.vban

/**
 * Is the mixer still answering?
 *
 * NOTHING ELSE IN THE SESSION ASKS THIS. The auth state machine leaves
 * `Streaming` only when the user stops it or a fresh `MDXM_AUTHSTATE` arrives,
 * and a server that has gone away sends neither -- so a session whose PC was
 * closed, whose Wi-Fi dropped, or whose phone slept through a Doze window sits
 * at `Streaming` for ever, reporting that everything is fine.
 *
 * That is survivable for audio, which announces its own failure by going
 * quiet. It is not survivable for commands: the only symptom of an unreachable
 * mixer is buttons that do nothing, and a remote that silently stops working
 * is worse than one that says it has lost the PC.
 *
 * The data was already being collected. `VbanSession` stamps every PING0 it
 * sends and reads the stamp back to measure RTT; this counts the ones that
 * never came back. No extra traffic, no new verb.
 *
 * Pure, so the threshold is argued about here rather than tuned on the rig.
 */
class Reachability(
    /**
     * Unanswered pings before the mixer is called unreachable.
     *
     * Three, at a 2 s tick, so about six seconds. Deliberately shorter than
     * the server's own 10 s peer expiry: by the time the PC has forgotten this
     * phone, the phone should already have said so rather than learning it
     * from the next refused command.
     *
     * One is far too few -- a single dropped datagram on a busy AP is
     * ordinary, and UDP owes nobody a delivery. Two would fire on two
     * unlucky ticks in a row, which happens. Three unanswered in a row is
     * either gone or so degraded that saying so is right anyway.
     */
    private val tolerance: Int = 3,
) {
    private var outstanding = 0

    /** False only after [tolerance] consecutive pings went unanswered. */
    var reachable: Boolean = true
        private set

    fun reset() {
        outstanding = 0
        reachable = true
    }

    /** A PING0 went out. */
    fun onPingSent() {
        outstanding++
        if (outstanding >= tolerance) reachable = false
    }

    /**
     * Something arrived from the mixer. Returns true if it is reachable now.
     *
     * ANY packet counts -- audio, a text reply, a ping reply -- and that is
     * the whole point. Crediting only PING0 replies made this depend on one
     * probe surviving while a couple of hundred audio packets a second were
     * arriving to say the same thing, so three delayed replies condemned a
     * link that was plainly working.
     *
     * One packet clears the whole debt rather than decrementing it. Nothing is
     * individually matched: a late reply arriving after two timeouts still
     * proves the mixer is there, which is the only question being asked.
     */
    fun onHeard(): Boolean {
        outstanding = 0
        reachable = true
        return true
    }

    /** For a diagnostics line: how many are currently in the air unanswered. */
    val unanswered: Int get() = outstanding
}
