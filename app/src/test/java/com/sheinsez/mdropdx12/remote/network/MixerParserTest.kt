package com.sheinsez.mdropdx12.remote.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MixerParserTest {

    @Test
    fun parsesASonarFader() {
        val f = MessageParser.parseMixerFader(
            "MIXER_FADER|ch=sonar:aux|chname=Aux|provider=sonar|id=monitoring" +
                "|label=Monitoring|vol=0.030|mute=0|health=ok|virtual=0",
        )!!
        assertEquals("sonar:aux", f.channelId)
        assertEquals("Aux", f.channelName)
        assertEquals("monitoring", f.faderId)
        assertEquals("Monitoring", f.label)
        assertEquals(3, f.volumePercent)
        assertFalse(f.muted)
        assertFalse(f.virtual)
        assertEquals("sonar:aux|monitoring", f.key)
    }

    /** The eight endpoints Sonar owns: named like its channels, inert at 1.000. */
    @Test
    fun marksAVirtualEndpointFader() {
        val f = MessageParser.parseMixerFader(
            "MIXER_FADER|ch=endpoint:{0.0.0.00000000}.{a34d83b2}|chname=SteelSeries Sonar - Aux" +
                "|provider=endpoint|id=main|label=Volume|vol=1.000|mute=0|health=ok|virtual=1",
        )!!
        assertTrue(f.virtual)
        assertEquals(100, f.volumePercent)
        assertEquals("endpoint:{0.0.0.00000000}.{a34d83b2}|main", f.key)
    }

    /** An older PC omits virtual=; absent must not read as true. */
    @Test
    fun aMissingVirtualFieldIsFalse() {
        val f = MessageParser.parseMixerFader(
            "MIXER_FADER|ch=sonar:media|chname=Media|provider=sonar|id=streaming" +
                "|label=Streaming|vol=0.990|mute=1|health=ok",
        )!!
        assertFalse(f.virtual)
        assertTrue(f.muted)
        assertEquals(99, f.volumePercent)
    }

    @Test
    fun parsesADeviceWithBatteryAndPresence() {
        val d = MessageParser.parseMixerDevice(
            "MIXER_DEVICE|id={0.0.1.00000000}.{c6e6f3c7}|name=Headset (WF-1000XM5-2)" +
                "|windowsName=Headset (WF-1000XM5-2)|flow=capture|active=1|display=0" +
                "|handsfree=1|seen=2026-08-31 09:19|battery=63",
        )!!
        assertEquals("Headset (WF-1000XM5-2)", d.name)
        assertTrue(d.active)
        assertTrue(d.handsfree)
        assertFalse(d.displayAudio)
        assertEquals(63, d.batteryPercent)
        assertEquals("2026-08-31 09:19", d.lastSeen)
    }

    /** -1 means unknown, and must not render as a flat battery. */
    @Test
    fun anUnknownBatteryIsNotZero() {
        val d = MessageParser.parseMixerDevice(
            "MIXER_DEVICE|id={x}|name=n|windowsName=n|flow=render|active=0" +
                "|display=1|handsfree=0|seen=2025-04-21 17:19|battery=-1",
        )!!
        assertNull(d.batteryPercent)
        assertTrue(d.displayAudio)
    }

    @Test
    fun parsesAnOrderEntry() {
        val o = MessageParser.parseMixerOrder(
            "MIXER_ORDER|pos=18|key=sonar:chatRender|monitoring",
        )!!
        assertEquals(18, o.first)
        assertEquals("sonar:chatRender|monitoring", o.second)
    }

    /** The empty-order sentinel must not become a real entry. */
    @Test
    fun theEmptyOrderSentinelIsRejected() {
        assertNull(MessageParser.parseMixerOrder("MIXER_ORDER|pos=-1|key="))
    }

    /**
     * canMute=0 marks a fader with a working volume that must never be muted --
     * the Sonar master, whose mute writes every render channel and loses their
     * states coming back.
     */
    @Test
    fun aFaderThatMustNotBeMutedIsMarked() {
        val f = MessageParser.parseMixerFader(
            "MIXER_FADER|ch=sonar:masters|chname=Master|provider=sonar|id=monitoring" +
                "|label=Monitoring|vol=1.000|mute=0|health=ok|virtual=0|canMute=0",
        )!!
        assertFalse(f.canMute)
    }

    /** Absent means true: an older PC omits it, and false would strip every button. */
    @Test
    fun aMissingCanMuteFieldIsTrue() {
        val f = MessageParser.parseMixerFader(
            "MIXER_FADER|ch=sonar:aux|chname=Aux|provider=sonar|id=monitoring" +
                "|label=Monitoring|vol=0.030|mute=0|health=ok|virtual=0",
        )!!
        assertTrue(f.canMute)
    }

    @Test
    fun wrongRecordTypesAreRejected() {
        assertNull(MessageParser.parseMixerFader("MIXER_DEVICE|id=x"))
        assertNull(MessageParser.parseMixerDevice("MIXER_FADER|ch=x"))
    }

    /**
     * A fader carries both names and the battery, so a row can be drawn without
     * joining back to MIXER_DEVICE by an endpoint id it would otherwise never
     * look at.
     */
    @Test
    fun aFaderCarriesItsShortNameItsWindowsNameAndItsBattery() {
        val f = MessageParser.parseMixerFader(
            "MIXER_FADER|ch=endpoint:{0.0.0.0}.{xm6}|chname=XM6|provider=endpoint" +
                "|id=main|label=Volume|vol=0.350|mute=0|health=ok|virtual=0|canMute=1" +
                "|chwindowsName=Headphones (WF-1000XM6)|battery=68",
        )!!

        assertEquals("XM6", f.channelName)
        assertEquals("Headphones (WF-1000XM6)", f.windowsName)
        assertEquals(68, f.batteryPercent)
    }

    /**
     * -1 is ORDINARY, not an error: a Sonar channel has no device behind it,
     * plenty of devices report nothing, and an inactive endpoint is deliberately
     * -1 rather than the reading it had on the way out. Never show it as 0.
     */
    @Test
    fun aBatteryOfMinusOneIsNoBattery() {
        val f = MessageParser.parseMixerFader(
            "MIXER_FADER|ch=sonar:aux|chname=Aux|provider=sonar|id=monitoring" +
                "|label=Monitoring|vol=0.040|mute=0|health=ok|virtual=0|canMute=1" +
                "|chwindowsName=|battery=-1",
        )!!

        assertNull(f.batteryPercent)
        assertEquals("", f.windowsName)
    }

    /** An older PC sends neither field, and must not start reporting 0%. */
    @Test
    fun anOlderPcOmitsBothAndNothingIsInvented() {
        val f = MessageParser.parseMixerFader(
            "MIXER_FADER|ch=sonar:aux|chname=Aux|provider=sonar|id=monitoring" +
                "|label=Monitoring|vol=0.040|mute=0|health=ok|virtual=0|canMute=1",
        )!!

        assertNull(f.batteryPercent)
        assertEquals("", f.windowsName)
    }
}
