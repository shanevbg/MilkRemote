package com.sheinsez.mdropdx12.remote.network.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket

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
enum class DiscoverySource { Beacon, Mdns }

data class DiscoveredServer(
    val name: String,
    val host: String,
    val port: Int,
    val version: String = "",
    val pid: String = "",
    val source: DiscoverySource = DiscoverySource.Mdns,
)

private const val BEACON_PORT = 9271

class MdnsDiscovery(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var beaconJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _servers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val servers: StateFlow<List<DiscoveredServer>> = _servers

    fun startDiscovery() {
        stopDiscovery()

        // Method 1: mDNS (Android NSD)
        startNsdDiscovery()

        // Method 2: UDP beacon listener (reliable fallback)
        startBeaconListener()
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            try { nsdManager.stopServiceDiscovery(it) } catch (_: Exception) {}
        }
        discoveryListener = null
        beaconJob?.cancel()
        beaconJob = null
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
