package com.sheinsez.mdropdx12.remote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemReadinessTest {

    private val allBad = SystemState(
        ignoringBatteryOptimizations = false,
        notificationsEnabled = false,
        hibernationEnabled = true,
    )
    private val allGood = SystemState(
        ignoringBatteryOptimizations = true,
        notificationsEnabled = true,
        hibernationEnabled = false,
    )

    /**
     * Nothing is suggested to somebody who only uses the remote. Asking a
     * remote-control user to exempt the app from battery optimization is
     * noise; asking somebody who streams audio is useful.
     */
    @Test
    fun `a foreground-only user is never nagged`() {
        assertTrue(SystemReadiness.suggestions(allBad, wantsBackground = false).isEmpty())
    }

    @Test
    fun `a background user is told what will interrupt them`() {
        val s = SystemReadiness.suggestions(allBad, wantsBackground = true)
        assertEquals(
            listOf(
                Suggestion.BatteryOptimization,
                Suggestion.Notifications,
                Suggestion.AppHibernation,
            ),
            s,
        )
    }

    @Test
    fun `nothing is suggested once the phone is out of the way`() {
        assertTrue(SystemReadiness.suggestions(allGood, wantsBackground = true).isEmpty())
    }

    @Test
    fun `only what is actually wrong is offered`() {
        val s = SystemReadiness.suggestions(
            allGood.copy(ignoringBatteryOptimizations = false),
            wantsBackground = true,
        )
        assertEquals(listOf(Suggestion.BatteryOptimization), s)
    }

    /** Every suggestion can say what it is and why, or it is not worth showing. */
    @Test
    fun `each suggestion has a title and a reason`() {
        for (s in Suggestion.entries) {
            assertTrue(SystemReadiness.title(s).isNotBlank())
            assertTrue(SystemReadiness.reason(s).length > 20)
        }
    }
}
