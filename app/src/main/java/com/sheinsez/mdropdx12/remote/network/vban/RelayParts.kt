package com.sheinsez.mdropdx12.remote.network.vban

/** One `MDXM_FWDP|id=|i=|n=|msg=` record: a slice of a relayed record. */
data class RelayPart(
    /** Names the record. The same on every part of it. */
    val id: String,
    /** This part's place, counting from 0. */
    val index: Int,
    /** How many parts the record was cut into. */
    val count: Int,
    val slice: String,
)

/**
 * Puts a relayed record back together from its parts.
 *
 * A record too large for one packet used to be answered `MDXM_ERR|msg=toolong`
 * and now crosses as numbered parts, each a whole record that fits. That is
 * what lets a phone whose only wire is the relay learn what displays exist:
 * DIAG_MIRRORS is ONE record of about 2.7 KB with three displays attached.
 *
 * The mixer sends each part once and UDP promises nothing about them, so
 * everything here is about what to do when the parts of one record do not
 * arrive as a tidy pair:
 *
 *   * OUT OF ORDER -- joined by index, never by arrival.
 *   * ONE MISSING -- the record is lost, and only that record. One still short
 *     after [expireMs] will stay short, and keeping it would join it to the
 *     parts of whatever later record is given the same id.
 *   * INTERLEAVED -- the relay carries every client's answers, so two large
 *     records can be in flight at once. They are kept apart by id.
 *
 * Pure, with the clock passed in, so each of those can be tested without a
 * socket. The caller owns the two things this cannot know: when to [clear]
 * (the mixer restarted, so ids start again from zero), and what to do about an
 * answer that never completes (ask again).
 */
class RelayParts(
    private val expireMs: Long = EXPIRE_MS,
    private val maxOpen: Int = MAX_OPEN,
) {
    companion object {
        /** "A couple of seconds", in the wire's own words. */
        const val EXPIRE_MS = 2_000L

        /**
         * Unfinished records held at once. Each is a reply somebody asked
         * for, so more than a handful means parts are being lost faster than
         * they expire, and the oldest is not going to be finished.
         */
        const val MAX_OPEN = 8
    }

    private class Unfinished(val count: Int, val startedMs: Long) {
        val slices = arrayOfNulls<String>(count)
        var have = 0
    }

    // Insertion-ordered, so the first key is the oldest record.
    private val unfinished = LinkedHashMap<String, Unfinished>()

    /** Records with at least one part in hand and at least one missing. */
    val open: Int get() = unfinished.size

    /** The whole record once its last part is in hand, else null. */
    fun accept(part: RelayPart, nowMs: Long): String? {
        unfinished.values.removeAll { nowMs - it.startedMs >= expireMs }

        var record = unfinished[part.id]
        // The same id with a different count is not the same record: the
        // mixer restarted between the two and is counting from zero again.
        if (record != null && record.count != part.count) {
            unfinished.remove(part.id)
            record = null
        }
        if (record == null) {
            if (unfinished.size >= maxOpen) unfinished.remove(unfinished.keys.first())
            record = Unfinished(part.count, nowMs)
            unfinished[part.id] = record
        }

        if (record.slices[part.index] == null) record.have++
        record.slices[part.index] = part.slice
        if (record.have < record.count) return null

        unfinished.remove(part.id)
        return record.slices.joinToString("")
    }

    /** Forget every unfinished record. */
    fun clear() = unfinished.clear()
}
