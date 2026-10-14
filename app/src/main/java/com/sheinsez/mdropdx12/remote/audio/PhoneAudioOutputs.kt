package com.sheinsez.mdropdx12.remote.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Where the phone can put the sound, and how loud it is there.
 *
 * ONE THING WORTH KNOWING BEFORE READING THE UI: Android keeps a **separate
 * media volume per output route**. A Bluetooth headset remembers its own
 * level, independent of the speaker's, and switching route switches the
 * number. That is the platform, not this app — and it is why the fader shows
 * the level of whatever is selected rather than one global figure, and why
 * the headset's volume genuinely does not move when the speaker's does.
 */
data class PhoneOutput(
    val id: Int,
    val label: String,
    /** True for the route the system is currently using. */
    val active: Boolean,
) {
    companion object {
        /** The system's own choice, which is what we use unless told otherwise. */
        const val AUTOMATIC_ID = -1
    }
}

object PhoneAudioOutputs {

    /**
     * Human names for the device types worth offering. Deliberately a short
     * list: the full AudioDeviceInfo set includes things like telephony and
     * HDMI-ARC that are not choices anybody makes from a remote-control app.
     */
    fun label(type: Int, productName: String?): String {
        val base = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE speaker"
            AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing aid"
            else -> null
        } ?: return productName?.takeIf { it.isNotBlank() } ?: "Other output"
        // A Bluetooth route without its product name is just "Bluetooth",
        // which is useless with two headsets paired.
        val name = productName?.takeIf { it.isNotBlank() && !base.startsWith(it) }
        return if (name != null && type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
            "$base — $name"
        } else {
            base
        }
    }

    /** Which device types are worth listing at all. */
    fun isOfferable(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HEARING_AID,
        -> true
        else -> false
    }

    fun outputs(context: Context): List<PhoneOutput> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return emptyList()
        val devices = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
            .getOrNull() ?: return emptyList()
        // "Automatic" first and always present: following the system is the
        // right default, and it is the only entry that survives a headset
        // being unplugged mid-stream.
        val out = mutableListOf(PhoneOutput(PhoneOutput.AUTOMATIC_ID, "Automatic", false))
        for (d in devices) {
            if (!isOfferable(d.type)) continue
            out += PhoneOutput(d.id, label(d.type, d.productName?.toString()), false)
        }
        return out
    }

    fun deviceFor(context: Context, id: Int): AudioDeviceInfo? {
        if (id == PhoneOutput.AUTOMATIC_ID) return null
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        return runCatching {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id }
        }.getOrNull()
    }

    /** 0..1 of the CURRENT route's media volume. */
    fun volume(context: Context): Float {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 0f
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 0f
        return am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    fun setVolume(context: Context, fraction: Float) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        val want = (fraction.coerceIn(0f, 1f) * max).toInt()
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, want, 0) }
    }

    /** How many steps the rocker has, so the fader can match the hardware. */
    fun steps(context: Context): Int {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 0
        return runCatching { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
    }
}
