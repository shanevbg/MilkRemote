package com.sheinsez.mdropdx12.remote.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MixerOrderingTest {

    private fun fader(ch: String, id: String, virtual: Boolean = false) = MixerFader(
        channelId = ch, channelName = ch.substringAfter(':'), provider = "sonar",
        faderId = id, label = id, volumePercent = 50, muted = false,
        health = "ok", virtual = virtual,
    )

    /**
     * The whole point: the PC owns the positions, so an interleave is rendered
     * as an interleave. chatRender's two faders sit either side of aux and stay
     * there — an earlier build hoisted aux up to keep the channel's rows
     * together, and the phone then disagreed with the window on the monitor.
     */
    @Test
    fun theStoredOrderIsRenderedLiterallyEvenWhenChannelsInterleave() {
        val faders = listOf(
            fader("sonar:aux", "monitoring"),
            fader("sonar:chatRender", "streaming"),
            fader("sonar:chatRender", "monitoring"),
        )
        val order = listOf(
            "sonar:chatRender|monitoring",
            "sonar:aux|monitoring",
            "sonar:chatRender|streaming",
        )

        val rows = orderFadersForDisplay(faders, order, showVirtual = false)

        assertEquals(
            listOf(
                "sonar:chatRender|monitoring",
                "sonar:aux|monitoring",
                "sonar:chatRender|streaming",
            ),
            rows.map { it.key },
        )
    }

    /** Nothing is sorted by name: masters is stored streaming-first and stays so. */
    @Test
    fun noSecondarySortIsAppliedOverTheStoredPositions() {
        val faders = listOf(
            fader("sonar:masters", "monitoring"),
            fader("sonar:masters", "streaming"),
        )
        val order = listOf("sonar:masters|streaming", "sonar:masters|monitoring")

        val rows = orderFadersForDisplay(faders, order, showVirtual = false)

        assertEquals(listOf("streaming", "monitoring"), rows.map { it.faderId })
    }

    @Test
    fun virtualFadersAreHiddenUnlessAskedFor() {
        val faders = listOf(
            fader("sonar:aux", "monitoring"),
            fader("endpoint:{a}", "main", virtual = true),
        )
        val order = listOf("sonar:aux|monitoring", "endpoint:{a}|main")

        assertEquals(1, orderFadersForDisplay(faders, order, showVirtual = false).size)
        assertEquals(2, orderFadersForDisplay(faders, order, showVirtual = true).size)
    }

    @Test
    fun everythingFilteredOutLeavesAnEmptyList() {
        val faders = listOf(fader("endpoint:{a}", "main", virtual = true))
        val rows = orderFadersForDisplay(faders, listOf("endpoint:{a}|main"), showVirtual = false)
        assertTrue(rows.isEmpty())
    }

    /**
     * A device that has just joined is not in the order yet. It has to appear
     * rather than vanish, and it belongs after everything the PC has placed —
     * position 0 is where a bare indexOf's -1 would wrongly put it.
     */
    @Test
    fun anUnplacedFaderGoesToTheEndRatherThanVanishing() {
        val faders = listOf(
            fader("sonar:new", "monitoring"),
            fader("sonar:aux", "monitoring"),
        )
        val rows = orderFadersForDisplay(faders, listOf("sonar:aux|monitoring"), showVirtual = false)

        assertEquals(listOf("sonar:aux|monitoring", "sonar:new|monitoring"), rows.map { it.key })
    }

    /** Two unplaced faders keep the order they arrived in, so the list does not shuffle. */
    @Test
    fun unplacedFadersKeepTheirArrivalOrder() {
        val faders = listOf(fader("sonar:z", "monitoring"), fader("sonar:a", "monitoring"))
        val rows = orderFadersForDisplay(faders, emptyList(), showVirtual = false)

        assertEquals(listOf("sonar:z|monitoring", "sonar:a|monitoring"), rows.map { it.key })
    }

    // ── The DRAWN list (MIXER_VIEW), which is what the PC shows ──────────

    private fun row(key: String, order: Int, pos: Int,
                    hidden: Boolean = false, pinned: Boolean = false) =
        MixerViewRow(key = key, order = order, pos = pos, hidden = hidden, pinned = pinned)

    /**
     * The whole point of MIXER_VIEW: a failover device is pinned to the top by
     * the PC while the stored order has it last. The drawn position wins.
     */
    @Test
    fun theDrawnPositionWinsOverTheStoredOne() {
        val faders = listOf(
            fader("sonar:aux", "monitoring"),
            fader("endpoint:{xm5}", "main"),
        )
        val view = MixerView(
            rows = listOf(
                row("sonar:aux|monitoring", order = 7, pos = 1),
                row("endpoint:{xm5}|main", order = 24, pos = 0, pinned = true),
            ),
            rev = "1a2b3c4d",
        )

        val rows = viewFadersForDisplay(faders, view, order = emptyList(), showVirtual = false)

        assertEquals(listOf("endpoint:{xm5}|main", "sonar:aux|monitoring"), rows.map { it.key })
    }

    /**
     * The PC reports what it is hiding rather than omitting it, so the switch
     * reveals exactly those rows. They go to the END: the PC draws them
     * nowhere, so there is no drawn position to put them at, and inventing one
     * is the thing this whole change exists to stop.
     */
    @Test
    fun theRowsThePcHidesAreRevealedAtTheEndOrNotAtAll() {
        val faders = listOf(
            fader("endpoint:{decoy}", "main"),
            fader("sonar:aux", "monitoring"),
        )
        val view = MixerView(
            rows = listOf(
                row("sonar:aux|monitoring", order = 7, pos = 0),
                row("endpoint:{decoy}|main", order = 3, pos = -1, hidden = true),
            ),
            rev = "1a2b3c4d",
        )

        assertEquals(
            listOf("sonar:aux|monitoring"),
            viewFadersForDisplay(faders, view, emptyList(), showVirtual = false).map { it.key },
        )
        assertEquals(
            listOf("sonar:aux|monitoring", "endpoint:{decoy}|main"),
            viewFadersForDisplay(faders, view, emptyList(), showVirtual = true).map { it.key },
        )
    }

    /** A fader that arrived after the view was fetched still has to appear. */
    @Test
    fun aFaderTheViewHasNotPlacedYetGoesToTheEnd() {
        val faders = listOf(fader("sonar:new", "monitoring"), fader("sonar:aux", "monitoring"))
        val view = MixerView(rows = listOf(row("sonar:aux|monitoring", 7, 0)), rev = "1a2b3c4d")

        assertEquals(
            listOf("sonar:aux|monitoring", "sonar:new|monitoring"),
            viewFadersForDisplay(faders, view, emptyList(), showVirtual = false).map { it.key },
        )
    }

    /** A PC too old to know MIXER_VIEW answers nothing, and the stored order stands. */
    @Test
    fun withNoViewTheStoredOrderIsStillUsed() {
        val faders = listOf(fader("sonar:media", "monitoring"), fader("sonar:aux", "monitoring"))
        val order = listOf("sonar:media|monitoring", "sonar:aux|monitoring")

        assertEquals(
            listOf("sonar:media|monitoring", "sonar:aux|monitoring"),
            viewFadersForDisplay(faders, null, order, showVirtual = false).map { it.key },
        )
        assertEquals(
            listOf("sonar:media|monitoring", "sonar:aux|monitoring"),
            viewFadersForDisplay(faders, MixerView(), order, showVirtual = false).map { it.key },
        )
    }

    // ── Moving: render the view, move in the order ──────────────────────

    private val threeRows = MixerView(
        rows = listOf(
            row("a|main", order = 0, pos = 0),
            row("b|main", order = 9, pos = 1),
            row("c|main", order = 12, pos = 2),
        ),
        rev = "1a2b3c4d",
    )

    /**
     * One row up the SCREEN is however many stored positions lie between the
     * two. Sending -1 blindly would swap with whatever sits at order 8 -- on
     * this machine usually a place kept for a device that is switched off, so
     * the press moved nothing visible at all.
     */
    @Test
    fun aMoveIsTheDistanceToTheNeighbouringDrawnRow() {
        assertEquals(-9, orderDeltaForVisibleMove(threeRows, "b|main", -1))
        assertEquals(3, orderDeltaForVisibleMove(threeRows, "b|main", 1))
    }

    @Test
    fun thereIsNoMovePastEitherEnd() {
        assertNull(orderDeltaForVisibleMove(threeRows, "a|main", -1))
        assertNull(orderDeltaForVisibleMove(threeRows, "c|main", 1))
        assertNull(orderDeltaForVisibleMove(threeRows, "nosuch|main", -1))
    }

    /** A hidden row is not on screen, so it is never the neighbour a move swaps with. */
    @Test
    fun aHiddenRowIsNotANeighbour() {
        val view = MixerView(
            rows = listOf(
                row("a|main", order = 0, pos = 0),
                row("decoy|main", order = 1, pos = -1, hidden = true),
                row("b|main", order = 2, pos = 1),
            ),
            rev = "1a2b3c4d",
        )
        assertEquals(-2, orderDeltaForVisibleMove(view, "b|main", -1))
    }
}
