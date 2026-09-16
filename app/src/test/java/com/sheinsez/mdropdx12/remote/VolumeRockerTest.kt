package com.sheinsez.mdropdx12.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeRockerTest {

    @Test
    fun shouldInterceptOnlyWhenEnabledAndConnected() {
        assertFalse(VolumeRocker.shouldIntercept(enabled = false, connected = false))
        assertFalse(VolumeRocker.shouldIntercept(enabled = true, connected = false))
        assertFalse(VolumeRocker.shouldIntercept(enabled = false, connected = true))
        assertTrue(VolumeRocker.shouldIntercept(enabled = true, connected = true))
    }

    @Test
    fun nextVolumeStepsByPercentAndClamps() {
        assertEquals(0.52f, VolumeRocker.nextVolume(0.50f, up = true, stepPercent = 2), 0.0001f)
        assertEquals(0.48f, VolumeRocker.nextVolume(0.50f, up = false, stepPercent = 2), 0.0001f)
        assertEquals(1.0f, VolumeRocker.nextVolume(0.99f, up = true, stepPercent = 2), 0.0001f)
        assertEquals(0.0f, VolumeRocker.nextVolume(0.01f, up = false, stepPercent = 2), 0.0001f)
    }

    @Test
    fun clampStepPercentLimitsRange() {
        assertEquals(1, VolumeRocker.clampStepPercent(0))
        assertEquals(10, VolumeRocker.clampStepPercent(99))
        assertEquals(2, VolumeRocker.clampStepPercent(2))
    }
}
