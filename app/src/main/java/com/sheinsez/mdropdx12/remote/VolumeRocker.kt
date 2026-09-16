package com.sheinsez.mdropdx12.remote

object VolumeRocker {
    const val DEFAULT_STEP_PERCENT = 2
    const val MIN_STEP_PERCENT = 1
    const val MAX_STEP_PERCENT = 10

    fun shouldIntercept(enabled: Boolean, connected: Boolean): Boolean =
        enabled && connected

    fun clampStepPercent(stepPercent: Int): Int =
        stepPercent.coerceIn(MIN_STEP_PERCENT, MAX_STEP_PERCENT)

    fun nextVolume(current: Float, up: Boolean, stepPercent: Int): Float {
        val step = clampStepPercent(stepPercent) / 100f
        val delta = if (up) step else -step
        return (current + delta).coerceIn(0f, 1f)
    }
}
