package com.sheinsez.mdropdx12.remote.network.vban

/**
 * Is the PC sending silence because it has nothing to send?
 *
 * THE RECEIVER CANNOT TELL FROM THE AUDIO. When mdxmixer has no render
 * device — headphones unplugged, no failover target — its mix has no clock,
 * so the sender drains an empty ring and emits well-formed packets full of
 * zeroes. `nuFrame` stays contiguous, the payload is
 * the right length, the jitter policy returns Play for every one. Nothing in
 * the stream itself says anything is wrong, and the listener just hears
 * nothing.
 *
 * The only tell is on the control channel: `starved` in MDXM_VBANSTATE counts
 * the packets the sender had to fill with silence. A rising count while
 * `emitting` is true is unambiguous.
 *
 * Pure, so the threshold can be reasoned about in a test rather than tuned
 * against a live rig.
 */
class SilenceDetector(
    /**
     * Starved packets per poll before this is believed.
     *
     * At 48 kHz with 256 samples a packet, the stream is ~188 packets a
     * second and the card polls every 2 s, so a genuinely clockless PC
     * contributes several hundred per sample. A handful is an ordinary
     * hiccup -- a scheduling blip at the far end -- and must not light a
     * warning that says the audio device has gone.
     */
    private val threshold: Int = 50,
) {
    private var last: Long? = null

    var silent: Boolean = false
        private set

    fun reset() {
        last = null
        silent = false
    }

    /** Feed each MDXM_VBANSTATE as it arrives. Returns the current verdict. */
    fun onState(starved: Long, emitting: Boolean): Boolean {
        val previous = last
        last = starved
        if (!emitting) {
            // Not sending at all is a different state with its own wording;
            // it is not silence arriving under a healthy-looking stream.
            silent = false
            return false
        }
        // Nothing to compare the first sample against. Deliberately not
        // treated as silence: a session that joins a long-running PC would
        // otherwise light up on a counter that grew before it arrived.
        if (previous == null) return silent
        val delta = starved - previous
        // A counter that went backwards means the PC restarted, not that it
        // recovered mid-poll.
        if (delta < 0) {
            silent = false
            return false
        }
        silent = delta >= threshold
        return silent
    }
}
