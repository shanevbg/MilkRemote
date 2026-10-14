package com.sheinsez.mdropdx12.remote

object VolumeRocker {
    const val DEFAULT_STEP_PERCENT = 2

    /**
     * Whether the volume keys start out pointed at this phone's own output
     * while the stream plays.
     *
     * Here rather than inline at the settings read, because that read needs
     * the value twice -- once as the DataStore fallback and once as the flow's
     * initial value -- and two copies of a default are two defaults waiting to
     * disagree.
     */
    const val ROCKER_LOCAL_DEFAULT = true
    const val MIN_STEP_PERCENT = 1
    const val MAX_STEP_PERCENT = 10

    fun shouldIntercept(enabled: Boolean, connected: Boolean): Boolean =
        enabled && connected

    /**
     * Do the volume keys belong to this phone's own output rather than to the
     * PC's faders?
     *
     * Only while the VBAN stream is actually playing, and **default ON**.
     *
     * It defaulted off at first, on the argument that a phone streaming the
     * monitor mix is still a remote control and somebody who linked faders to
     * the rocker wants those faders. Both ways were tried on the rig and the
     * design spec's original reading won: with audio in your ears, pressing
     * volume-down and having something move on the PC instead is not what
     * anybody means by the volume keys.
     *
     * The option survives for the fader case, which is why this is still a
     * parameter and not a constant.
     */
    fun localOutputOwnsKeys(streaming: Boolean, linkWhenStreaming: Boolean): Boolean =
        streaming && linkWhenStreaming

    fun clampStepPercent(stepPercent: Int): Int =
        stepPercent.coerceIn(MIN_STEP_PERCENT, MAX_STEP_PERCENT)

    fun nextVolume(current: Float, up: Boolean, stepPercent: Int): Float {
        val step = clampStepPercent(stepPercent) / 100f
        val delta = if (up) step else -step
        return (current + delta).coerceIn(0f, 1f)
    }
}
