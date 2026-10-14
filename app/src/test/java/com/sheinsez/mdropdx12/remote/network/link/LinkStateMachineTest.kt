package com.sheinsez.mdropdx12.remote.network.link

import com.sheinsez.mdropdx12.remote.network.ConnectionState
import com.sheinsez.mdropdx12.remote.network.vban.FwdState
import com.sheinsez.mdropdx12.remote.network.vban.LostReason
import com.sheinsez.mdropdx12.remote.network.vban.VbanSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one table every screen reads instead of combining four signals itself.
 */
class LinkStateMachineTest {

    private fun fwd(
        enabled: Boolean = true,
        pid: Int = 4242,
        connected: Boolean = true,
        launching: Boolean = false,
        autolaunch: Boolean = true,
        learned: Boolean = true,
    ) = FwdState(
        enabled = enabled,
        targetExe = "mdropdx12.exe",
        pid = pid,
        connected = connected,
        launching = launching,
        autolaunch = autolaunch,
        learned = learned,
        subs = 1,
        queued = 0,
        dropped = 0,
        sent = 0,
        received = 0,
        path = "C:\\x\\mdropdx12.exe",
        lastError = "",
    )

    private fun derive(
        session: VbanSessionState = VbanSessionState.Streaming,
        f: FwdState? = fwd(),
        tcp: ConnectionState = ConnectionState.Disconnected,
        unreachable: Boolean = false,
    ) = LinkStateMachine.derive(session, f, tcp, unreachable)

    // ── the everyday state under the premise ─────────────────────────────

    /**
     * The whole point. mdx12's TCP server is off, the mixer is forwarding, and
     * this must read as working -- not as a degraded fallback.
     */
    @Test
    fun `mixer forwarding with no TCP socket is Live`() {
        assertEquals(LinkState.Live(tcp = false), derive())
        assertNull(LinkStateMachine.explain(derive()))
    }

    @Test
    fun `both routes up is Live and says so`() {
        assertEquals(
            LinkState.Live(tcp = true),
            derive(tcp = ConnectionState.Connected),
        )
    }

    // ── the two states the rig starts in ─────────────────────────────────

    /**
     * fwd.enabled defaults false and is only settable at the PC, so this is
     * the first thing a fresh setup produces -- and it will not change on its
     * own, which is why it is a persistent state and not a transient error.
     */
    @Test
    fun `forwarding switched off is MixerOnly however healthy the session looks`() {
        val s = derive(f = fwd(enabled = false))
        assertEquals(LinkState.MixerOnly(FwdBlock.ForwardingOff), s)
        assertTrue(LinkStateMachine.explain(s)!!.contains("mdxmixer"))
    }

    @Test
    fun `no visualizer and nothing to start it is MixerOnly NoTarget`() {
        assertEquals(
            LinkState.MixerOnly(FwdBlock.NoTarget),
            derive(f = fwd(pid = 0, connected = false, autolaunch = false)),
        )
    }

    /**
     * THE BUG THE LIVE SERVER CAUGHT. A forward was accepted and executed
     * against a state record reading connected=0, because the mixer's pipe
     * client connects on demand and MDXM_OK never meant more than "queued".
     * Blocking on it refused every command on a PC that was about to work.
     */
    @Test
    fun `connected=0 does not block a command`() {
        assertNull(LinkStateMachine.blockFor(fwd(connected = false)))
        assertEquals(LinkState.Live(tcp = false), derive(f = fwd(connected = false)))
    }

    /**
     * And blocking on `launching` broke autolaunch outright -- the whole point
     * of which is that a command arrives, finds nothing running, and starts it.
     */
    @Test
    fun `a PC starting the visualizer still takes commands`() {
        assertNull(LinkStateMachine.blockFor(fwd(pid = 0, launching = true)))
        assertEquals(
            LinkState.Live(tcp = false),
            derive(f = fwd(pid = 0, launching = true)),
        )
    }

    /** Autolaunch on, nothing running: the command is what starts it. */
    @Test
    fun `nothing running but autolaunch armed is deliverable`() {
        assertNull(LinkStateMachine.blockFor(fwd(pid = 0, autolaunch = true, learned = true)))
    }

    /** Willing to launch, with no idea what to launch. */
    @Test
    fun `autolaunch with no learned path is NoLaunchPath`() {
        assertEquals(
            FwdBlock.NoLaunchPath,
            LinkStateMachine.blockFor(fwd(pid = 0, autolaunch = true, learned = false)),
        )
    }

    /**
     * The live rig's actual state, which must read as working: forwarding on,
     * a resolved pid, autolaunch armed, and connected=0.
     */
    @Test
    fun `the rig's real state is Live`() {
        assertEquals(
            LinkState.Live(tcp = false),
            derive(f = fwd(pid = 15460, connected = false, autolaunch = true, learned = true)),
        )
    }

    /**
     * An older mixer answers `unknown verb` and never sends a state record at
     * all, which needs a different sentence from one that answered "off".
     */
    @Test
    fun `no forwarding record is TooOld, not ForwardingOff`() {
        val s = derive(f = null)
        assertEquals(LinkState.MixerOnly(FwdBlock.TooOld), s)
        assertTrue(LinkStateMachine.explain(s)!!.contains("too old"))
    }

    // ── the case the session state cannot see ────────────────────────────

    /**
     * The session reports Streaming for ever once the PC goes away -- it is
     * left only by the user stopping it or a fresh auth record. So an
     * unreachable mixer looks perfectly healthy from the session alone.
     */
    @Test
    fun `unreachable is Stale even though the session still says Streaming`() {
        val s = derive(unreachable = true)
        assertEquals(LinkState.Stale, s)
        assertTrue(LinkStateMachine.explain(s)!!.contains("last thing"))
    }

    /**
     * And it is checked BEFORE the forwarding record, because a stale record
     * is exactly what an unreachable mixer leaves behind. Reading it would
     * report Live from something that arrived before the PC vanished.
     */
    @Test
    fun `an unreachable mixer does not report Live from its last good record`() {
        assertEquals(LinkState.Stale, derive(f = fwd(enabled = true), unreachable = true))
    }

    // ── TCP carries everything, so it outranks a forwarding block ────────

    /**
     * A mixer that will not forward is not a blocked link when the
     * visualizer's own socket is up -- it is simply not the route in use.
     */
    @Test
    fun `a live TCP socket overrides every forwarding block`() {
        for (f in listOf(
            fwd(enabled = false),
            fwd(pid = 0, autolaunch = false),
            fwd(pid = 0, autolaunch = true, learned = false),
            null,
        )) {
            assertEquals(
                "with TCP up this should be Live",
                LinkState.Live(tcp = true),
                derive(f = f, tcp = ConnectionState.Connected),
            )
        }
    }

    @Test
    fun `TCP alone with no mixer session at all is how the app always worked`() {
        assertEquals(
            LinkState.Live(tcp = true),
            derive(session = VbanSessionState.Idle, f = null, tcp = ConnectionState.Connected),
        )
    }

    @Test
    fun `nothing anywhere is Idle`() {
        assertEquals(
            LinkState.Idle,
            derive(session = VbanSessionState.Idle, f = null),
        )
    }

    // ── on the way, and refused ──────────────────────────────────────────

    @Test
    fun `reaching says wait`() {
        assertEquals(LinkState.Reaching, derive(session = VbanSessionState.Pinging, f = null))
        assertEquals(LinkState.Reaching, derive(session = VbanSessionState.AuthPending, f = null))
        assertNotNull(LinkStateMachine.explain(LinkState.Reaching))
    }

    @Test
    fun `a refused phone is Lost and carries the reason`() {
        val s = derive(session = VbanSessionState.Lost(LostReason.BadPin), f = null)
        assertEquals(LinkState.Lost(LostReason.BadPin.name), s)
    }

    /** A lost mixer session does not matter while the visualizer answers directly. */
    @Test
    fun `a refused phone with TCP up is still Live`() {
        assertEquals(
            LinkState.Live(tcp = true),
            derive(
                session = VbanSessionState.Lost(LostReason.Denied),
                f = null,
                tcp = ConnectionState.Connected,
            ),
        )
    }

    /**
     * The header's Disconnect stops the session, and a stopped session is
     * nobody refusing anything. Read as Lost, the header turned red and said
     * the mixer was not accepting this phone, about a phone that had just
     * asked to leave.
     */
    @Test
    fun `a session stopped on purpose is Idle, not refused`() {
        assertEquals(
            LinkState.Idle,
            derive(session = VbanSessionState.Lost(LostReason.Stopped), f = null),
        )
    }

    @Test
    fun `a stopped session with TCP up is still Live`() {
        assertEquals(
            LinkState.Live(tcp = true),
            derive(
                session = VbanSessionState.Lost(LostReason.Stopped),
                f = null,
                tcp = ConnectionState.Connected,
            ),
        )
    }

    @Test
    fun `a connecting TCP socket is not a connected one`() {
        for (t in listOf(ConnectionState.Connecting, ConnectionState.AuthPending)) {
            assertEquals(LinkState.Live(tcp = false), derive(tcp = t))
        }
    }

    // ── is there anything to disconnect from ─────────────────────────────

    /** Nothing up and nothing trying: the header offers Connect, not the X. */
    @Test
    fun `nothing up and nothing trying is not engaged`() {
        assertFalse(
            LinkStateMachine.engaged(LinkState.Idle, ConnectionState.Disconnected, tcpRetrying = false),
        )
    }

    /**
     * Idle hides the TCP reconnect loop. With no mixer session the link reads
     * Idle while the socket cycles Connecting -> Disconnected every few
     * seconds, and that retrying is exactly what the X is there to stop.
     */
    @Test
    fun `a TCP socket retrying behind Idle is engaged`() {
        assertTrue(
            LinkStateMachine.engaged(LinkState.Idle, ConnectionState.Disconnected, tcpRetrying = true),
        )
        for (t in listOf(ConnectionState.Connecting, ConnectionState.AuthPending)) {
            assertTrue(t.name, LinkStateMachine.engaged(LinkState.Idle, t, tcpRetrying = false))
        }
    }

    /** Connected, reaching, refused or stale: every one of them can be stopped. */
    @Test
    fun `every state but Idle is engaged`() {
        for (s in listOf(
            LinkState.Live(tcp = true),
            LinkState.Live(tcp = false),
            LinkState.Reaching,
            LinkState.Stale,
            LinkState.Lost(LostReason.Timeout.name),
            LinkState.MixerOnly(FwdBlock.NoTarget),
        )) {
            assertTrue(
                s.toString(),
                LinkStateMachine.engaged(s, ConnectionState.Disconnected, tcpRetrying = false),
            )
        }
    }

    /** Every blocked arm must give someone something to do. */
    @Test
    fun `every MixerOnly reason has a sentence`() {
        for (b in FwdBlock.entries) {
            val text = LinkStateMachine.explain(LinkState.MixerOnly(b))
            assertNotNull(b.name, text)
            assertTrue(b.name, text!!.length > 20)
        }
    }
}
