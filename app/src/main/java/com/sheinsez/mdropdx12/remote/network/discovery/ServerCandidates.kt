package com.sheinsez.mdropdx12.remote.network.discovery

/**
 * Which discovered address to try next, and in what order.
 *
 * Both rules here come from one failure mode that took three sessions to pin
 * down. A PC running a Hyper-V external switch over its Wi-Fi adapter holds
 * TWO addresses on the same subnet -- the virtual switch carries the traffic
 * while the physical adapter keeps a lease it no longer answers on. Windows
 * publishes both over mDNS, because `DnsServiceRegister` is handed no address
 * and so advertises everything bound to the host. Android's resolver hands
 * back one of them, and roughly half the time that is the black hole.
 *
 * What made it hard to see is that nothing reports an error: the address is
 * routable, the packets simply go nowhere, so a connection attempt sits there
 * until it times out and the phone tries the very same address again.
 *
 * Hence: a beacon address outranks an mDNS one, because it is the source of a
 * packet that actually arrived rather than a claim about where a PC can be
 * found; and an endpoint that has already failed drops to the back rather than
 * being retried while an untried sibling is sitting there.
 */
object ServerCandidates {

    /** Stable identity of one address, and the key failures are recorded under. */
    fun endpointKey(host: String, port: Int): String = "$host:$port"

    fun endpointKey(server: DiscoveredServer): String = endpointKey(server.host, server.port)

    /**
     * The addresses worth trying, best first.
     *
     * @param serverName the PC already being talked to, if known. Its own
     *   addresses come first; everything else stays on the list behind them
     *   rather than being dropped, because a PC that has been renamed is still
     *   more use than no PC at all.
     * @param failed endpoints tried unsuccessfully since the last connection.
     *   Excluded outright while any untried candidate remains, and appended in
     *   their original order once none does -- a dead address is not worth a
     *   retry ahead of an untried one, but it is worth a retry ahead of
     *   nothing, since whatever broke it may have been fixed.
     */
    fun rank(
        servers: List<DiscoveredServer>,
        serverName: String? = null,
        failed: Set<String> = emptySet(),
    ): List<DiscoveredServer> {
        val ordered = servers.sortedWith(
            compareBy(
                { if (!serverName.isNullOrEmpty() && it.name == serverName) 0 else 1 },
                { if (it.source == DiscoverySource.Beacon) 0 else 1 },
            ),
        )
        val (tried, untried) = ordered.partition { endpointKey(it) in failed }
        return untried + tried
    }

    /**
     * The next address to attempt, or null when discovery has turned up
     * nothing at all.
     *
     * `current` is what is being used now: it is skipped when there is
     * anything else to try, because retrying the address that just failed is
     * the behaviour this exists to replace.
     */
    fun next(
        servers: List<DiscoveredServer>,
        serverName: String? = null,
        failed: Set<String> = emptySet(),
        current: String? = null,
    ): DiscoveredServer? {
        val ranked = rank(servers, serverName, failed)
        return ranked.firstOrNull { endpointKey(it) != current } ?: ranked.firstOrNull()
    }
}
