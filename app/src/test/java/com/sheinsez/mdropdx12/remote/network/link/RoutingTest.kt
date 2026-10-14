package com.sheinsez.mdropdx12.remote.network.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingTest {

    private val ACTIONS = listOf(
        "SIGNAL|NEXT_PRESET",
        "SET_DISPLAY_MODE=2,preset",
        "SET_MIRROR_ENABLED=1",
        "PRESET=C:\\x.milk",
        "DISPLAY_NEXT=2",
        "RAISE_WINDOW",
        "MIXER_SET=personal,0.4",
        "GET_DISPLAY_MODE",
        "GET_VERSION",
    )

    /**
     * Only what the server itself refuses. Each of these was measured
     * answering `MDXM_ERR|msg=toolong` or nothing at all over the live relay
     * -- not merely estimated to be large.
     */
    private val TCP_ONLY = listOf(
        "MIXER_STATE",
        "MIXER_VIEW",
        "MIXER_ORDER",
        "DIAG_MIRRORS",
        "HELP=JSON",
        "MIXER_SUBSCRIBE=1",
        "GET_PRESET_SOURCE",
    )

    /** Batches that measurably arrive, terminator and all. */
    private val BATCHES = listOf(
        "BROWSE_PRESETS=0,60",
        "GET_CHILDREN",
        "GET_PRESET_FOLDERS",
        "GET_AUDIO_DEVICES",
    )

    /**
     * THE ARCHITECTURE'S CENTRAL CLAIM, and the test that pins it.
     *
     * If this ever flips to Tcp the relay becomes cold code that runs only
     * after the TCP server is switched off for good -- which is the one day it
     * must not be the first time it has run.
     */
    @Test
    fun `a relayable command goes through the mixer even with TCP connected`() {
        for (c in ACTIONS + BATCHES) {
            assertEquals(
                c,
                Route.Via(Carrier.Relay),
                Routing.route(c, relayUsable = true, tcpUp = true),
            )
        }
    }

    @Test
    fun `a reply too big for a datagram goes over TCP even with the relay live`() {
        for (c in TCP_ONLY) {
            assertEquals(
                c,
                Route.Via(Carrier.Tcp),
                Routing.route(c, relayUsable = true, tcpUp = true),
            )
        }
    }

    /**
     * What makes this shippable to someone who has never heard of mdxmixer:
     * their visualizer is listening and must simply work.
     */
    @Test
    fun `with no mixer at all everything falls back to TCP`() {
        for (c in ACTIONS + BATCHES + TCP_ONLY) {
            assertEquals(
                c,
                Route.Via(Carrier.Tcp),
                Routing.route(c, relayUsable = false, tcpUp = true),
            )
        }
    }

    @Test
    fun `the everyday state under the premise - relay only`() {
        for (c in ACTIONS + BATCHES) {
            assertEquals(c, Route.Via(Carrier.Relay), Routing.route(c, true, tcpUp = false))
        }
        // And the handful that cannot cross it are refused with a sentence
        // rather than dropped.
        for (c in TCP_ONLY) {
            val r = Routing.route(c, true, tcpUp = false)
            assertTrue(c, r is Route.Refused)
            assertTrue(c, (r as Route.Refused).why.isNotBlank())
        }
    }

    @Test
    fun `nothing anywhere refuses rather than pretending`() {
        for (c in ACTIONS + BATCHES + TCP_ONLY) {
            assertTrue(c, Routing.route(c, relayUsable = false, tcpUp = false) is Route.Refused)
        }
    }

    /**
     * A refusal must say what to DO. The batch and too-big cases carry
     * RelayPolicy's wording, which names the direct connection.
     */
    @Test
    fun `a relay-only refusal explains itself in terms of the visualizer`() {
        val r = Routing.route("MIXER_STATE", true, tcpUp = false) as Route.Refused
        assertTrue(r.why, r.why.contains("too large"))
    }

    // ── a mixer that cuts a large reply into parts ───────────────────────

    /** Refused through an older mixer; carried by one that sends parts. */
    private val LARGE = listOf(
        "DIAG_MIRRORS",
        "DISPLAY_PROFILE_LIST",
        "MIXER_STATE",
        "MIXER_VIEW",
        "MIXER_ORDER",
    )

    /**
     * What this exists for: the visualizer's own server is off, the relay is
     * the only wire, and the Displays tab can still learn what displays exist.
     */
    @Test
    fun `relay only, a large reply takes the relay when the mixer sends parts`() {
        for (c in LARGE) {
            assertEquals(
                c,
                Route.Via(Carrier.Relay),
                Routing.route(c, relayUsable = true, tcpUp = false, parts = true),
            )
        }
    }

    /**
     * Every part is a packet that has to arrive and nothing is sent twice, so
     * a reply in two parts is lost about twice as often as a packet is. The
     * socket loses none. Where both wires are up, a large reply keeps the one
     * that cannot drop half of it.
     */
    @Test
    fun `with TCP up a large reply still takes TCP, parts or not`() {
        for (c in LARGE) {
            assertEquals(
                c,
                Route.Via(Carrier.Tcp),
                Routing.route(c, relayUsable = true, tcpUp = true, parts = true),
            )
        }
    }

    @Test
    fun `parts do not rescue what is too large for them`() {
        for (c in listOf("HELP=JSON", "GET_PRESET_SOURCE", "MIXER_SUBSCRIBE=1")) {
            val r = Routing.route(c, relayUsable = true, tcpUp = false, parts = true)
            assertTrue(c, r is Route.Refused)
        }
    }

    @Test
    fun `a small command routes the same whether the mixer sends parts or not`() {
        for (c in ACTIONS + BATCHES) {
            assertEquals(
                c,
                Routing.route(c, relayUsable = true, tcpUp = true),
                Routing.route(c, relayUsable = true, tcpUp = true, parts = true),
            )
        }
    }

    /** The remedy is at the PC, and the sentence has to say so. */
    @Test
    fun `through a mixer without parts the refusal names the mixer`() {
        val r = Routing.route("DIAG_MIRRORS", relayUsable = true, tcpUp = false) as Route.Refused
        assertTrue(r.why, r.why.contains("mdxmixer"))
    }

    /**
     * "Too large for the mixer" is a claim about a mixer. With no wire up at
     * all there is no mixer to make it about, and the thing to fix is the
     * connection.
     */
    @Test
    fun `with nothing connected the reason given is the connection, not the size`() {
        for (c in LARGE + TCP_ONLY) {
            for (parts in listOf(false, true)) {
                val r = Routing.route(c, relayUsable = false, tcpUp = false, parts = parts)
                assertEquals(c, Route.Refused("No connection to the PC."), r)
            }
        }
    }

    // ── which link states may be offered a command ───────────────────────

    @Test
    fun `only a Live link may be offered a command`() {
        assertTrue(Routing.relayUsable(LinkState.Live(tcp = false)))
        assertTrue(Routing.relayUsable(LinkState.Live(tcp = true)))
    }

    /**
     * Each of these has either told us it will not forward, or stopped
     * answering. Handing one a command drops it with no sign, which is the
     * failure the whole design exists to avoid.
     */
    @Test
    fun `every other link state is not usable`() {
        for (s in listOf(
            LinkState.Idle,
            LinkState.Reaching,
            LinkState.Stale,
            LinkState.Lost("BadPin"),
            LinkState.MixerOnly(FwdBlock.ForwardingOff),
            LinkState.MixerOnly(FwdBlock.NoTarget),
            LinkState.MixerOnly(FwdBlock.NoLaunchPath),
            LinkState.MixerOnly(FwdBlock.TooOld),
        )) {
            assertFalse(s.toString(), Routing.relayUsable(s))
        }
    }

    /**
     * The combination that produces the rig's first weeks: forwarding is off
     * at the PC and the TCP server is off too. Every command must be refused
     * with a reason, never queued into nothing.
     */
    @Test
    fun `forwarding off and no TCP refuses everything`() {
        val usable = Routing.relayUsable(LinkState.MixerOnly(FwdBlock.ForwardingOff))
        for (c in ACTIONS) {
            assertTrue(c, Routing.route(c, usable, tcpUp = false) is Route.Refused)
        }
    }
}
