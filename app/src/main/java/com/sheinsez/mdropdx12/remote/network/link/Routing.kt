package com.sheinsez.mdropdx12.remote.network.link

import com.sheinsez.mdropdx12.remote.network.RelayPolicy

/** Which wire carried a command, or brought a record back. */
enum class Carrier {
    /** Through mdxmixer, as MDXM_FWD. */
    Relay,

    /** Down MDropDX12's own TCP socket. */
    Tcp,
}

sealed interface Route {
    data class Via(val carrier: Carrier) : Route

    /** Nothing can carry it. [why] is a sentence for a person. */
    data class Refused(val why: String) : Route
}

/**
 * Which wire a command takes.
 *
 * THE CENTRAL CLAIM: a command the relay can carry goes through the relay even
 * when MDropDX12's own TCP socket is up and idle.
 *
 * That looks wasteful and is the opposite. Routing by "whatever happens to be
 * connected" means the relay path -- the raw-remainder slice, the firehose
 * filter, the per-tick re-subscribe -- is cold code on every day the TCP
 * server is running, and warm only on the day it is switched off for good.
 * That is the shape of a path which is broken the one time it is needed. Route
 * by verb and every button press exercises it.
 *
 * TCP keeps the handful of verbs whose replies do not fit a 1436-byte
 * datagram, and nothing else. With TCP down, a mixer that cuts a large reply
 * into parts carries most of those too; `parts` is whether this one does.
 *
 * Pure. The decision is one function so it can be argued about in a test
 * rather than discovered on the rig.
 */
object Routing {

    fun route(
        command: String,
        relayUsable: Boolean,
        tcpUp: Boolean,
        parts: Boolean = false,
    ): Route {
        // Neither wire. Said first, and the same for every verb: "too large
        // for the mixer" is a claim about a mixer, and with nothing connected
        // there is no mixer to make it about. It used to be made anyway, to
        // someone whose trouble was the connection.
        if (!relayUsable && !tcpUp) return Route.Refused("No connection to the PC.")

        if (RelayPolicy.travels(command)) {
            if (relayUsable) return Route.Via(Carrier.Relay)
            // The fallback, and it is what makes this safe to ship publicly.
            //
            // On this rig the mixer runs all the time, so the line above is
            // the one that fires and the relay stays the hot path. Someone
            // who has never run the mixer and whose visualizer is listening
            // on its own port must not be told their remote is broken because
            // of a program they do not have -- so TCP carries it.
            return Route.Via(Carrier.Tcp)
        }

        // A reply too large for one datagram, or a subscription whose pushes
        // never reach a relay. The visualizer's own socket carries those
        // whole, so it is taken whenever it is there -- INCLUDING for a reply
        // the mixer could carry in parts. Every part is a packet that has to
        // arrive and nothing is sent twice, so a reply in two parts is lost
        // about twice as often as a packet is, and the socket loses none.
        // The central claim above is about keeping the relay path warm, and
        // it stays warm on every command that fits; it is not a reason to
        // choose the wire that can drop half an answer when the other is up.
        if (tcpUp) return Route.Via(Carrier.Tcp)

        // Relay only. What used to be refused outright now depends on the
        // mixer: one that sends parts carries these, and says so.
        if (RelayPolicy.travels(command, parts)) return Route.Via(Carrier.Relay)
        return Route.Refused(
            RelayPolicy.explain(command, parts) ?: "The visualizer is not connected directly.",
        )
    }

    /**
     * Whether the relay is worth offering a command to.
     *
     * `Live` is the only state in which the mixer will pass one on. A
     * `MixerOnly` has told us why it will not, and `Stale` means it has
     * stopped answering at all -- offering either of those a command would
     * drop it silently, which is the failure this whole design exists to
     * avoid.
     */
    fun relayUsable(state: LinkState): Boolean = state is LinkState.Live
}
