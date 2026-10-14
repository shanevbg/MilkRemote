package com.sheinsez.mdropdx12.remote.data.model

import kotlin.math.abs

/**
 * New levels for a linked group after one rocker press.
 *
 * The group clamps TOGETHER: the applied delta is reduced until every member
 * stays in range, so the balance between them is preserved. Clamping each one
 * separately would let them drift apart at the ends, and after one trip to zero
 * and back the mix the user set would be gone.
 */
fun nudgeLinked(faders: List<MixerFader>, step: Int, up: Boolean): Map<String, Int> {
    if (faders.isEmpty()) return emptyMap()
    val wanted = if (up) step else -step

    // The most any member can move before it clips, in the wanted direction.
    val headroom = faders.minOf { f ->
        if (wanted > 0) 100 - f.volumePercent else f.volumePercent
    }.coerceAtLeast(0)

    val magnitude = minOf(abs(wanted), headroom)
    val delta = if (wanted > 0) magnitude else -magnitude
    return faders.associate { it.key to (it.volumePercent + delta).coerceIn(0, 100) }
}
