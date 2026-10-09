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
import java.math.BigInteger
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * Built-in HTTP file server, ported from AIOPE's file server. Serves a directory over the local
 * network so other devices can download files from the phone.
 *
 * Supports:
 * - Read-only file serving with MIME detection and directory listing.
 * - Uploads (POST) streamed to disk with a size cap.
 * - Optional HTTPS via a self-signed certificate.
 * - Optional PIN protection gating access.
 *
 * This is a lightweight single-threaded server intended for simple LAN sharing. A full
 * foreground-service implementation is a larger follow-up.
 */
class FileServerToolProvider(private val context: Context) : ToolProvider {

    private var server: SimpleHttpServer? = null

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.fileServerEnabled) return emptyList()
        return listOf(
            ToolDefinition(
                function = ToolFunction(
                    name = "file_server_start",
                    description = "Start an HTTP(S) file server on the local network serving a " +
                        "directory. Supports uploads, optional HTTPS, and optional PIN protection. " +
                        "Returns the URL other devices can use.",
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
                            "allow_upload" to ToolProperty(
                                "boolean",
                                "Allow clients to upload files (default false).",
                            ),
                            "https" to ToolProperty(
                                "boolean",
                                "Serve over HTTPS with a self-signed certificate (default false).",
                            ),
                            "pin" to ToolProperty(
                                "string",
                                "Optional PIN required to access the server. When set, clients " +
                                    "must pass ?pin=... to access files.",
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
        val allowUpload = (args["allow_upload"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
        val https = (args["https"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
        val pin = (args["pin"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        val dir = File(path)
        if (!dir.isDirectory) return@withContext error("Not a directory: $path")
        if (server?.isRunning == true) {
            return@withContext error("File server is already running. Stop it first.")
        }
        val newServer = SimpleHttpServer(
            root = dir,
            port = port,
            allowUpload = allowUpload,
            https = https,
            pin = pin,
        )
        val started = newServer.start()
        if (!started) return@withContext error("Failed to start server on port $port")
        server = newServer
        val ip = localIpAddress()
        val scheme = if (https) "https" else "http"
        buildJsonObject {
            put("type", "file_server_start")
            put("status", "running")
            put("url", "$scheme://$ip:$port/")
            put("path", dir.absolutePath)
            put("port", port)
            put("https", https)
            put("allow_upload", allowUpload)
            if (pin != null) put("pin_required", true)
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
        val scheme = if (s.https) "https" else "http"
        buildJsonObject {
            put("type", "file_server_status")
            put("status", "running")
            put("url", "$scheme://${localIpAddress()}:${s.port}/")
            put("path", s.root.absolutePath)
            put("https", s.https)
            put("allow_upload", s.allowUpload)
            if (s.pin != null) put("pin_required", true)
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
 * Single-threaded HTTP(S) file server. Serves files from a root directory with MIME detection,
 * directory listing, optional uploads, optional HTTPS (self-signed), and optional PIN protection.
 * Runs on a background thread.
 */
internal class SimpleHttpServer(
    val root: File,
    val port: Int,
    val allowUpload: Boolean = false,
    val https: Boolean = false,
    val pin: String? = null,
) {
    @Volatile
    var isRunning: Boolean = false
        private set

    private var thread: Thread? = null
    private var serverSocket: java.net.ServerSocket? = null

    /** Max upload size (2 GB, matching AIOPE's cap). */
    private val maxUploadBytes = 2L * 1024 * 1024 * 1024

    fun start(): Boolean {
        return try {
            serverSocket = if (https) {
                createSslServerSocket()
            } else {
                java.net.ServerSocket(port)
            }
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

    private fun createSslServerSocket(): SSLServerSocket {
        val keyStore = java.security.KeyStore.getInstance("PKCS12")
        keyStore.load(null, null)
        val keyPair = generateSelfSignedKeyPair()
        val cert = generateSelfSignedCertificate(keyPair)
        keyStore.setKeyEntry(
            "agora",
            keyPair.private,
            charArrayOf("agora".toCharArray().let { it }),
            arrayOf(cert),
        )
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, "agora".toCharArray())
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, null, null)
        val socket = sslContext.serverSocketFactory.createServerSocket(port) as SSLServerSocket
        socket.useClientMode = false
        return socket
    }

    private fun generateSelfSignedKeyPair(): java.security.KeyPair {
        val generator = java.security.KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        return generator.generateKeyPair()
    }

    /**
     * Builds a self-signed X.509 certificate with BouncyCastle. `sun.security.x509` is not part of
     * the Android SDK, so this mirrors the WebUI's `WebUiCertificate.kt` approach. The certificate
     * is trusted by fingerprint, not by a CA, so a short validity is fine.
     */
    private fun generateSelfSignedCertificate(
        keyPair: java.security.KeyPair,
    ): java.security.cert.X509Certificate {
        val now = System.currentTimeMillis()
        val validity = 365L * 24 * 60 * 60 * 1000
        val subject = X500Name("CN=Agora File Server, O=Agora, C=US")
        val names = GeneralNames(
            arrayOf(
                GeneralName(GeneralName.dNSName, "localhost"),
                GeneralName(GeneralName.iPAddress, "127.0.0.1"),
            ),
        )
        val holder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(128, SecureRandom()).abs().add(BigInteger.ONE),
            Date(now),
            Date(now + validity),
            subject,
            keyPair.public,
        )
            .addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
            .addExtension(
                Extension.extendedKeyUsage,
                false,
                ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth),
            )
            .addExtension(Extension.subjectAlternativeName, false, names)
            .build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private))
        return JcaX509CertificateConverter().getCertificate(holder)
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
            client.soTimeout = 10000
            val reader = client.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val rawPath = parts[1]
            // Parse query string for ?pin=...
            val (pathPart, query) = rawPath.split("?", limit = 2).let { it[0] to (it.getOrNull(1) ?: "") }
            if (pin != null && !query.contains("pin=$pin")) {
                writeResponse(client, 401, "text/plain", "Unauthorized")
                return
            }
            when (method) {
                "GET" -> handleGet(client, pathPart)
                "POST" -> if (allowUpload) handlePost(client, pathPart, reader) else writeResponse(client, 405, "text/plain", "Method Not Allowed")
                else -> writeResponse(client, 405, "text/plain", "Method Not Allowed")
            }
        } catch (_: Exception) {
            // Best-effort; a malformed request just closes the connection.
        } finally {
            runCatching { client.close() }
        }
    }

    private fun handleGet(client: java.net.Socket, path: String) {
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
    }

    private fun handlePost(
        client: java.net.Socket,
        path: String,
        reader: java.io.BufferedReader,
    ) {
        // Read headers to find Content-Length.
        var contentLength = 0L
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(':').trim().toLongOrNull() ?: 0L
            }
        }
        if (contentLength <= 0 || contentLength > maxUploadBytes) {
            writeResponse(client, 413, "text/plain", "Payload Too Large")
            return
        }
        val decoded = java.net.URLDecoder.decode(path, "UTF-8").removePrefix("/")
        val target = File(root, decoded).canonicalFile
        if (!target.path.startsWith(root.canonicalPath)) {
            writeResponse(client, 403, "text/plain", "Forbidden")
            return
        }
        target.parentFile?.mkdirs()
        try {
            val output = target.outputStream()
            try {
                val buffer = ByteArray(8192)
                var remaining = contentLength
                while (remaining > 0) {
                    val read = reader.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    remaining -= read
                }
            } finally {
                output.close()
            }
            writeResponse(client, 201, "text/plain", "Created")
        } catch (_: Exception) {
            writeResponse(client, 500, "text/plain", "Internal Server Error")
        }
    }

    private fun writeResponse(client: java.net.Socket, code: Int, mime: String, body: String) {
        writeResponse(client, code, mime, body.toByteArray(Charsets.UTF_8))
    }

    private fun writeResponse(client: java.net.Socket, code: Int, mime: String, body: ByteArray) {
        val status = when (code) {
            200 -> "OK"
            201 -> "Created"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            413 -> "Payload Too Large"
            500 -> "Internal Server Error"
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