package com.sheinsez.mdropdx12.remote.data.model

/**
 * One volume-key press, waiting for levels to be measured against.
 *
 * A press made with nothing known, or with only an old reading, cannot be
 * applied honestly: "up one step" from a level the PC changed an hour ago is a
 * jump, not a step. So the press is held while a fresh reading is fetched.
 *
 * AND IT EXPIRES. Held with no limit it is applied whenever a reading next
 * arrives -- and with the screen off and the link down that can be minutes
 * later, which is a level moving by itself.
 *
 * One press, the latest. A queue would replay a burst of them all at once.
 */
class HeldPress(private val validMs: Long = VALID_MS) {

    companion object {
        const val VALID_MS = 2_000L
    }

    private var up: Boolean? = null
    private var heldAtMs = 0L

    // Held from the thread a key press arrives on and taken from the one a
    // reading arrives on, so the pair is atomic.

    @Synchronized
    fun hold(up: Boolean, nowMs: Long) {
        this.up = up
        heldAtMs = nowMs
    }

    /** The press, if one is held and still recent. Either way it is gone after this. */
    @Synchronized
    fun take(nowMs: Long): Boolean? {
        val held = up ?: return null
        up = null
        return if (nowMs - heldAtMs <= validMs) held else null
    }
}
