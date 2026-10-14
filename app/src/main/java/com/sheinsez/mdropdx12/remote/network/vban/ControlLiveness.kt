package com.sheinsez.mdropdx12.remote.network.vban

/**
 * Is the mixer still *answering us*, as opposed to merely being there?
 *
 * [Reachability] answers the first question and is credited by ANY packet --
 * audio, a text reply, a ping reply. That breadth is deliberate and must not
 * be narrowed: crediting only PING0 replies once demoted a healthy streaming
 * session back to re-authenticating every six seconds, while a couple of
 * hundred audio packets a second were arriving to say the link was fine.
 *
 * THE GAP IT LEAVES. mdxmixer answers PING0 **statelessly** -- the reply is
 * built from a cached identity and consults neither the peer table, nor the
 * PIN, nor the approval list. It answers a device it has never heard of, and
 * one it has explicitly denied. So when the PC restarts and loses its
 * in-memory approval:
 *
 *   - our pings keep being answered, so reachability stays true;
 *   - our TXT records are dropped in silence, so no commands work;
 *   - no audio arrives, because the peer is not entitled to any;
 *   - and the session sits in Streaming, believing everything is fine,
 *     for ever.
 *
 * Observed on the mixer as `peers=1 emitting=0` -- present, pinging, receiving
 * nothing -- eight times across forty minutes of PC restarts.
 *
 * A TXT REPLY CANNOT ARRIVE THAT WAY. An unauthorized peer's records are
 * dropped without an answer, so a reply is positive proof of the thing that
 * actually matters: the far end is mdxmixer, it is alive, and we are still
 * allowed to talk to it. Silence where we asked is proof of the opposite.
 *
 * The session already sends a query every tick while Streaming and the mixer
 * already answers it, so this costs no extra traffic and needs no new verb --
 * the same principle Reachability was built on.
 *
 * Pure, so the tolerance is argued about in a test rather than on the rig.
 */
class ControlLiveness(
    /**
     * Unanswered queries before the command surface is presumed dead.
     *
     * Three, at the session's 2 s tick, so about six seconds -- the same
     * number and the same reasoning as Reachability's. One lost reply is
     * ordinary on UDP and two happens; three in a row, while the far end is
     * supposed to answer every single one, is not a coincidence.
     */
    private val tolerance: Int = 3,
) {
    private var outstanding = 0

    /** False once [tolerance] consecutive queries have gone unanswered. */
    var answering: Boolean = true
        private set

    fun reset() {
        outstanding = 0
        answering = true
    }

    /**
     * A query went out. Returns true when the caller should RE-ASSERT AUTH.
     *
     * Re-asserting rather than tearing the session down is the point. The
     * state machine's own recovery -- drop to Pinging, re-authenticate --
     * works, but it flips the UI to "connecting" and that is the regression
     * the PING0-only crediting caused. An AUTH record is idempotent: a device
     * already approved gets `ok=1` straight back and nothing visible happens,
     * while a device the PC has forgotten gets re-approved and the stream
     * resumes. The same trade the forwarding subscription already makes by
     * re-asserting every tick.
     *
     * ONCE PER WINDOW, NOT ONCE PER TICK. The counter resets on the attempt,
     * so a mixer that is genuinely gone sees one AUTH every six seconds
     * rather than one every two -- recovery, not a retry storm, and the
     * server logs a line for each.
     */
    fun onQuerySent(): Boolean {
        outstanding++
        if (outstanding >= tolerance) {
            answering = false
            outstanding = 0
            return true
        }
        return false
    }

    /**
     * A TXT reply arrived. Any reply clears the whole debt rather than
     * decrementing it -- replies are not individually matched, and a late one
     * still proves the only thing being asked.
     */
    fun onReplyHeard() {
        outstanding = 0
        answering = true
    }

    /** For the diagnostics line, beside Reachability's own count. */
    val unanswered: Int get() = outstanding
}
