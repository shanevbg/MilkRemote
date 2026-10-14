package com.sheinsez.mdropdx12.remote.network.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.sheinsez.mdropdx12.remote.network.vban.VbanCodec
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * How an address was learned, which is also how much it can be trusted.
 *
 * A BEACON address is the source address of a UDP packet that actually
 * arrived, so it is proof of a working path in the direction that matters. An
 * MDNS address is whatever the PC's responder chose to publish, and Windows
 * publishes every address bound to the host -- including one left on an
 * adapter that is no longer carrying traffic. A PC running a Hyper-V external
 * switch over its Wi-Fi adapter holds exactly that: two addresses on the same
 * subnet, one of them a black hole.
 */
enum class DiscoverySource { Beacon, Mdns, Vban }

/**
 * What kind of program answered.
 *
 * It matters because they are reached differently and do different jobs: the
 * visualizer over its TCP remote, the mixer over VBAN. With MDropDX12's TCP
 * server switched off, the mixer is the only thing on the LAN that answers
 * at all -- and it is the one that is always running, where the visualizer
 * comes and goes.
 */
enum class ServerKind { Visualizer, Mixer }

data class DiscoveredServer(
    val name: String,
    val host: String,
    val port: Int,
    val version: String = "",
    val pid: String = "",
    val source: DiscoverySource = DiscoverySource.Mdns,
    val kind: ServerKind = ServerKind.Visualizer,
)

private const val BEACON_PORT = 9271

class MdnsDiscovery(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var beaconJob: Job? = null
    private var vbanJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _servers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val servers: StateFlow<List<DiscoveredServer>> = _servers

    /**
     * [probeVban] BROADCASTS A VBAN PING0, AND IS NOT FREE. Ask for it only
     * when a mixer is what is being looked for.
     *
     * A PING0 registers this phone as a peer on every VBAN server that hears
     * it, and mdxmixer's open-subscribe hatch grants audio to any peer that
     * pings -- unconditionally, with no way for the prober to decline. So a
     * throwaway discovery socket is answered with the full 48 kHz stereo
     * feed, about 1.5 Mbit/s, for as long as it lives.
     *
     * That is how this came to matter: the TCP reconnect loop called
     * discovery after every failed attempt, and with the visualizer's own
     * server switched off it never stopped failing -- so the phone broadcast
     * a probe every few seconds for ever, and was sent a SECOND copy of the
     * audio it was already playing, over the same Wi-Fi link, competing with
     * the real session for airtime. Heard as stuttering, with both ends
     * looking healthy because each was.
     *
     * The reconnect loop is hunting a visualizer on TCP. It has no use for a
     * mixer, so it asks for no probe.
     */
    fun startDiscovery(probeVban: Boolean = false) {
        stopDiscovery()

        // Method 1: mDNS (Android NSD)
        startNsdDiscovery()

        // Method 2: UDP beacon listener (reliable fallback)
        startBeaconListener()

        // Method 3: a VBAN identification broadcast, which is the only way to
        // find mdxmixer -- it advertises nothing of its own, but every VBAN
        // server answers PING0 and names itself in the reply.
        if (probeVban) startVbanProbe()
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            try { nsdManager.stopServiceDiscovery(it) } catch (_: Exception) {}
        }
        discoveryListener = null
        beaconJob?.cancel()
        beaconJob = null
        vbanJob?.cancel()
        vbanJob = null
    }

    private fun startNsdDiscovery() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val server = DiscoveredServer(
                            name = info.serviceName,
                            host = info.host.hostAddress ?: return,
                            port = info.port,
                            version = info.attributes["version"]?.decodeToString() ?: "",
                            pid = info.attributes["pid"]?.decodeToString() ?: "",
                            source = DiscoverySource.Mdns,
                        )
                        addServer(server)
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                _servers.value = _servers.value.filter { it.name != serviceInfo.serviceName }
            }
        }
        discoveryListener = listener
        try {
            nsdManager.discoverServices("_milkwave._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
            // NSD may fail on some devices — beacon is the fallback
        }
    }

    private fun startBeaconListener() {
        beaconJob = scope.launch {
            try {
                val socket = DatagramSocket(BEACON_PORT)
                socket.soTimeout = 5000 // 5s timeout for periodic check of cancellation
                socket.broadcast = true
                val buf = ByteArray(512)

                while (isActive) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val message = String(packet.data, 0, packet.length, Charsets.UTF_8)

                        // Format: "MDROP_BEACON|<name>|<tcpPort>|<pid>"
                        val parts = message.split("|")
                        if (parts.size >= 4 && parts[0] == "MDROP_BEACON") {
                            val server = DiscoveredServer(
                                name = parts[1],
                                host = packet.address.hostAddress ?: continue,
                                port = parts[2].toIntOrNull() ?: continue,
                                pid = parts[3],
                                source = DiscoverySource.Beacon,
                            )
                            addServer(server)
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        // Normal — just loop and check isActive
                    }
                }
                socket.close()
            } catch (_: Exception) {
                // Port in use or permission denied — NSD is the primary method
            }
        }
    }

    /**
     * Broadcast a VBAN PING0 and take the identification replies.
     *
     * One shot rather than a listener: there is nothing to subscribe to, and
     * a server that does not answer within the window is not reachable
     * anyway. The reply carries the program's own name, so a mixer and a
     * visualizer are told apart by what they say rather than by guessing
     * from the port.
     */
    private fun startVbanProbe() {
        vbanJob = scope.launch {
            val sock = runCatching {
                DatagramSocket().apply { broadcast = true; soTimeout = 400 }
            }.getOrNull() ?: return@launch
            try {
                val req = VbanCodec.buildPing0Request("VBAN Service", 0xC1AUL.toLong())
                for (addr in broadcastTargets()) {
                    runCatching {
                        sock.send(
                            DatagramPacket(req, req.size, addr, VbanCodec.DEFAULT_PORT),
                        )
                    }
                }
                val buf = ByteArray(VbanCodec.MAX_PACKET)
                val until = System.currentTimeMillis() + 2000
                while (isActive && System.currentTimeMillis() < until) {
                    val dp = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(dp)
                    } catch (_: java.net.SocketTimeoutException) {
                        continue
                    } catch (_: Exception) {
                        break
                    }
                    val p = VbanCodec.parse(buf, dp.length) ?: continue
                    val id = VbanCodec.parsePing0Reply(p) ?: continue
                    val host = dp.address?.hostAddress ?: continue
                    val name = id.deviceName.ifBlank { id.hostName.ifBlank { host } }
                    addServer(
                        DiscoveredServer(
                            name = name,
                            host = host,
                            port = VbanCodec.DEFAULT_PORT,
                            source = DiscoverySource.Vban,
                            // Named by the program itself. Anything that is
                            // not the mixer is treated as a visualizer, which
                            // is the conservative way round: a stranger on
                            // the network is not handed the stream settings.
                            kind = if (name.contains("mdxmixer", ignoreCase = true)) {
                                ServerKind.Mixer
                            } else {
                                ServerKind.Visualizer
                            },
                        ),
                    )
                }
            } finally {
                runCatching { sock.close() }
            }
        }
    }

    /**
     * 255.255.255.255 plus every interface's own broadcast address. The
     * limited broadcast alone does not leave some Android builds, and the
     * directed one does not exist until an interface has an address.
     */
    private fun broadcastTargets(): List<InetAddress> {
        val out = mutableListOf<InetAddress>()
        runCatching { out += InetAddress.getByName("255.255.255.255") }
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    ia.broadcast?.let { out += it }
                }
            }
        }
        return out
    }

    private fun addServer(server: DiscoveredServer) {
        val current = _servers.value
        // Update existing or add new (dedup by host:port)
        val existing = current.indexOfFirst { it.host == server.host && it.port == server.port }
        _servers.value = if (existing >= 0) {
            // Both methods report the same PC, and often the same endpoint of
            // it. Once a beacon has arrived FROM an address, a later mDNS
            // record for the same address must not downgrade what is known
            // about it -- the packet is the stronger evidence and it does not
            // stop being true. Every other field still takes the newer value.
            val merged = if (current[existing].source == DiscoverySource.Beacon) {
                server.copy(source = DiscoverySource.Beacon)
            } else {
                server
            }
            current.toMutableList().apply { set(existing, merged) }
        } else {
            current + server
        }
    }
}
