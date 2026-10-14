package com.sheinsez.mdropdx12.remote.network.vban

/**
 * When is the SOCKET the thing that is broken?
 *
 * [Reachability] can say the mixer has stopped answering, and the session
 * acts on that by dropping to `Pinging` and re-authenticating. That is the
 * right answer to every cause it was written for -- an expired peer, a PC
 * that rebooted, a Doze window -- because in all of those the socket is fine
 * and only the registration behind it was lost.
 *
 * There is one cause it cannot fix, and re-authenticating on a loop is
 * exactly the wrong response to it. Android stamps a datagram socket with the
 * fwmark of the DEFAULT NETWORK AT THE MOMENT IT IS CREATED, and that stamp
 * is permanent. Move the phone from one Wi-Fi network to another -- a hotspot
 * to the house AP -- and the old network's id is gone, but the socket still
 * carries it: every `send` is accepted by the kernel, routed into a table
 * that no longer leads anywhere, and dropped. UDP owes nobody a delivery, so
 * `send` does not throw and nothing is logged.
 *
 * The symptom is a session that pings for ever. It was observed for fifteen
 * minutes: ICMP to the PC answered in 9 ms, the route was right, the app
 * reported no send failures, and not one datagram reached the mixer. The only
 * cure is a NEW socket, which is stamped with the network that now exists.
 *
 * So this is the second-order watchdog: not "is the mixer answering" but
 * "has re-authenticating stopped helping". It runs only once reachability has
 * already given up, which is why its tolerance is counted on top of that one.
 *
 * Pure, so the thresholds are argued about here rather than tuned on the rig.
 */
class RebindPolicy(
    /**
     * Unreachable ticks before the socket itself is suspected.
     *
     * Five, at a 2 s tick, on top of the three [Reachability] already spent:
     * about sixteen seconds before the first rebuild. Deliberately longer
     * than a peer expiry (10 s), because a socket rebuild cannot fix an
     * expired peer and the cheap answer deserves to be tried first and given
     * time to work. Short enough that nobody reaches for Stop and Play.
     */
    private val tolerance: Int = 5,
    /**
     * The ceiling on the backoff, in ticks.
     *
     * Rebuilding repeatedly is cheap but pointless against a PC that is
     * simply switched off, and a new socket every ten seconds all night is
     * noise in the log that will be read as a fault. Forty ticks is about 80
     * seconds between attempts once it is clear nothing is coming back.
     */
    private val ceiling: Int = 40,
) {
    private var ticks = 0
    private var threshold = tolerance

    fun reset() {
        ticks = 0
        threshold = tolerance
    }

    /**
     * A datagram actually arrived from the mixer.
     *
     * THE ONLY THING THAT CLEARS THE BACKOFF, and it has to be, because
     * [Reachability]'s verdict cannot be used for it. A rebind resets that
     * verdict -- it must, or the fresh socket inherits the dead one's debt
     * and is condemned on its first tick -- so for two or three ticks after
     * every rebuild it reports "reachable" on no evidence whatever.
     *
     * Letting that reset the threshold was measured doing exactly what the
     * ceiling exists to prevent: thirteen consecutive rebinds at a flat
     * twelve-second interval, the backoff never once growing, because each
     * rebuild handed back the optimism that wiped it. Contact is the only
     * thing that proves a socket works, so contact is the only thing that
     * forgives one.
     */
    fun onHeard() {
        reset()
    }

    /**
     * One ping tick went by. Returns true if the socket should be rebuilt.
     *
     * [unreachable] is [Reachability]'s verdict, not a raw ping result: a
     * single lost datagram must never reach this, and by the time it is true
     * three in a row already have been.
     */
    fun onTick(unreachable: Boolean): Boolean {
        if (!unreachable) {
            // Stop counting, but KEEP the backoff. This branch runs on the
            // optimism described in onHeard as well as on real contact, and
            // only one of those has earned a fresh start.
            ticks = 0
            return false
        }
        ticks++
        if (ticks < threshold) return false
        ticks = 0
        threshold = (threshold * 2).coerceAtMost(ceiling)
        return true
    }

    /** For a diagnostics line: ticks spent unreachable since the last rebuild. */
    val stalledTicks: Int get() = ticks
}
