package com.newoether.agora.tool

import android.content.Context
import android.net.wifi.WifiManager
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections

/**
 * LAN network scanner, ported from AIOPE's network scanner. Discovers live hosts on the local
 * subnet via ICMP ping, then optionally performs TCP port scanning with banner grabbing.
 *
 * This is a permission-light implementation intended for auditing your own network. Port scanning
 * is opt-in via the `ports` argument and is bounded to a small set of common ports by default.
 */
class NetworkScannerToolProvider(private val context: Context) : ToolProvider {

    /** Common service ports probed when `ports` is not specified. */
    private val defaultPorts = listOf(22, 80, 443, 445, 3389, 8080, 8443)

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.networkScanEnabled) return emptyList()
        return listOf(
            ToolDefinition(
                function = ToolFunction(
                    name = "network_scan",
                    description = "Scan the local network for live hosts. Discovers the local " +
                        "subnet and pings each address to find responsive devices. Optionally " +
                        "performs TCP port scanning with banner grabbing on each live host. " +
                        "Returns the gateway, local IP, and a list of reachable hosts with their " +
                        "open ports and banners. Use for auditing your own network.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "timeout_ms" to ToolProperty(
                                "integer",
                                "Per-host ping timeout in milliseconds (default 1000).",
                            ),
                            "ports" to ToolProperty(
                                "array",
                                "Optional list of TCP ports to scan on each live host. " +
                                    "Defaults to common service ports (22, 80, 443, 445, 3389, " +
                                    "8080, 8443).",
                                ToolProperty("integer", "A TCP port number."),
                            ),
                            "port_timeout_ms" to ToolProperty(
                                "integer",
                                "Per-port connection timeout in milliseconds (default 500).",
                            ),
                            "banner" to ToolProperty(
                                "boolean",
                                "Attempt banner grabbing on open ports (default true).",
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    override fun handles(name: String): Boolean = name == "network_scan"

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String {
        if (!ctx.networkScanEnabled) return error("Network scanner is disabled")
        val args = runCatching {
            Json.parseToJsonElement(arguments.ifBlank { "{}" }).jsonObject
        }.getOrNull() ?: Json.parseToJsonElement("{}").jsonObject
        val timeoutMs = ((args["timeout_ms"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 1000)
            .coerceIn(200, 5000)
        val portTimeoutMs = ((args["port_timeout_ms"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 500)
            .coerceIn(100, 5000)
        val grabBanner = (args["banner"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: true
        val ports = (args["ports"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
            ?.filter { it in 1..65535 }
            ?.distinct()
            ?.take(50)
            ?: defaultPorts

        return withContext(Dispatchers.IO) {
            val localIp = localIpAddress()
            val gateway = gatewayAddress()
            val subnet = subnetFromIp(localIp)
            if (subnet == null) {
                return@withContext error("Could not determine local subnet")
            }
            val hosts = scanSubnet(subnet, timeoutMs)
            val hostDetails = hosts.map { host ->
                val openPorts = scanPorts(host, ports, portTimeoutMs, grabBanner)
                buildJsonObject {
                    put("ip", host)
                    putJsonArray("ports") {
                        openPorts.forEach { (port, banner) ->
                            add(
                                buildJsonObject {
                                    put("port", port)
                                    banner?.let { put("banner", it) }
                                },
                            )
                        }
                    }
                }
            }
            buildJsonObject {
                put("type", "network_scan")
                put("local_ip", localIp)
                gateway?.let { put("gateway", it) }
                put("subnet", subnet)
                putJsonArray("hosts") {
                    hostDetails.forEach { add(it) }
                }
            }.toString()
        }
    }

    private fun localIpAddress(): String = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .firstOrNull { it.isUp && !it.isLoopback }
            ?.inetAddresses
            ?.toList()
            ?.firstOrNull { it is java.net.Inet4Address }
            ?.hostAddress
            ?: "127.0.0.1"
    }.getOrDefault("127.0.0.1")

    private fun gatewayAddress(): String? = runCatching {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val dhcp = wifi.dhcpInfo
        if (dhcp.gateway == 0) null
        else String.format(
            "%d.%d.%d.%d",
            dhcp.gateway and 0xff,
            dhcp.gateway shr 8 and 0xff,
            dhcp.gateway shr 16 and 0xff,
            dhcp.gateway shr 24 and 0xff,
        )
    }.getOrNull()

    private fun subnetFromIp(ip: String): String? {
        val parts = ip.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return null
        return "${parts[0]}.${parts[1]}.${parts[2]}."
    }

    private fun scanSubnet(subnet: String, timeoutMs: Int): List<String> {
        val hosts = mutableListOf<String>()
        for (i in 1..254) {
            val ip = "$subnet$i"
            runCatching {
                val reachable = InetAddress.getByName(ip).isReachable(timeoutMs)
                if (reachable) hosts.add(ip)
            }
        }
        return hosts
    }

    /** Returns a list of (port, banner) pairs for open ports on [host]. */
    private fun scanPorts(
        host: String,
        ports: List<Int>,
        timeoutMs: Int,
        grabBanner: Boolean,
    ): List<Pair<Int, String?>> {
        val open = mutableListOf<Pair<Int, String?>>()
        for (port in ports) {
            val banner = probePort(host, port, timeoutMs, grabBanner)
            if (banner != null || isPortOpen(host, port, timeoutMs)) {
                open.add(port to banner)
            }
        }
        return open
    }

    private fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            true
        }
    }.getOrDefault(false)

    /** Attempts a banner grab; returns null if the port is closed or no banner is read. */
    private fun probePort(host: String, port: Int, timeoutMs: Int, grabBanner: Boolean): String? {
        if (!grabBanner) return null
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.soTimeout = timeoutMs
                val buffer = ByteArray(256)
                val read = socket.getInputStream().read(buffer)
                if (read > 0) {
                    String(buffer, 0, read, Charsets.ISO_8859_1).trim().take(256)
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private fun error(message: String): String = "Error: $message"
}