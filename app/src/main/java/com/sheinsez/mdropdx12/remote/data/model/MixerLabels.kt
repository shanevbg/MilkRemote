package com.sheinsez.mdropdx12.remote.data.model

/**
 * A row label per fader key, following the PC's own rule.
 *
 * A channel carrying SEVERAL faders needs both halves — "Aux — Monitoring" —
 * because the list is flat and six channels' worth of "Monitoring" would be six
 * identical rows. A channel carrying ONE is named by the channel alone: every
 * Windows endpoint is such a channel, and writing " — Volume" after each of
 * them says nothing while costing exactly the width the device's own name
 * needs.
 *
 * Where a device has been given a short name on the PC
 * (`MIXER_RENAME_DEVICE=`), that name is already what arrives as `chname`, so
 * this renders it with nothing appended.
 */
fun displayLabels(faders: List<MixerFader>): Map<String, String> {
    val perChannel = faders.groupingBy { it.channelId }.eachCount()
    return faders.associate { f ->
        val channel = f.channelName.ifBlank { f.channelId }
        // A name the user chose wins outright, and is never trimmed or
        // combined: choosing "Aux P" and being shown "Aux - Aux P" would throw
        // away the choice, which is the whole reason for having made one.
        if (f.shortName.isNotBlank()) return@associate f.key to f.shortName
        f.key to if ((perChannel[f.channelId] ?: 1) > 1) {
            "$channel \u2014 ${f.label.ifBlank { f.faderId }}"
        } else {
            channel
        }
    }
}

/**
 * The charge of the headset a fader controls, or null.
 *
 * Only for a CONNECTED device on the fallback path. Windows keeps the last
 * reading after a disconnect, so a stale number would read as current — the
 * same rule the device list follows. The PC applies that rule itself on the
 * field it now sends.
 */
fun batteryFor(fader: MixerFader, devices: List<MixerDevice>): Int? {
    // The PC now puts it on the fader, read live from the device watcher. That
    // wins: MIXER_BATTERY re-reads batteries without re-polling a provider, so
    // a figure taken from anywhere else would not move when that verb ran.
    fader.batteryPercent?.let { return it }

    // An older PC sends no such field, and the device record is still there to
    // join to -- an endpoint fader's channel IS its device once the prefix
    // comes off. Kept so the screen still works before that build ships.
    val deviceId = fader.channelId.removePrefix("endpoint:")
    if (deviceId == fader.channelId) return null      // not an endpoint at all
    val device = devices.firstOrNull { it.id == deviceId } ?: return null
    return if (device.active) device.batteryPercent else null
}
