package com.sheinsez.mdropdx12.remote.network

import android.content.Context
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build

/**
 * What the Wi-Fi radio will tell us about the link, separately per direction.
 *
 * Tx and Rx are different numbers and the difference is the whole point. A
 * walk to the far end of the house took Rx from 272 Mbps to 36 while Tx stayed
 * at 1134 -- so everything that measures the link by sending something stayed
 * perfectly happy while the stream, which only receives, died. Reading one
 * figure for "the connection" would miss it exactly as Android's own
 * indicator does.
 *
 * Nothing here needs location permission, and nothing here reads SSID or
 * BSSID. Those are redacted without that permission anyway, and they identify
 * a place: a BSSID is a MAC address that public databases map to a street
 * address. The rates and the signal are not location-sensitive and come
 * through regardless.
 */
object WifiLink {

    /** No reading available, for any reason. Never confused with a slow link. */
    const val UNKNOWN = -1

    /**
     * A reading, and where it came from.
     *
     * [source] is carried because a reader that silently answers "unknown"
     * is a feature that does nothing with no way to find out why -- which is
     * the exact shape of every lying instrument this session had to unpick.
     */
    data class Reading(
        val rxMbps: Int = UNKNOWN,
        val txMbps: Int = UNKNOWN,
        val rssi: Int = UNKNOWN,
        val source: String = "none",
    )

    /**
     * Read the link, preferring what the network callback was handed.
     *
     * [callbackInfo] is the WifiInfo delivered to a REGISTERED
     * NetworkCallback. That is not a convenience: asking
     * `getNetworkCapabilities` directly returns `transportInfo` redacted to
     * null for an ordinary app, so the synchronous query cannot work at all
     * and the callback is the only supported path on modern Android.
     *
     * The deprecated getter is kept as a fallback because it still answers on
     * older releases and on devices where the callback has not fired yet --
     * at startup there is a window before the first onCapabilitiesChanged.
     */
    fun read(context: Context, callbackInfo: WifiInfo?): Reading {
        callbackInfo?.let { info ->
            val rx = rxOf(info)
            if (rx != UNKNOWN) {
                return Reading(rx, txOf(info), rssiOf(info), "callback")
            }
        }
        legacyWifiInfo(context)?.let { info ->
            val rx = rxOf(info)
            if (rx != UNKNOWN) {
                return Reading(rx, txOf(info), rssiOf(info), "wifiManager")
            }
            // Still worth the signal reading even with no rate: it is context
            // for a later diagnosis, and says the radio is at least present.
            return Reading(UNKNOWN, txOf(info), rssiOf(info), "wifiManager(noRate)")
        }
        return Reading(source = if (callbackInfo != null) "callback(noRate)" else "none")
    }

    private fun rxOf(info: WifiInfo): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return UNKNOWN
        return info.rxLinkSpeedMbps.takeIf { it >= 0 } ?: UNKNOWN
    }

    private fun txOf(info: WifiInfo): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return UNKNOWN
        return info.txLinkSpeedMbps.takeIf { it >= 0 } ?: UNKNOWN
    }

    /**
     * Signal in dBm. Context for a rate, never a substitute for one.
     *
     * The documented invalid value is the only one rejected: real readings
     * are negative, so a sign test would discard every good one.
     */
    private fun rssiOf(info: WifiInfo): Int =
        info.rssi.takeIf { it != Int.MIN_VALUE } ?: UNKNOWN

    @Suppress("DEPRECATION")
    private fun legacyWifiInfo(context: Context): WifiInfo? = runCatching {
        context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo
    }.getOrNull()
}
