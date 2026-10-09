package com.newoether.agora.tool

import android.content.Context
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
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/**
 * Built-in HTTP file server, ported from AIOPE's file server. Serves a directory over the local
 * network so other devices can download files from the phone.
 *
 * This is a lightweight single-threaded server intended for simple LAN sharing. It serves files
 * read-only with basic MIME detection. A full foreground-service implementation with uploads,
 * HTTPS, and PIN protection is a larger follow-up; this provides the core serve capability.
 */
class FileServerToolProvider(private val context: Context) : ToolProvider {

    private var server: SimpleHttpServer? = null

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.fileServerEnabled) return emptyList()
        return listOf(
            ToolDefinition(
                function = ToolFunction(
                    name = "file_server_start",
                    description = "Start an HTTP file server on the local network serving a " +
                        "directory. Returns the URL other devices can use to download files.",
                    parameters = ToolParameters(
                        properties = mapOf(
                            "path" to ToolProperty(
                                "string",
                                "Absolute path of the directory to serve.",
                            ),
                            "port" to ToolProperty(
                                "integer",
                                "Port to listen on (default 8080).",
                            ),
                        ),
                        required = listOf("path"),
                    ),
                ),
            ),
            ToolDefinition(
                function = ToolFunction(
                    name = "file_server_stop",
                    description = "Stop the running file server.",
                    parameters = ToolParameters(properties = emptyMap()),
                ),
            ),
            ToolDefinition(
                function = ToolFunction(
                    name = "file_server_status",
                    description = "Get the current file server status and URL.",
                    parameters = ToolParameters(properties = emptyMap()),
                ),
            ),
        )
    }

    override fun handles(name: String): Boolean = name in TOOL_NAMES

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String {
        if (!ctx.fileServerEnabled) return error("File server is disabled")
        return when (name) {
            "file_server_start" -> executeStart(arguments)
            "file_server_stop" -> executeStop()
            "file_server_status" -> executeStatus()
            else -> error("Unknown file server tool: $name")
        }
    }

    private suspend fun executeStart(arguments: String): String = withContext(Dispatchers.IO) {
        val args = runCatching {
            Json.parseToJsonElement(arguments.ifBlank { "{}" }).jsonObject
        }.getOrNull() ?: return@withContext error("Arguments are not a JSON object.")
        val path = (args["path"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: return@withContext error("path is required")
        val port = ((args["port"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 8080)
            .coerceIn(1024, 65535)
        val dir = File(path)
        if (!dir.isDirectory) return@withContext error("Not a directory: $path")
        if (server?.isRunning == true) {
            return@withContext error("File server is already running. Stop it first.")
        }
        val newServer = SimpleHttpServer(dir, port)
        val started = newServer.start()
        if (!started) return@withContext error("Failed to start server on port $port")
        server = newServer
        val ip = localIpAddress()
        buildJsonObject {
            put("type", "file_server_start")
            put("status", "running")
            put("url", "http://$ip:$port/")
            put("path", dir.absolutePath)
            put("port", port)
        }.toString()
    }

    private suspend fun executeStop(): String = withContext(Dispatchers.IO) {
        val s = server
        if (s == null || !s.isRunning) return@withContext error("File server is not running")
        s.stop()
        server = null
        buildJsonObject {
            put("type", "file_server_stop")
            put("status", "stopped")
        }.toString()
    }

    private suspend fun executeStatus(): String = withContext(Dispatchers.IO) {
        val s = server
        if (s == null || !s.isRunning) {
            return@withContext buildJsonObject {
                put("type", "file_server_status")
                put("status", "stopped")
            }.toString()
        }
        buildJsonObject {
            put("type", "file_server_status")
            put("status", "running")
            put("url", "http://${localIpAddress()}:${s.port}/")
            put("path", s.root.absolutePath)
        }.toString()
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

    private fun error(message: String): String = "Error: $message"

    private companion object {
        const val START = "file_server_start"
        const val STOP = "file_server_stop"
        const val STATUS = "file_server_status"
        val TOOL_NAMES = setOf(START, STOP, STATUS)
    }
}

/**
 * Minimal single-threaded HTTP file server. Serves files read-only from a root directory with
 * basic MIME detection and directory listing. Runs on a background thread.
 */
internal class SimpleHttpServer(
    val root: File,
    val port: Int,
) {
    @Volatile
    var isRunning: Boolean = false
        private set

    private var thread: Thread? = null
    private var serverSocket: java.net.ServerSocket? = null

    fun start(): Boolean {
        return try {
            serverSocket = java.net.ServerSocket(port)
            isRunning = true
            thread = Thread { serveLoop() }.apply {
                isDaemon = true
                name = "agora-file-server"
                start()
            }
            true
        } catch (_: Exception) {
            isRunning = false
            false
        }
    }

    fun stop() {
        isRunning = false
        runCatching { serverSocket?.close() }
        thread?.interrupt()
        thread = null
        serverSocket = null
    }

    private fun serveLoop() {
        val socket = serverSocket ?: return
        while (isRunning) {
            try {
                val client = socket.accept()
                handleClient(client)
            } catch (_: Exception) {
                if (!isRunning) break
            }
        }
    }

    private fun handleClient(client: java.net.Socket) {
        try {
            client.soTimeout = 5000
            val reader = client.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1]
            if (method != "GET") {
                writeResponse(client, 405, "text/plain", "Method Not Allowed")
                return
            }
            val decoded = java.net.URLDecoder.decode(path, "UTF-8").removePrefix("/")
            val file = File(root, decoded).canonicalFile
            if (!file.path.startsWith(root.canonicalPath)) {
                writeResponse(client, 403, "text/plain", "Forbidden")
                return
            }
            if (file.isDirectory) {
                val listing = file.listFiles()?.joinToString("\n") { it.name } ?: ""
                writeResponse(client, 200, "text/plain", listing)
            } else if (file.isFile) {
                val bytes = file.readBytes()
                writeResponse(client, 200, mimeType(file.name), bytes)
            } else {
                writeResponse(client, 404, "text/plain", "Not Found")
            }
        } catch (_: Exception) {
            // Best-effort; a malformed request just closes the connection.
        } finally {
            runCatching { client.close() }
        }
    }

    private fun writeResponse(client: java.net.Socket, code: Int, mime: String, body: String) {
        writeResponse(client, code, mime, body.toByteArray(Charsets.UTF_8))
    }

    private fun writeResponse(client: java.net.Socket, code: Int, mime: String, body: ByteArray) {
        val status = when (code) {
            200 -> "OK"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Error"
        }
        val header = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: $mime\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        client.getOutputStream().apply {
            write(header.toByteArray(Charsets.UTF_8))
            write(body)
            flush()
        }
    }

    private fun mimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js" -> "application/javascript"
        "json" -> "application/json"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "pdf" -> "application/pdf"
        "txt", "md" -> "text/plain"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }
}