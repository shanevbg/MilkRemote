package com.sheinsez.mdropdx12.remote.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MixerLabelTest {

    private fun fader(ch: String, chname: String, id: String, label: String) = MixerFader(
        channelId = ch, channelName = chname, provider = "sonar", faderId = id,
        label = label, volumePercent = 50, muted = false, health = "ok", virtual = false,
    )

    private fun device(id: String, active: Boolean, battery: Int?) = MixerDevice(
        id = id, name = "n", windowsName = "w", flow = "render", active = active,
        displayAudio = false, handsfree = false, lastSeen = "", batteryPercent = battery,
    )

    /** Two faders on one channel: the row has to say which of them it is. */
    @Test
    fun aChannelWithSeveralFadersNamesBoth() {
        val faders = listOf(
            fader("sonar:aux", "Aux", "monitoring", "Monitoring"),
            fader("sonar:aux", "Aux", "streaming", "Streaming"),
        )
        val labels = displayLabels(faders)

        assertEquals("Aux \u2014 Monitoring", labels["sonar:aux|monitoring"])
        assertEquals("Aux \u2014 Streaming", labels["sonar:aux|streaming"])
    }

    /**
     * One fader: the CHANNEL is the name, as the PC does it. Every Windows
     * endpoint is such a channel, and " - Volume" after each of them says
     * nothing while costing the width the device's own name needs.
     */
    @Test
    fun aChannelWithOneFaderIsNamedByTheChannel() {
        val faders = listOf(fader("endpoint:{a}", "XM5 Black #1", "main", "Volume"))
        assertEquals("XM5 Black #1", displayLabels(faders)["endpoint:{a}|main"])
    }

    @Test
    fun blankNamesFallBackToTheIds() {
        val faders = listOf(
            fader("endpoint:{a}", "", "main", ""),
            fader("sonar:x", "", "monitoring", ""),
            fader("sonar:x", "", "streaming", ""),
        )
        val labels = displayLabels(faders)

        assertEquals("endpoint:{a}", labels["endpoint:{a}|main"])
        assertEquals("sonar:x \u2014 monitoring", labels["sonar:x|monitoring"])
    }

    // ── Battery, joined from the device record ──────────────────────────

    /** An endpoint fader's channel IS its device, once the prefix is dropped. */
    @Test
    fun anEndpointFaderFindsItsDevicesBattery() {
        val f = fader("endpoint:{0.0.0.0}.{xm6}", "XM6", "main", "Volume")
        val devices = listOf(device("{0.0.0.0}.{xm6}", active = true, battery = 72))

        assertEquals(72, batteryFor(f, devices))
    }

    /**
     * Windows keeps the last reading after a disconnect, so a number from a
     * device that is not here would look current and be days old.
     */
    @Test
    fun aDisconnectedDeviceReportsNoBattery() {
        val f = fader("endpoint:{0.0.0.0}.{xm6}", "XM6", "main", "Volume")
        val devices = listOf(device("{0.0.0.0}.{xm6}", active = false, battery = 72))

        assertNull(batteryFor(f, devices))
    }

    @Test
    fun aDeviceWithoutABatteryReportsNone() {
        val f = fader("endpoint:{0.0.0.0}.{spk}", "Speakers", "main", "Volume")
        val devices = listOf(device("{0.0.0.0}.{spk}", active = true, battery = null))

        assertNull(batteryFor(f, devices))
    }

    /** A Sonar channel is not an endpoint and has no device behind it. */
    @Test
    fun aSonarChannelHasNoDeviceAndSoNoBattery() {
        val f = fader("sonar:aux", "Aux", "monitoring", "Monitoring")
        val devices = listOf(device("{0.0.0.0}.{xm6}", active = true, battery = 72))

        assertNull(batteryFor(f, devices))
    }

    /**
     * The PC now puts the battery on the fader itself, read live from the
     * watcher. That figure wins: MIXER_BATTERY re-reads batteries without
     * re-polling a provider, so anything cached elsewhere would not move when
     * it ran.
     */
    @Test
    fun theFadersOwnBatteryIsTheOneUsed() {
        val f = fader("endpoint:{0.0.0.0}.{xm6}", "XM6", "main", "Volume")
            .copy(batteryPercent = 68)
        val devices = listOf(device("{0.0.0.0}.{xm6}", active = true, battery = 41))

        assertEquals(68, batteryFor(f, devices))
    }

    /** An older PC omits it, and the device record is still there to join to. */
    @Test
    fun withoutOneOnTheFaderTheDeviceRecordStillAnswers() {
        val f = fader("endpoint:{0.0.0.0}.{xm6}", "XM6", "main", "Volume")
        val devices = listOf(device("{0.0.0.0}.{xm6}", active = true, battery = 41))

        assertEquals(41, batteryFor(f, devices))
    }
}
