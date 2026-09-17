package app.codua2a

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.Socket
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

/** Offline HTTPS fixture, packaged only in the instrumentation APK. */
class ProtocolFixture(assets: AssetManager) : AutoCloseable {
    private val server: SSLServerSocket
    val port: Int get() = server.localPort
    init {
        val password = "test-fixture-only".toCharArray()
        val keys = KeyStore.getInstance("PKCS12").apply { assets.open("fixture.p12").use { load(it, password) } }
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }
        val ssl = SSLContext.getInstance("TLS").apply { init(factory.keyManagers, null, null) }
        server = ssl.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        Thread {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                Thread { try { serve(socket) } catch (_: Exception) { socket.close() } }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }
    }
    private fun serve(socket: Socket) = socket.use {
        socket.soTimeout = 10000
        val input = socket.getInputStream().buffered()
        fun line(): String {
            val out = StringBuilder()
            while (true) { val b = input.read(); check(b >= 0); if (b == 10) return out.toString().trimEnd('\r'); out.append(b.toChar()); check(out.length < 16000) }
        }
        val target = line().split(' ')[1]
        var length = 0
        while (true) { val header = line(); if (header.isEmpty()) break; if (header.startsWith("content-length:", true)) length = header.substringAfter(':').trim().toInt() }
        check(length in 0..8 * 1024 * 1024)
        val body = ByteArray(length); var offset = 0
        while (offset < length) { val n = input.read(body, offset, length - offset); check(n > 0); offset += n }
        val request = JSONObject(String(body, Charsets.UTF_8))
        val output = socket.getOutputStream()
        fun reply(status: Int, type: String, content: String, keep: Boolean = false) {
            val data = content.toByteArray(Charsets.UTF_8)
            val framing = if (keep) "Connection: keep-alive\r\n" else "Content-Length: ${data.size}\r\nConnection: close\r\n"
            output.write("HTTP/1.1 $status OK\r\nContent-Type: $type\r\nMcp-Session-Id: fixture-session\r\n$framing\r\n".toByteArray())
            output.write(data); output.flush()
            if (keep) input.read() // Client must finish on SSE response, not wait for us to close.
        }
        if (target == "/v1/messages") {
            val events = listOf(
                """{"type":"message_start","message":{"id":"fixture","type":"message","role":"assistant","content":[],"model":"fixture","usage":{"input_tokens":10,"output_tokens":0}}}""",
                """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你好 Android 😀"}}""",
                """{"type":"content_block_stop","index":0}""",
                """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":4}}""",
                """{"type":"message_stop"}"""
            ).joinToString("") { "event: ${JSONObject(it).getString("type")}\ndata: $it\n\n" }
            reply(200, "text/event-stream", events)
        } else {
            val method = request.getString("method")
            if (method.startsWith("notifications/")) { reply(202, "application/json", ""); return@use }
            val result = when (method) {
                "initialize" -> JSONObject().put("protocolVersion", "2025-06-18").put("capabilities", JSONObject().put("tools", JSONObject())).put("serverInfo", JSONObject().put("name", "fixture").put("version", "1"))
                "tools/list" -> JSONObject().put("tools", JSONArray().put(JSONObject().put("name", "echo").put("inputSchema", JSONObject().put("type", "object"))))
                "tools/call" -> JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "MCP 你好 😀")))
                else -> JSONObject()
            }
            val response = JSONObject().put("jsonrpc", "2.0").put("id", request.get("id")).put("result", result).toString()
            if (method == "tools/call") reply(200, "text/event-stream", "event: message\ndata: $response\n\n", true)
            else reply(200, "application/json", response)
        }
    }
    override fun close() { server.close() }
}
