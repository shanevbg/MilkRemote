package com.sheinsez.mdropdx12.remote.network

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class CommandBuilderTest {

    /**
     * The PC parses wire values with _wtof, which stops at a comma. A device set
     * to a comma-decimal locale must still send "0.50", never "0,50".
     */
    private fun <T> withLocale(locale: Locale, body: () -> T): T {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            return body()
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun signalUsesCanonicalName() {
        assertEquals("SIGNAL|MIRROR", CommandBuilder.signal("MIRROR"))
    }

    @Test
    fun signalMapsTemplateAliasesToServerNames() {
        assertEquals("SIGNAL|MIRROR", CommandBuilder.signal("SIG_MIRROR"))
        assertEquals("SIGNAL|MIRROR_WM", CommandBuilder.signal("SIG_MIRROR_WM"))
        assertEquals("SIGNAL|FULLSCREEN", CommandBuilder.signal("SIG_FULLSCREEN"))
        assertEquals("SIGNAL|WATERMARK", CommandBuilder.signal("SIG_WATERMARK"))
        assertEquals("SIGNAL|CAPTURE", CommandBuilder.signal("SIG_CAPTURE"))
        assertEquals("SIGNAL|BORDERLESS_FS", CommandBuilder.signal("SIG_BORDERLESS"))
    }

    @Test
    fun setDeviceVolumeUsesDotDecimalOnACommaLocale() {
        withLocale(Locale.GERMANY) {
            assertEquals("SET_DEVICE_VOLUME=0.50", CommandBuilder.setDeviceVolume(0.5f))
        }
    }

    @Test
    fun wireFloatUsesDotDecimalOnACommaLocale() {
        withLocale(Locale.GERMANY) {
            assertEquals("0.500", CommandBuilder.wireFloat(0.5f))
            assertEquals("1.000", CommandBuilder.wireFloat(1f))
            assertEquals("-0.250", CommandBuilder.wireFloat(-0.25f))
        }
    }

    /** The six wave sliders feed this; a comma here reads as zero on the PC. */
    @Test
    fun waveParamsCarryDotDecimalsOnACommaLocale() {
        withLocale(Locale.GERMANY) {
            val command = CommandBuilder.wave(
                mapOf(
                    "wave_a" to CommandBuilder.wireFloat(0.5f),
                    "zoom" to CommandBuilder.wireFloat(1.03f),
                ),
            )
            assertEquals("WAVE|wave_a=0.500|zoom=1.030", command)
        }
    }
}
