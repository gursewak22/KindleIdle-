package com.kindleidle.host

import com.kindleidle.host.net.HttpServer
import com.kindleidle.host.net.Request
import com.kindleidle.host.net.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * The HTTP layer, driven over real sockets.
 *
 * This exists because of a bug that reached the field: the phone answered its
 * own browser but nothing else on the network -- clean TCP connect, no
 * response, eventual reset. That is what a server does when it accepts a
 * connection and never gets around to it, so these tests go at the connection
 * accounting rather than at the routes.
 */
class HttpServerTest {

    private var server: HttpServer? = null
    private val sockets = mutableListOf<Socket>()

    @After
    fun tearDown() {
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        server?.stop()
        server = null
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun start(
        firstByteTimeoutMs: Int = 10_000,
        keepAliveIdleMs: Int = 15_000,
        maxConnections: Int = 64,
        handler: (Request) -> Response
    ): Int {
        val port = freePort()
        server = HttpServer(port, firstByteTimeoutMs, keepAliveIdleMs, maxConnections, handler)
            .also { it.start() }
        return port
    }

    /** One request and its reply, over a fresh connection. */
    private fun request(port: Int, path: String = "/", timeoutMs: Int = 4000): String? {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            socket.soTimeout = timeoutMs
            socket.getOutputStream().write(
                "GET $path HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".toByteArray()
            )
            socket.getOutputStream().flush()
            return try {
                socket.getInputStream().readBytes()
                    .toString(Charsets.ISO_8859_1)
                    .takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Opens a connection and deliberately leaves it hanging. */
    private fun holdOpen(port: Int, sendRequest: Boolean) {
        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", port), 2000)
        if (sendRequest) {
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()
        }
        sockets.add(socket)
    }

    @Test
    fun `answers a simple request`() {
        val port = start { Response(204) }
        val reply = request(port)
        assertTrue("no reply at all", reply != null)
        assertTrue("unexpected status line: $reply", reply!!.startsWith("HTTP/1.1 204"))
    }

    @Test
    fun `answers several requests on one keep-alive connection`() {
        val port = start { Response(200).text("ok") }

        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 4000)
            socket.soTimeout = 4000
            val out = socket.getOutputStream()
            val input = socket.getInputStream()

            repeat(3) { i ->
                out.write("GET /$i HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                out.flush()
                val buf = ByteArray(256)
                val n = input.read(buf)
                val reply = String(buf, 0, maxOf(n, 0), Charsets.ISO_8859_1)
                assertTrue("request $i got: $reply", reply.startsWith("HTTP/1.1 200"))
            }
        }
    }

    /**
     * Sixty connections opened and closed in turn is well past the cap. If a
     * slot is not released when a connection ends, this stops answering
     * partway through.
     */
    @Test
    fun `survives more sequential connections than the connection cap`() {
        val port = start { Response(204) }
        for (i in 1..60) {
            assertTrue("stopped answering at connection $i", request(port) != null)
        }
    }

    /**
     * The one that matters for the bug in the field: clients that connect,
     * ask, and walk away without reading the answer -- a browser tab closed, a
     * Kindle asleep, a curl timing out.
     */
    @Test
    fun `keeps answering after clients abandon their connections`() {
        val port = start { Response(204) }

        repeat(40) { holdOpen(port, sendRequest = true) }

        val reply = request(port)
        assertTrue("server stopped answering after 40 abandoned connections", reply != null)
        assertTrue(reply!!.startsWith("HTTP/1.1 204"))
    }

    /** Connections that open and then say nothing at all. */
    @Test
    fun `keeps answering while silent connections are held open`() {
        val port = start { Response(204) }

        repeat(40) { holdOpen(port, sendRequest = false) }

        val reply = request(port)
        assertTrue("server stopped answering while 40 silent connections were open", reply != null)
        assertTrue(reply!!.startsWith("HTTP/1.1 204"))
    }

    /**
     * At the cap the answer must still be an answer. Closing the socket in
     * silence sends an RST, which from the client is indistinguishable from a
     * server that has crashed -- and sent us chasing routers and power
     * management for an evening when the server was merely full.
     */
    @Test
    fun `says 503 at the cap rather than closing in silence`() {
        val port = start(firstByteTimeoutMs = 30_000, maxConnections = 2) { Response(204) }

        repeat(2) { holdOpen(port, sendRequest = false) }

        val reply = request(port, timeoutMs = 3000)
        assertTrue("expected a refusal, got nothing", reply != null)
        assertTrue("expected 503, got: $reply", reply!!.startsWith("HTTP/1.1 503"))
    }

    /**
     * And full has to be temporary. Connections that die without saying so
     * must age out on their own, or one burst of them closes the server for
     * good -- which is exactly what happened on the phone.
     */
    @Test
    fun `recovers on its own once dead connections age out`() {
        val port = start(firstByteTimeoutMs = 600, maxConnections = 2) { Response(204) }

        repeat(2) { holdOpen(port, sendRequest = false) }
        assertTrue(
            "should have been full",
            request(port, timeoutMs = 3000)!!.startsWith("HTTP/1.1 503")
        )

        // Past the first-byte window the silent connections are dropped.
        Thread.sleep(1500)

        val reply = request(port, timeoutMs = 3000)
        assertTrue("did not recover", reply != null)
        assertTrue("expected 204 after recovery, got: $reply", reply!!.startsWith("HTTP/1.1 204"))
    }

    @Test
    fun `reports the client address to the handler`() {
        var seen: String? = null
        val port = start { req ->
            seen = req.remoteIp
            Response(204)
        }
        request(port)
        assertEquals("127.0.0.1", seen)
    }
}
