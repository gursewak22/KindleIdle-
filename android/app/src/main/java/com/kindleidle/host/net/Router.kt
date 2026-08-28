package com.kindleidle.host.net

import android.content.res.AssetManager
import com.kindleidle.host.core.Auth
import com.kindleidle.host.core.Render
import com.kindleidle.host.core.Scenes
import com.kindleidle.host.core.Store
import java.io.FileNotFoundException

/**
 * The routing table, ported from server/index.js.
 *
 * The two clients are byte-for-byte the ones the Node server serves, so every
 * route they can reach has to behave the same way -- including the parts that
 * exist only to survive the Kindle's browser, like letting through a POST
 * that carries no Origin header at all.
 */
class Router(
    private val assets: AssetManager,
    private val store: Store,
    private val auth: Auth,
    private val render: Render,
    private val scenes: Scenes
) {

    fun handle(req: Request): Response {
        val route = req.path

        // Two things are served without a session: the login form, and the
        // favicon the browser asks for before following the redirect.
        if (route == "/login") {
            if (req.method == "GET") return getLogin(req)
            if (req.method == "POST") return postLogin(req)
        }

        if (route == "/logout" && req.method == "POST") {
            if (!sameOrigin(req)) return Response(403).text("forbidden")
            return Response.redirect("/login").cookie(auth.clearedCookie())
        }

        if (req.method == "GET" && route == "/favicon.ico") return Response(204)

        gate(req, route)?.let { return it }

        if (req.method == "POST" && !sameOrigin(req)) {
            return Response(403).text("forbidden")
        }

        if (route == "/pair" && (req.method == "GET" || req.method == "POST")) {
            return pair(req)
        }

        if (req.method == "GET" && (route == "/" || route == "/index.html")) {
            return Response(200).html(
                render.renderKindlePage(store.getState(), store.getVersion(), readTheme(req))
            )
        }

        if (req.method == "GET" && (route == "/remote" || route == "/remote/")) {
            return Response(200).html(
                render.renderRemotePage(store.getState(), store.getVersion(), readTheme(req))
            )
        }

        if (req.method == "GET" && route == "/api/poll") return poll(req)

        if (req.method == "POST" && route == "/api/action") return action(req)

        if (req.method == "GET") return static(route)

        return Response(405).text("method not allowed")
    }

    /* ---------------------------------------------------------------------
       the gate
    --------------------------------------------------------------------- */

    /** Returns a response when the request must be turned away, else null. */
    private fun gate(req: Request, route: String): Response? {
        if (auth.hasSession(req.cookies)) return null

        // A page request bounces to the login form; a request from one of the
        // two clients gets a 401 it can act on, because a 303 to an HTML page
        // is not something an XHR expecting JSON can do anything with.
        if (wantsJson(req)) {
            return Response(401).json("{\"error\":\"auth\"}")
        }

        val search = if (req.query.isEmpty()) "" else "?" + req.query.entries.joinToString("&") {
            urlEncode(it.key) + "=" + urlEncode(it.value)
        }
        return Response.redirect("/login?next=" + urlEncode(route + search))
    }

    private fun getLogin(req: Request): Response = Response(200).html(
        render.renderLoginPage(
            theme = readTheme(req),
            next = safeNext(req.queryParam("next")),
            locked = auth.lockedFor(req.remoteIp)
        )
    )

    private fun postLogin(req: Request): Response {
        if (!sameOrigin(req)) return Response(403).text("forbidden")

        val form = parseBody(req)
        val next = safeNext(form["next"])

        // Two doors, one counter: the username/password form and the pairing
        // code share the lockout, so alternating between them cannot buy an
        // attacker a fresh set of free tries at either.
        val byCode = (form["code"] ?: "").filter { it.isDigit() }.isNotEmpty()
        val locked = auth.lockedFor(req.remoteIp)

        // A locked-out address is not told whether it guessed right, so the
        // lockout cannot be used as an oracle for one last free check.
        val ok = locked == 0L && (
            if (byCode) auth.redeemPairingCode(form["code"])
            else auth.verifyLogin(form["user"], form["pass"])
            )

        if (!ok) {
            if (locked == 0L) auth.recordFailure(req.remoteIp)
            return Response(if (locked > 0) 429 else 401).html(
                render.renderLoginPage(
                    theme = readTheme(req),
                    next = next,
                    error = if (byCode) "code" else "password",
                    locked = auth.lockedFor(req.remoteIp)
                )
            )
        }

        auth.recordSuccess(req.remoteIp)
        return Response.redirect(next).cookie(auth.sessionCookie())
    }

    /**
     * The pairing desk. GET shows the code in force, minting one only if none
     * is live, so reloading the page does not invalidate a code already
     * written on somebody's hand. POST is the deliberate "give me a new one".
     * Neither puts the code in a URL: it is a credential, and query strings
     * end up in history and logs.
     */
    private fun pair(req: Request): Response {
        if (req.method == "POST") {
            auth.newPairingCode()
            return Response.redirect("/pair")
        }
        val code = auth.currentPairingCode()
        return Response(200).html(
            render.renderPairPage(
                code = code.code,
                expiresAt = code.expiresAt,
                theme = readTheme(req),
                origin = "http://" + (req.header("host") ?: "this server") + "/"
            )
        )
    }

    /* ---------------------------------------------------------------------
       state
    --------------------------------------------------------------------- */

    private fun poll(req: Request): Response {
        val since = req.queryParam("v")?.toIntOrNull()
        val v = store.waitForChange(since, HttpServer.POLL_MS)
        return Response(200).json(
            render.statePayload(store.getState(), v, req.queryParam("for"))
        )
    }

    private fun action(req: Request): Response {
        val body = parseBody(req)
        val ok = applyAction(body)

        if (!wantsJson(req)) {
            // No-JS fallback: plain form posts bounce back to the page they
            // came from.
            return Response.redirect(req.header("referer") ?: "/remote")
        }
        if (!ok) {
            return Response(400).json("{\"error\":\"unknown action\"}")
        }
        return Response(200).json(
            render.statePayload(store.getState(), store.getVersion(), req.queryParam("for"))
        )
    }

    private fun applyAction(body: Map<String, String>): Boolean = when (body["act"]) {
        "add" -> { store.addTodo(body["text"]); true }
        "toggle" -> { store.toggleTodo(body["id"]); true }
        "del" -> { store.deleteTodo(body["id"]); true }
        "clear" -> { store.clearDone(); true }
        // An unknown id is a no-op rather than an error, the same as toggling
        // a todo that has since been deleted; the reply echoes the scene in
        // force.
        "scene" -> { store.setScene(body["id"]); true }
        "sw-start" -> { store.stopwatchStart(); true }
        "sw-stop" -> { store.stopwatchStop(); true }
        "sw-toggle" -> { store.stopwatchToggle(); true }
        "sw-reset" -> { store.stopwatchReset(); true }
        "sw-lap" -> { store.stopwatchLap(); true }
        else -> false
    }

    /* ---------------------------------------------------------------------
       static files

       Served straight out of the app's assets, which are the project's own
       public/ folder wired in by build.gradle.kts rather than copied. Only
       the types the two pages actually ask for are served, so the generated
       scenes.json sitting beside them is not reachable over HTTP.
    --------------------------------------------------------------------- */

    private fun static(route: String): Response {
        val rel = route.trimStart('/')
        if (rel.isEmpty() || rel.contains("..") || rel.startsWith("/")) {
            return Response(403).text("forbidden")
        }
        val type = MIME[rel.substringAfterLast('.', "")] ?: return Response(404).text("not found")

        return try {
            val bytes = assets.open(rel).use { it.readBytes() }
            Response(200)
                .body(bytes, type)
                .header("Cache-Control", "public, max-age=86400")
        } catch (e: FileNotFoundException) {
            Response(404).text("not found")
        } catch (e: Exception) {
            Response(404).text("not found")
        }
    }

    /* ---------------------------------------------------------------------
       request helpers
    --------------------------------------------------------------------- */

    /**
     * Dark mode is a per-device choice, not shared state: the Kindle can stay
     * on paper while the phone goes dark at night. A cookie rather than
     * localStorage so the server can stamp the theme on the first paint -- on
     * e-ink, letting a script correct it afterwards means a white flash first.
     */
    private fun readTheme(req: Request): String? =
        when (Auth.readCookie(req.cookies, "ki_theme")) {
            "dark" -> "dark"
            "light" -> "light"
            else -> null
        }

    private fun parseBody(req: Request): Map<String, String> {
        val contentType = req.header("content-type") ?: ""
        val raw = req.bodyText()
        if (contentType.contains("application/json", ignoreCase = true)) {
            return try {
                val obj = org.json.JSONObject(raw)
                val out = HashMap<String, String>()
                for (key in obj.keys()) out[key] = obj.optString(key)
                out
            } catch (e: Exception) {
                emptyMap()
            }
        }
        return HttpServer.parseQuery(raw)
    }

    private fun wantsJson(req: Request): Boolean =
        (req.header("accept") ?: "").contains("application/json", ignoreCase = true)

    /**
     * Where to send someone back to once they are in. Anything that is not a
     * plain path on this server is dropped rather than corrected: an open
     * redirect is the standard way a login form gets turned into a phishing
     * hop.
     */
    private fun safeNext(value: String?): String {
        val next = value ?: ""
        if (!next.startsWith("/") || next.startsWith("//") || next.startsWith("/\\")) return "/"
        if (next.startsWith("/login") || next.startsWith("/logout")) return "/"
        return next
    }

    /**
     * Cookies ride along with a cross-site form post, so `SameSite=Lax` is the
     * first line and this is the second -- old WebKit, the Kindle's included,
     * predates SameSite entirely and ignores it. A browser that sends `Origin`
     * must send one matching this host; one that sends none (the Kindle, on a
     * same-origin form post) is let through, which is the best a server can do.
     */
    private fun sameOrigin(req: Request): Boolean {
        val origin = req.header("origin") ?: return true
        if (origin == "null") return true
        return try {
            java.net.URL(origin).let { url ->
                val host = if (url.port == -1) url.host else "${url.host}:${url.port}"
                host == req.header("host")
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    companion object {
        private val MIME = mapOf(
            "css" to "text/css; charset=utf-8",
            "js" to "application/javascript; charset=utf-8",
            "svg" to "image/svg+xml",
            "png" to "image/png",
            "ico" to "image/x-icon"
        )
    }
}
