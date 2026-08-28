package com.kindleidle.host.net

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger

/**
 * A small HTTP/1.1 server.
 *
 * Hand-rolled rather than pulled in, for the same reason the Node server has
 * no dependencies (docs/adr/0005): what this app needs from HTTP is two
 * methods, form and JSON bodies, cookies, and connections that are allowed to
 * sit idle for the length of a long-poll. That last one is the awkward
 * requirement for most embedded servers and the reason the timeouts below are
 * spelled out rather than left at their defaults.
 *
 * One thread per connection. With a Kindle, a phone and a spare that is three
 * threads, most of them parked in a poll; [MAX_CONNECTIONS] is the backstop
 * against something on the LAN opening sockets without end.
 */

class Request(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray,
    val remoteIp: String
) {
    fun header(name: String): String? = headers[name.lowercase()]
    val cookies: String? get() = header("cookie")
    fun bodyText(): String = String(body, Charsets.UTF_8)
    fun queryParam(name: String): String? = query[name]
}

class Response(val status: Int) {
    val headers = LinkedHashMap<String, String>()
    var body: ByteArray = EMPTY
        private set

    /** Extra Set-Cookie lines, which may not be folded into one header. */
    val cookies = ArrayList<String>()

    fun header(name: String, value: String) = apply { headers[name] = value }

    fun cookie(value: String) = apply { cookies.add(value) }

    fun body(bytes: ByteArray, contentType: String) = apply {
        body = bytes
        headers["Content-Type"] = contentType
    }

    fun text(value: String, contentType: String = "text/plain; charset=utf-8") =
        body(value.toByteArray(Charsets.UTF_8), contentType)

    fun html(value: String) = text(value, "text/html; charset=utf-8").noStore()

    fun json(value: String) = text(value, "application/json; charset=utf-8").noStore()

    fun noStore() = header("Cache-Control", "no-store")

    companion object {
        private val EMPTY = ByteArray(0)

        fun of(status: Int) = Response(status)

        fun redirect(location: String) =
            Response(303).header("Location", location).noStore()
    }
}

class HttpServer(
    private val port: Int,
    private val firstByteTimeoutMs: Int = FIRST_BYTE_TIMEOUT_MS,
    private val keepAliveIdleMs: Int = KEEP_ALIVE_IDLE_MS,
    private val maxConnections: Int = MAX_CONNECTIONS,
    private val handler: (Request) -> Response
) {

    @Volatile private var socket: ServerSocket? = null
    @Volatile private var running = false
    private var accepter: Thread? = null
    private var workers: ExecutorService? = null
    private val liveConnections = AtomicInteger(0)

    val isRunning: Boolean get() = running

    /** Throws if the port is taken, so the caller can report it. */
    fun start() {
        if (running) return
        val server = ServerSocket(port, BACKLOG, InetAddress.getByName("0.0.0.0"))
        server.reuseAddress = true
        socket = server
        running = true
        workers = Executors.newCachedThreadPool { r ->
            Thread(r, "ki-http").apply { isDaemon = true }
        }
        accepter = Thread({ acceptLoop(server) }, "ki-accept").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (e: IOException) { /* already down */ }
        socket = null
        workers?.shutdownNow()
        workers = null
        accepter = null
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                if (running) continue else break
            }
            if (liveConnections.get() >= maxConnections) {
                // Say so, rather than closing a socket that still has an
                // unread request in it -- that sends an RST, and a client
                // being reset in silence is indistinguishable from a server
                // that has died. It cost a long evening to learn that once.
                refuse(client)
                continue
            }
            liveConnections.incrementAndGet()
            try {
                workers?.execute { serve(client) } ?: client.close()
            } catch (e: Exception) {
                liveConnections.decrementAndGet()
                try { client.close() } catch (e2: IOException) { /* nothing to do */ }
            }
        }
    }

    /** Turns a connection away with an answer, then closes it. */
    private fun refuse(client: Socket) {
        try {
            client.soTimeout = 1000
            client.getOutputStream().apply {
                write(REFUSAL.toByteArray(Charsets.ISO_8859_1))
                flush()
            }
        } catch (e: IOException) {
            // Gone already; the close below is all that is left to do.
        } finally {
            try { client.close() } catch (e: IOException) { /* nothing to do */ }
        }
    }

    private fun serve(client: Socket) {
        try {
            client.tcpNoDelay = true
            val input = BufferedInputStream(client.getInputStream(), 8192)
            val output = BufferedOutputStream(client.getOutputStream(), 8192)
            val ip = client.inetAddress?.hostAddress ?: "unknown"

            var firstRequest = true
            while (running && !client.isClosed) {
                // A connection holds a worker thread and one of the
                // [maxConnections] slots for as long as it is open, so waiting
                // on a silent client is not free the way it is on an
                // event-driven server. A new connection gets a short window to
                // say something; an established one gets a little longer
                // between requests. Neither needs to cover the long-poll hold,
                // which happens inside the handler, not in a socket read.
                client.soTimeout = if (firstRequest) firstByteTimeoutMs else keepAliveIdleMs
                firstRequest = false

                val request = try {
                    readRequest(input, ip)
                } catch (e: SocketTimeoutException) {
                    break
                } catch (e: BadRequest) {
                    writeResponse(output, Response(e.status).text(e.message ?: "bad request"), false)
                    output.flush()
                    break
                } ?: break

                val response = try {
                    handler(request)
                } catch (e: Exception) {
                    android.util.Log.e("KindleIdle", "${request.method} ${request.path}", e)
                    Response(500).text("server error")
                }

                val keepAlive = wantsKeepAlive(request) && running
                writeResponse(output, response, keepAlive)
                output.flush()
                if (!keepAlive) break
            }
        } catch (e: IOException) {
            // A client that walks away mid-poll is ordinary, not an error.
        } finally {
            liveConnections.decrementAndGet()
            try { client.close() } catch (e: IOException) { /* nothing to do */ }
        }
    }

    private class BadRequest(val status: Int, message: String) : Exception(message)

    private fun readRequest(input: InputStream, ip: String): Request? {
        val requestLine = readLine(input, MAX_LINE) ?: return null
        if (requestLine.isEmpty()) return null

        val parts = requestLine.split(' ')
        if (parts.size < 3) throw BadRequest(400, "bad request line")
        val method = parts[0].uppercase()
        val target = parts[1]

        val headers = HashMap<String, String>()
        var headerBytes = 0
        while (true) {
            val line = readLine(input, MAX_LINE) ?: throw BadRequest(400, "truncated headers")
            if (line.isEmpty()) break
            headerBytes += line.length
            if (headerBytes > MAX_HEADERS || headers.size >= MAX_HEADER_COUNT) {
                throw BadRequest(431, "headers too large")
            }
            val colon = line.indexOf(':')
            if (colon < 1) throw BadRequest(400, "bad header")
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            // Cookies are the only header here that legitimately repeats.
            headers[name] = headers[name]?.let { "$it; $value" } ?: value
        }

        // No chunked request bodies: nothing this server accepts sends one,
        // and quietly mis-reading a body is worse than refusing it.
        headers["transfer-encoding"]?.let {
            if (it.contains("chunked", ignoreCase = true)) {
                throw BadRequest(411, "chunked request bodies are not accepted")
            }
        }

        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0) throw BadRequest(400, "bad content-length")
        if (length > MAX_BODY) throw BadRequest(413, "body too large")

        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw BadRequest(400, "truncated body")
            read += n
        }

        val q = target.indexOf('?')
        val path = decodePath(if (q < 0) target else target.substring(0, q))
        val query = if (q < 0) emptyMap() else parseQuery(target.substring(q + 1))

        return Request(method, path, query, headers, body, ip)
    }

    private fun wantsKeepAlive(request: Request): Boolean {
        val connection = request.header("connection")?.lowercase()
        if (connection != null && connection.contains("close")) return false
        return true
    }

    private fun writeResponse(out: OutputStream, response: Response, keepAlive: Boolean) {
        val sb = StringBuilder(256)
        sb.append("HTTP/1.1 ").append(response.status).append(' ')
            .append(reason(response.status)).append("\r\n")

        for ((name, value) in response.headers) {
            if (name.equals("Content-Length", true) || name.equals("Connection", true)) continue
            sb.append(name).append(": ").append(value).append("\r\n")
        }
        for (cookie in response.cookies) sb.append("Set-Cookie: ").append(cookie).append("\r\n")

        sb.append("Content-Length: ").append(response.body.size).append("\r\n")
        sb.append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n")
        sb.append("\r\n")

        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (response.body.isNotEmpty()) out.write(response.body)
    }

    companion object {
        /** Matches POLL_MS in server/index.js. */
        const val POLL_MS = 25_000L

        /**
         * How long a freshly accepted connection has to send its request
         * line, and how long an established one may sit between requests.
         *
         * The Node server sets keepAliveTimeout to POLL_MS + 15s, but it can
         * afford to: an idle socket there costs nothing. Here it costs a
         * thread and a connection slot, so idle connections are shed several
         * times faster and a burst of dead ones drains in seconds.
         */
        private const val FIRST_BYTE_TIMEOUT_MS = 10_000
        private const val KEEP_ALIVE_IDLE_MS = 15_000

        private const val BACKLOG = 32

        /**
         * Threads, so not unlimited -- but high enough that ordinary use
         * cannot reach it. A Kindle, a phone and a laptop hold single figures;
         * anything near this is connections that have died without saying so.
         */
        private const val MAX_CONNECTIONS = 64

        private const val REFUSAL =
            "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        private const val MAX_LINE = 8 * 1024
        private const val MAX_HEADERS = 16 * 1024
        private const val MAX_HEADER_COUNT = 64

        /** Matches MAX_BODY in server/index.js. */
        private const val MAX_BODY = 8 * 1024

        private fun readLine(input: InputStream, max: Int): String? {
            val buf = ByteArrayOutputStream(128)
            while (true) {
                val b = input.read()
                if (b == -1) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
                if (b == '\n'.code) {
                    val s = buf.toString("ISO-8859-1")
                    return if (s.endsWith("\r")) s.dropLast(1) else s
                }
                buf.write(b)
                if (buf.size() > max) throw IOException("line too long")
            }
        }

        fun decodePath(raw: String): String {
            if (raw.indexOf('%') < 0 && raw.indexOf('+') < 0) return raw
            return try {
                // A path is not a query string, so `+` stays a plus rather
                // than becoming a space.
                java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
            } catch (e: Exception) {
                raw
            }
        }

        fun parseQuery(raw: String): Map<String, String> {
            if (raw.isEmpty()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (pair in raw.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                val key = if (eq < 0) pair else pair.substring(0, eq)
                val value = if (eq < 0) "" else pair.substring(eq + 1)
                out[formDecode(key)] = formDecode(value)
            }
            return out
        }

        fun formDecode(raw: String): String = try {
            java.net.URLDecoder.decode(raw, "UTF-8")
        } catch (e: Exception) {
            raw
        }

        private fun reason(status: Int): String = when (status) {
            200 -> "OK"
            204 -> "No Content"
            303 -> "See Other"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            411 -> "Length Required"
            413 -> "Payload Too Large"
            429 -> "Too Many Requests"
            431 -> "Request Header Fields Too Large"
            500 -> "Internal Server Error"
            503 -> "Service Unavailable"
            else -> "OK"
        }
    }
}
