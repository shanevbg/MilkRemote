package com.sheinsez.mdropdx12.remote.data.model

/**
 * Levels and mutes the user has just set, held over what the PC last said.
 *
 * The mixer is POLLED, and a reading asked for before a press and answered
 * after it carries the level from before the press. Drawn as it arrives, the
 * row steps back -- and the step buttons compute from the number on screen, so
 * the next press is measured from the stale one and a step is lost.
 *
 * So a write is held for a short while, and let go the moment the PC agrees
 * with it: holding for the full time regardless would paint the phone's own
 * number over a change made at the PC a moment later.
 *
 * No clock of its own, so the rule can be tested.
 */
class PendingWrites(private val holdMs: Long = HOLD_MS) {

    companion object {
        /**
         * Longer than one poll and its answer, shorter than anyone would wait
         * to see a level that was refused snap back.
         */
        const val HOLD_MS = 1_500L
    }

    private data class Held<T>(val value: T, val untilMs: Long)

    private val volumes = mutableMapOf<String, Held<Int>>()
    private val mutes = mutableMapOf<String, Held<Boolean>>()

    fun volume(key: String, percent: Int, nowMs: Long) {
        volumes[key] = Held(percent, nowMs + holdMs)
    }

    fun mute(key: String, muted: Boolean, nowMs: Long) {
        mutes[key] = Held(muted, nowMs + holdMs)
    }

    fun clear() {
        volumes.clear()
        mutes.clear()
    }

    /** [faders] as read, with whatever is still being held laid over them. */
    fun over(faders: List<MixerFader>, nowMs: Long): List<MixerFader> {
        if (volumes.isEmpty() && mutes.isEmpty()) return faders
        return faders.map { f ->
            val percent = settle(volumes, f.key, f.volumePercent, nowMs)
            val muted = settle(mutes, f.key, f.muted, nowMs)
            if (percent == f.volumePercent && muted == f.muted) f
            else f.copy(volumePercent = percent, muted = muted)
        }
    }

    private fun <T> settle(held: MutableMap<String, Held<T>>, key: String, read: T, nowMs: Long): T {
        val h = held[key] ?: return read
        // Out of time, or the PC now says the same thing: either way the
        // reading is the truth from here on.
        if (nowMs >= h.untilMs || h.value == read) {
            held.remove(key)
            return read
        }
        return h.value
    }
}
