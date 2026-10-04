package com.pizzashop.pos

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.SocketTimeoutException

/**
 * Finds the POS server on the Wi-Fi without typing its address: shouts "PIZZAPOS-DISCOVER?" (UDP port 47310)
 * and every POS server answers with its name and web port. Must run on a background thread.
 */
object DiscoveryClient {
    const val PORT = 47310
    private const val MAGIC = "PIZZAPOS-DISCOVER?"

    class Found(val host: String, val port: Int, val name: String) {
        val address: String get() = "$host:$port"
        val label: String get() = "$name ($host:$port)"
    }

    fun find(timeoutMs: Int = 2500): List<Found> {
        val found = LinkedHashMap<String, Found>()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(null as SocketAddress?)       // not bound yet, so reuseAddress can be set first
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(0))
            socket.broadcast = true

            val ask = MAGIC.toByteArray(Charsets.UTF_8)
            for (target in broadcastAddresses()) {
                try {
                    socket.send(DatagramPacket(ask, ask.size, target, PORT))
                } catch (e: Exception) {
                    // this network does not allow that address, try the next one
                }
            }

            val buffer = ByteArray(512)
            val end = System.currentTimeMillis() + timeoutMs
            while (true) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) break
                socket.soTimeout = left.toInt()
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    break
                }
                try {
                    val json = JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
                    if (json.optString("app") != "pizza-pos") continue
                    val host = packet.address.hostAddress ?: continue
                    val port = json.optInt("port", 3000)
                    if (port < 1 || port > 65535) continue
                    found["$host:$port"] = Found(host, port, json.optString("name", "Pizza POS").ifEmpty { "Pizza POS" })
                } catch (e: Exception) {
                    // some other device answered with something else, ignore it
                }
            }
        } catch (e: Exception) {
            // no Wi-Fi or no permission: nothing found
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
                // already closed
            }
        }
        return found.values.toList()
    }

    /** The broadcast address of every active network (like 192.168.1.255) plus the general 255.255.255.255. */
    private fun broadcastAddresses(): List<InetAddress> {
        val list = ArrayList<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val b = ia.broadcast
                    if (b != null && !list.contains(b)) list.add(b)
                }
            }
        } catch (e: Exception) {
            // fall back to the general address only
        }
        try {
            list.add(InetAddress.getByName("255.255.255.255"))
        } catch (e: Exception) {
            // cannot happen for a literal address
        }
        return list
    }
}
