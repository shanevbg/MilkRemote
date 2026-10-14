package com.sheinsez.mdropdx12.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who gets the volume keys once the phone is making the sound.
 *
 * Two shipped features already point the keys at the PC — `volume_rocker_enabled`
 * intercepts them here, and `media_device` hands them to a remote
 * VolumeProvider — so "the stream owns them" has to be MADE true rather than
 * assumed.
 */
class RockerPrecedenceTest {

    @Test
    fun `streaming alone does not take the keys`() {
        assertFalse(
            VolumeRocker.localOutputOwnsKeys(streaming = true, linkWhenStreaming = false),
        )
    }

    @Test
    fun `the setting alone does not take the keys either`() {
        assertFalse(
            VolumeRocker.localOutputOwnsKeys(streaming = false, linkWhenStreaming = true),
        )
    }

    @Test
    fun `both together hand the keys to the phone's own output`() {
        assertTrue(
            VolumeRocker.localOutputOwnsKeys(streaming = true, linkWhenStreaming = true),
        )
    }

    @Test
    fun `neither leaves the PC in charge`() {
        assertFalse(
            VolumeRocker.localOutputOwnsKeys(streaming = false, linkWhenStreaming = false),
        )
    }

    /**
     * The shipped default, which decides what happens with nobody having
     * touched a setting: audio in your ears, and the volume keys change it.
     *
     * It was off at first, on the argument that a phone streaming the monitor
     * mix is still a remote control. Both ways were tried on the rig and this
     * one won -- pressing volume-down with music playing and having something
     * move on the PC instead is not what anybody means by the volume keys.
     */
    @Test
    fun `by default a playing stream owns the keys`() {
        assertTrue(
            VolumeRocker.localOutputOwnsKeys(
                streaming = true,
                linkWhenStreaming = VolumeRocker.ROCKER_LOCAL_DEFAULT,
            ),
        )
    }

    /** And with nothing playing, the default still leaves the PC in charge. */
    @Test
    fun `the default does not take the keys when nothing is playing`() {
        assertFalse(
            VolumeRocker.localOutputOwnsKeys(
                streaming = false,
                linkWhenStreaming = VolumeRocker.ROCKER_LOCAL_DEFAULT,
            ),
        )
    }

    /** The existing rule is untouched: the PC path still needs both of its own. */
    @Test
    fun `the PC interception still requires enabled and connected`() {
        assertTrue(VolumeRocker.shouldIntercept(enabled = true, connected = true))
        assertFalse(VolumeRocker.shouldIntercept(enabled = true, connected = false))
        assertFalse(VolumeRocker.shouldIntercept(enabled = false, connected = true))
    }
}
