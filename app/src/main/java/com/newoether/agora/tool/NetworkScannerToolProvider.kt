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
import java.net.NetworkInterface
import java.util.Collections

/**
 * LAN network scanner, ported from AIOPE's network scanner. Discovers live hosts on the local
 * subnet via ICMP ping and reports IP addresses. This is a lightweight, permission-light
 * implementation intended for auditing your own network.
 *
 * Note: full port/banner scanning requires additional socket work and is intentionally kept
 * minimal here; the core host-discovery sweep is provided.
 */
class NetworkScannerToolProvider(private val context: Context) : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.networkScanEnabled) return emptyList()
        return listOf(
            ToolDefinition(
                function = ToolFunction(
                    name = "network_scan",
                    description = "Scan the local network for live hosts. Discovers the local " +
                        "subnet and pings each address to find responsive devices. Returns the " +
                        "gateway, local IP, and a list of reachable host IPs. Use for auditing " +
                        "your own network.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "timeout_ms" to ToolProperty(
                                "integer",
                                "Per-host ping timeout in milliseconds (default 1000).",
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

        return withContext(Dispatchers.IO) {
            val localIp = localIpAddress()
            val gateway = gatewayAddress()
            val subnet = subnetFromIp(localIp)
            if (subnet == null) {
                return@withContext error("Could not determine local subnet")
            }
            val hosts = scanSubnet(subnet, timeoutMs)
            buildJsonObject {
                put("type", "network_scan")
                put("local_ip", localIp)
                gateway?.let { put("gateway", it) }
                put("subnet", subnet)
                putJsonArray("hosts") {
                    hosts.forEach { add(it) }
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

    private fun error(message: String): String = "Error: $message"
}