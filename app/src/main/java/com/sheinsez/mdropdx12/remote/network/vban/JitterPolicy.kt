package com.sheinsez.mdropdx12.remote.network.vban

/**
 * Buffer depth, and the gap this tolerates before giving up and re-locking.
 *
 * Resilient is the default for every route: it is what makes ~200 ms STABLE
 * rather than ~200 ms with dropouts. Low-latency is an explicit opt-in for
 * quiet-network experiments, not something to select automatically from the
 * audio route — the route is shown as information, never used to choose.
 */
enum class LatencyProfile(val bufferMs: Int) { Resilient(150), LowLatency(40) }

enum class PlayoutAction { Play, InsertSilence, Restart, Drop }

data class PlayoutDecision(val action: PlayoutAction, val silenceSamples: Int = 0)

/**
 * What to do with the packet that just arrived.
 *
 * PURE — no AudioTrack, no clock, no coroutines — because the JVM suite has
 * no Robolectric and `android.media` throws `Stub!` there. The player drives
 * this; this decides. Every rule below is a decision that would otherwise be
 * buried in a callback nobody can test.
 */
class JitterPolicy(private val profile: LatencyProfile) {

    companion object {
        /**
         * How far behind a packet must be before it means the sender restarted
         * rather than that the network reordered something.
         *
         * A reorder on a LAN is single-digit packets. At roughly five
         * milliseconds a packet this is half a second of them -- far past any
         * plausible reorder, and far short of the two billion that a genuine
         * counter wrap would present as.
         */
        const val RESTART_BEHIND_PACKETS = 100L
    }

    private var last: Long? = null

    var inserts = 0; private set
    var restarts = 0; private set
    var drops = 0; private set

    fun reset() { last = null }

    /**
     * Serial-number arithmetic, mod 2^32.
     *
     * `nuFrame` is monotonic for the SERVER's whole process lifetime — it
     * never resets when emission stops and restarts — so it wraps rather than
     * starting over. A naive subtraction reads that wrap as a jump of about
     * four billion and flushes a perfectly healthy buffer, once every few
     * hours of listening, for no reason anybody could diagnose.
     */
    private fun forwardDistance(from: Long, to: Long): Long = (to - from) and 0xFFFFFFFFL

    fun onPacket(nuFrame: Long, samplesPerPacket: Int, sampleRate: Int): PlayoutDecision {
        val previous = last
        if (previous == null) {
            last = nuFrame
            return PlayoutDecision(PlayoutAction.Play)
        }
        val forward = forwardDistance(previous, nuFrame)
        // Anything in the top half of the space is behind us, not ahead: a
        // reordered or duplicated packet, not a four-billion-packet jump.
        val backwards = forward > 0x80000000L
        if (backwards) {
            val behind = 0x1_0000_0000L - forward
            // THE SENDER STARTED OVER, which is not a reordered packet.
            //
            // mdxmixer's audio counter is one server-wide monotonic value
            // (`net/vban_server.h:193`), so it does not reset when this phone's
            // peer expires and re-registers -- but it DOES reset when mdxmixer
            // itself restarts, or when its VBAN server is stopped and started.
            //
            // Treating that as a reorder drops the packet AND leaves `last`
            // untouched, so the next packet is judged against the same stale
            // value and dropped too. Every packet after a restart, for ever:
            // silence that looks exactly like a healthy stream, recoverable
            // only by stopping and starting playback by hand. The SilenceDetector
            // already had to reason about a counter going backwards for the same
            // reason; this is the same event seen through a different counter.
            //
            // The threshold separates the two cases rather than guessing: a
            // reorder on a LAN is single-digit packets, and at ~5 ms a packet
            // this is half a second of them.
            if (behind > RESTART_BEHIND_PACKETS) {
                restarts++
                last = nuFrame
                return PlayoutDecision(PlayoutAction.Restart)
            }
            drops++
            return PlayoutDecision(PlayoutAction.Drop)
        }
        if (forward == 0L) {
            // The same packet twice. Nothing to play and nothing to re-lock.
            drops++
            return PlayoutDecision(PlayoutAction.Drop)
        }
        val missing = forward - 1L
        val packetMs = samplesPerPacket * 1000.0 / sampleRate
        val gapMs = missing * packetMs
        last = nuFrame
        return when {
            missing == 0L -> PlayoutDecision(PlayoutAction.Play)
            // Inside the buffer depth the hole can be filled and the stream
            // stays locked: a lost packet is a lost few milliseconds, not a
            // reason to re-establish.
            gapMs <= profile.bufferMs -> {
                inserts++
                PlayoutDecision(PlayoutAction.InsertSilence, (missing * samplesPerPacket).toInt())
            }
            // Beyond it, re-locking beats pretending: inserting that much
            // silence would just delay everything after it for ever.
            else -> {
                restarts++
                last = nuFrame
                PlayoutDecision(PlayoutAction.Restart)
            }
        }
    }
}
