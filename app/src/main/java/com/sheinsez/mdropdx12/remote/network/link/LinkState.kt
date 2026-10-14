package com.sheinsez.mdropdx12.remote.network.link

import com.sheinsez.mdropdx12.remote.network.ConnectionState
import com.sheinsez.mdropdx12.remote.network.vban.FwdState
import com.sheinsez.mdropdx12.remote.network.vban.LostReason
import com.sheinsez.mdropdx12.remote.network.vban.VbanSessionState

/**
 * Why a command cannot reach the visualizer even though the mixer is answering.
 *
 * Each of these is a different thing for a person to go and do, which is the
 * reason they are not one `false`.
 */
enum class FwdBlock {
    /** Switched off at the PC. It defaults off, so this is the first state a fresh setup is in. */
    ForwardingOff,

    /** Forwarding is on, the visualizer is not running, and nothing will start it. */
    NoTarget,

    /** Willing to start the visualizer and no path to start it from. */
    NoLaunchPath,

    /** The mixer does not know the forwarding verbs. An older build. */
    TooOld,
}

/**
 * Can a command reach MDropDX12, and by what route?
 *
 * REPLACES `ConnectionState` AS THE THING SCREENS GATE ON, and is deliberately
 * not a rename of it. Nineteen behaviours in the app test
 * `ConnectionState == Connected`, and every one of them meant "can I reach the
 * visualizer". While mdx12's own TCP socket was the only way in, those were
 * the same question. Once a command can travel through the mixer they are
 * different questions with different answers, and the socket's answer is the
 * wrong one: it reads Disconnected for ever on a PC whose TCP server is
 * switched off, while every button on screen works.
 */
sealed interface LinkState {

    /** Nothing has been tried, or it was stopped on purpose. */
    object Idle : LinkState

    /** A link is being established. Says "wait", not "broken". */
    object Reaching : LinkState

    /**
     * The mixer is there and will not pass a command on. [why] says what to do.
     *
     * A first-class, PERSISTENT state rather than a transient error, because
     * the two commonest values are both settled at the PC and will not change
     * on their own: forwarding defaults off, and `ForwardCommand` ends at
     * `notarget` whenever the visualizer is not running.
     */
    data class MixerOnly(val why: FwdBlock) : LinkState

    /**
     * Commands are getting through.
     *
     * [tcp] says whether the visualizer's own socket is ALSO up, which is what
     * the handful of verbs with replies too large for a datagram need. It is
     * not a measure of health: `Live(tcp = false)` is the ordinary everyday
     * state once the TCP server is off, and must not be drawn as degraded.
     */
    data class Live(val tcp: Boolean) : LinkState

    /**
     * The mixer has stopped answering, so what is on screen is the last thing
     * known rather than what is true.
     *
     * Distinct from [Lost]: nothing has refused anything and no reason was
     * given. The pings simply stopped coming back.
     */
    object Stale : LinkState

    /** The mixer refused this phone, or gave up. */
    data class Lost(val reason: String) : LinkState
}

/**
 * One pure function from the four things that are known to the one thing every
 * screen wants.
 *
 * Pure so the table can be argued about in a test. The alternative -- each
 * screen combining a session state, a forwarding record, a socket state and a
 * watchdog for itself -- is nineteen chances to combine them differently.
 */
object LinkStateMachine {

    fun derive(
        session: VbanSessionState,
        fwd: FwdState?,
        tcp: ConnectionState,
        unreachable: Boolean,
    ): LinkState {
        val tcpUp = tcp == ConnectionState.Connected

        // The visualizer's own socket, with no mixer session at all, is still a
        // working link -- it is how the app worked before any of this, and how
        // it still works for someone who has never run the mixer.
        if (session is VbanSessionState.Idle) {
            return if (tcpUp) LinkState.Live(tcp = true) else LinkState.Idle
        }

        if (session is VbanSessionState.Lost) {
            if (tcpUp) return LinkState.Live(tcp = true)
            // Stopped is this phone's own doing -- the header's Disconnect --
            // and nobody refused anything. As Lost it drew red and said the
            // mixer was not accepting a phone that had just asked to leave.
            return if (session.reason == LostReason.Stopped) LinkState.Idle
            else LinkState.Lost(session.reason.name)
        }

        if (session is VbanSessionState.Pinging || session is VbanSessionState.AuthPending) {
            return if (tcpUp) LinkState.Live(tcp = true) else LinkState.Reaching
        }

        // Streaming. The session is up, so the question is now entirely about
        // what the mixer says it can do with a command.

        // Checked BEFORE the forwarding state, because a stale FwdState is
        // exactly what an unreachable mixer leaves behind -- it would otherwise
        // report Live from a record that arrived before the PC went away.
        if (unreachable) return if (tcpUp) LinkState.Live(tcp = true) else LinkState.Stale

        // No forwarding record at all is not the same as one saying "off". An
        // older mixer answers `unknown verb` and never sends one, and the
        // sentence for that is different.
        if (fwd == null) {
            return if (tcpUp) LinkState.Live(tcp = true) else LinkState.MixerOnly(FwdBlock.TooOld)
        }

        val block = blockFor(fwd)
        return when {
            block == null -> LinkState.Live(tcp = tcpUp)
            // The visualizer's socket still carries everything, so a mixer that
            // will not forward is not a blocked link -- it is just not the
            // route being used.
            tcpUp -> LinkState.Live(tcp = true)
            else -> LinkState.MixerOnly(block)
        }
    }

    /**
     * Null when the mixer will pass a command on.
     *
     * THE BLOCKS ARE EXACTLY THE PC'S OWN REFUSALS, and nothing else. This
     * used to block on `connected` and on `launching` too, which was wrong in
     * a way only the live server showed: a forward was accepted and executed
     * against a state record reading `connected=0`, because the mixer's pipe
     * client connects ON DEMAND and `MDXM_OK` never meant more than "queued".
     *
     * Blocking on it would have refused every command on a PC that was about
     * to work, and broken autolaunch outright -- the entire point of which is
     * that a command arrives, finds nothing running, and starts it. The
     * spec's own failure table says as much: "mdx12 starting (launched or by
     * hand) -> command queued, delivered on connect".
     *
     * So the deliverability question is only ever: will the PC refuse this?
     * Its refusals are `forwarding is off`, `notarget` and `nolaunchpath`;
     * `connected=0` is not among them, and neither is `launching=1`.
     */
    fun blockFor(fwd: FwdState): FwdBlock? = when {
        !fwd.enabled -> FwdBlock.ForwardingOff
        // Nothing running and nothing that will start it.
        fwd.pid == 0 && !fwd.autolaunch -> FwdBlock.NoTarget
        // Willing to start it and no idea what to start.
        fwd.pid == 0 && !fwd.learned -> FwdBlock.NoLaunchPath
        else -> null
    }

    /**
     * What to tell someone holding the phone.
     *
     * Says where to go and what to do, not what the mechanism is. "The pipe is
     * down" is true and useless.
     */
    fun explain(state: LinkState): String? = when (state) {
        is LinkState.Live, LinkState.Idle -> null
        LinkState.Reaching -> "Reaching the PC…"
        LinkState.Stale -> "The PC has stopped answering. This is the last thing it said."
        is LinkState.Lost -> "The mixer is not accepting this phone."
        is LinkState.MixerOnly -> when (state.why) {
            FwdBlock.ForwardingOff ->
                "The mixer is connected but will not pass commands to the visualizer. " +
                    "Turn forwarding on in mdxmixer."
            FwdBlock.NoTarget -> "The visualizer is not running on the PC."
            FwdBlock.NoLaunchPath ->
                "The PC will start the visualizer but does not know where it is."
            FwdBlock.TooOld -> "This mixer is too old to pass commands to the visualizer."
        }
    }

    /**
     * Connected, or trying to be: is there anything for the header's X to
     * stop?
     *
     * Not `state != Idle`. Idle is what [derive] answers whenever the mixer
     * session is idle and the socket is not up, and that includes the TCP
     * reconnect loop cycling Connecting -> Disconnected every few seconds --
     * a phone with no connection at all that is still trying, which is the
     * case the X most needs to cover.
     */
    fun engaged(state: LinkState, tcp: ConnectionState, tcpRetrying: Boolean): Boolean =
        state != LinkState.Idle || tcp != ConnectionState.Disconnected || tcpRetrying
}
