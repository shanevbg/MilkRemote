package com.sheinsez.mdropdx12.remote.data.model

/**
 * The reorder a user asked for, held until the PC settles it.
 *
 * A move carries the arrangement token it was made against, and the PC refuses
 * one made against a list that has since changed. That refusal is a normal
 * event rather than a fault: the pinned failover device swaps out every few
 * hours, so a list fetched a minute ago can genuinely have a different fader
 * under the finger. The row the user pressed is still on screen either way, so
 * the intent outlives the refusal and is asked again against the list that
 * replaced it.
 *
 * Two invariants, both learned the hard way rather than guessed:
 *
 * - **Once per press.** A PC that kept reordering would otherwise turn one
 *   press into an endless argument.
 * - **Only a refusal arms the retry.** Views arrive for every reason -- a
 *   device connecting, a reorder made at the PC -- and re-sending on any of
 *   them would resurface a move whose acknowledgement was merely lost, minutes
 *   later, on an unrelated event.
 */
class MixerMoveIntent {
    private var pending: Pair<String, Int>? = null
    private var retried = false
    private var armed = false

    /** A fresh press. Replaces whatever was pending, and restores the retry. */
    fun start(key: String, direction: Int): Pair<String, Int> {
        pending = key to direction
        retried = false
        armed = false
        return key to direction
    }

    /** The PC applied it. */
    fun acknowledged() {
        pending = null
        armed = false
    }

    /** Nothing to send after all — already at that end of the list. */
    fun abandon() {
        pending = null
        armed = false
    }

    /**
     * The PC refused it as stale.
     *
     * @return true when it will be asked again once the replacement list
     * arrives; false when the one retry is already spent, and the intent is
     * dropped.
     */
    fun refused(): Boolean {
        if (pending == null || retried) {
            pending = null
            armed = false
            return false
        }
        armed = true
        return true
    }

    /** A view arrived. @return the move to re-send, or null. */
    fun onView(): Pair<String, Int>? {
        if (!armed) return null
        armed = false
        retried = true
        return pending
    }
}
