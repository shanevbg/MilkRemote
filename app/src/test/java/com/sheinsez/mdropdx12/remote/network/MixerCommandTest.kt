package com.sheinsez.mdropdx12.remote.network

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class MixerCommandTest {

    private fun <T> withLocale(locale: Locale, body: () -> T): T {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        try { return body() } finally { Locale.setDefault(previous) }
    }

    @Test
    fun aFaderMuteCarriesTheWantedState() {
        assertEquals("MIXER_MUTE=sonar:media|streaming|0", CommandBuilder.mixerMute("sonar:media", "streaming", false))
        assertEquals("MIXER_MUTE=sonar:media|streaming|1", CommandBuilder.mixerMute("sonar:media", "streaming", true))
    }

    /** Moving is by key, never by row index. */
    @Test
    fun orderMoveIsByKey() {
        assertEquals(
            "MIXER_ORDER_MOVE=sonar:aux|monitoring|-1",
            CommandBuilder.mixerOrderMove("sonar:aux|monitoring", -1),
        )
        assertEquals(
            "MIXER_ORDER_MOVE=sonar:aux|monitoring|1",
            CommandBuilder.mixerOrderMove("sonar:aux|monitoring", 1),
        )
    }
}
