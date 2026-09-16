package com.sheinsez.mdropdx12.remote.network

import com.sheinsez.mdropdx12.remote.network.discovery.DiscoveredServer
import com.sheinsez.mdropdx12.remote.network.discovery.DiscoverySource
import com.sheinsez.mdropdx12.remote.network.discovery.ServerCandidates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The real arrangement these rules exist for: one PC, one service name, two
 * addresses on the same subnet. `.97` is the lease left on a Wi-Fi adapter
 * whose traffic a Hyper-V external switch took over -- routable, and a black
 * hole. `.99` is the switch, and the only one that answers.
 *
 * Windows publishes both over mDNS because DnsServiceRegister is handed no
 * address; only `.99` can ever be a beacon source, because a beacon is a
 * packet that arrived.
 */
class ServerCandidatesTest {

    private val dead = DiscoveredServer(
        name = "STUDIO", host = "192.168.0.97", port = 9270,
        source = DiscoverySource.Mdns,
    )
    private val live = DiscoveredServer(
        name = "STUDIO", host = "192.168.0.99", port = 9270,
        source = DiscoverySource.Beacon,
    )

    @Test
    fun `a beacon address outranks a published one`() {
        val ranked = ServerCandidates.rank(listOf(dead, live), serverName = "STUDIO")
        assertEquals(listOf("192.168.0.99", "192.168.0.97"), ranked.map { it.host })
    }

    /** Discovery order must not decide it — mDNS often answers first. */
    @Test
    fun `the beacon wins from either input order`() {
        assertEquals(
            "192.168.0.99",
            ServerCandidates.rank(listOf(live, dead)).first().host,
        )
        assertEquals(
            "192.168.0.99",
            ServerCandidates.rank(listOf(dead, live)).first().host,
        )
    }

    @Test
    fun `an endpoint that already failed drops behind an untried one`() {
        val failed = setOf(ServerCandidates.endpointKey(live))
        val ranked = ServerCandidates.rank(listOf(dead, live), "STUDIO", failed)
        assertEquals(listOf("192.168.0.99", "192.168.0.97"), ranked.map { it.host }.reversed())
    }

    /**
     * The case that was actually broken: the stored address is the dead one,
     * it fails, and the next attempt must not be the same address again.
     */
    @Test
    fun `next skips the address that just failed`() {
        val failed = setOf(ServerCandidates.endpointKey(dead))
        val next = ServerCandidates.next(
            servers = listOf(dead, live),
            serverName = "STUDIO",
            failed = failed,
            current = ServerCandidates.endpointKey(dead),
        )
        assertEquals("192.168.0.99", next?.host)
    }

    /**
     * With nothing untried left, a failed address comes back rather than the
     * loop having nowhere to go — whatever broke it may since have been fixed.
     */
    @Test
    fun `a sole failed address is offered again rather than nothing`() {
        val failed = setOf(ServerCandidates.endpointKey(dead))
        val next = ServerCandidates.next(listOf(dead), "STUDIO", failed, null)
        assertEquals("192.168.0.97", next?.host)
    }

    @Test
    fun `nothing discovered means nothing to try`() {
        assertNull(ServerCandidates.next(emptyList(), "STUDIO", emptySet(), null))
    }

    /** The PC being talked to comes first; the rest stay on the list behind it. */
    @Test
    fun `the named server outranks a stranger even when the stranger has a beacon`() {
        val stranger = DiscoveredServer(
            name = "LAPTOP", host = "192.168.0.40", port = 9270,
            source = DiscoverySource.Beacon,
        )
        val ranked = ServerCandidates.rank(listOf(stranger, dead), serverName = "STUDIO")
        assertEquals(listOf("192.168.0.97", "192.168.0.40"), ranked.map { it.host })
    }

    /** An unknown name must not narrow anything away. */
    @Test
    fun `with no name known every address is still a candidate`() {
        val ranked = ServerCandidates.rank(listOf(dead, live), serverName = null)
        assertEquals(2, ranked.size)
        assertEquals("192.168.0.99", ranked.first().host)
    }

    @Test
    fun `an endpoint key is host and port together`() {
        assertEquals("192.168.0.99:9270", ServerCandidates.endpointKey(live))
        assertEquals(
            ServerCandidates.endpointKey("192.168.0.99", 9270),
            ServerCandidates.endpointKey(live),
        )
        // Same host, different port, is a different endpoint.
        assertEquals(
            false,
            ServerCandidates.endpointKey("192.168.0.99", 9271) ==
                ServerCandidates.endpointKey(live),
        )
    }
}
