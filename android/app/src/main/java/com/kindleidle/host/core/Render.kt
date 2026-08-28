package com.kindleidle.host.core

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every byte of markup either screen sees, ported from server/render.js.
 *
 * Neither client ever builds markup: the server makes the strings and the
 * client swaps them in (docs/adr/0001). That is why this file is long and the
 * two page scripts are not, and it is what lets the Kindle's ancient WebKit
 * run the same UI as a current phone browser.
 */
class Render(private val scenes: Scenes) {

    /** Cache-buster for the stylesheets, fixed for the life of the process. */
    val assetV: String = java.lang.Long.toString(System.currentTimeMillis(), 36)

    /* ---------------------------------------------------------------------
       pieces
    --------------------------------------------------------------------- */

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun pad(n: Int): String = if (n < 10) "0$n" else n.toString()

    private fun fmtElapsed(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = ((total % 3600) / 60).toInt()
        val s = (total % 60).toInt()
        return (if (h > 0) "$h:${pad(m)}" else m.toString()) + ":" + pad(s)
    }

    private fun todoItem(t: Todo): String =
        "<li class=\"t${if (t.done) " done" else ""}\">" +
            "<a class=\"box\" href=\"#\" data-act=\"toggle\" data-id=\"${esc(t.id)}\"></a>" +
            "<span class=\"tx\">${esc(t.text)}</span>" +
            "<a class=\"del\" href=\"#\" data-act=\"del\" data-id=\"${esc(t.id)}\">&#215;</a>" +
            "</li>"

    private fun renderTodos(state: State, hint: String): String {
        if (state.todos.isEmpty()) {
            return "<li class=\"empty\">Nothing on the list.<br><span>$hint</span></li>"
        }
        val pending = state.todos.filter { !it.done }
        val done = state.todos.filter { it.done }
        var html = pending.joinToString("") { todoItem(it) }
        if (done.isNotEmpty()) {
            html += "<li class=\"sep\">Done &#183; ${done.size}</li>" +
                done.joinToString("") { todoItem(it) }
        }
        return html
    }

    private fun renderUpNext(state: State): String {
        val pending = state.open
        if (pending.isEmpty()) return "<li class=\"empty\">All clear.</li>"
        var html = pending.take(UP_NEXT_LIMIT).joinToString("") { "<li>${esc(it.text)}</li>" }
        val rest = pending.size - UP_NEXT_LIMIT
        if (rest > 0) html += "<li class=\"more\">and $rest more</li>"
        return html
    }

    private fun renderLaps(state: State): String {
        val laps = state.stopwatch.laps
        if (laps.isEmpty()) return ""
        return "<li class=\"lhead\"><b>#</b><span>Split</span><em>Total</em></li>" +
            laps.withIndex().joinToString("") { (i, lap) ->
                "<li><b>${laps.size - i}</b><span>${fmtElapsed(lap.split)}</span>" +
                    "<em>${fmtElapsed(lap.at)}</em></li>"
            }
    }

    /**
     * The Kindle's empty-list hint points at the phone; the phone's points at
     * the input right above it. Everything else about the two lists is the
     * same.
     */
    private fun emptyHint(audience: String?): String =
        if (audience == "remote") "Add one above." else "Add tasks from your phone at /remote"

    /**
     * The rendered pieces plus the JSON the clients boot from. Both pages need
     * the same strings twice over -- once inlined into the markup, once in
     * `window.BOOT` -- so they are built once and carried together rather than
     * serialised and parsed straight back out.
     */
    private class Boot(
        val json: String,
        val todos: String,
        val upNext: String,
        val laps: String,
        val openCount: Int
    )

    private fun boot(state: State, version: Int, audience: String?): Boot {
        val todos = renderTodos(state, emptyHint(audience))
        val upNext = renderUpNext(state)
        val laps = renderLaps(state)
        val sw = state.stopwatch

        // Key order follows server/render.js. JSONObject keeps insertion
        // order on Android, so the two hosts emit the same bytes.
        val json = JSONObject().apply {
            put("v", version)
            put("now", System.currentTimeMillis())
            put("todos", todos)
            put("upNext", upNext)
            put("laps", laps)
            put("scene", state.scene)
            put("openCount", state.open.size)
            put("sw", JSONObject().apply {
                put("running", sw.running)
                put("startedAt", sw.startedAt ?: JSONObject.NULL)
                put("accumulated", sw.accumulated)
            })
        }.toString()

        return Boot(json, todos, upNext, laps, state.open.size)
    }

    /**
     * One payload shape for both the long-poll and the action responses:
     * neither client ever builds markup, it only swaps in strings the server
     * made.
     */
    fun statePayload(state: State, version: Int, hint: String?): String =
        boot(state, version, hint).json

    /* ---------------------------------------------------------------------
       Both screens are the same three panels over the same nav, so they share
       one body. They differ only in the stylesheet, the add form, and which
       panel opens first.
    --------------------------------------------------------------------- */

    private fun addForm(): String =
        """  <form id="add" method="post" action="/api/action" autocomplete="off">
    <input type="hidden" name="act" value="add">
    <input id="text" name="text" placeholder="Add a task&hellip;" maxlength="200" required>
    <button type="submit">Add</button>
  </form>
"""

    // Pinned to the top-right of the viewport so it stays reachable whichever
    // panel is open. Both glyphs ship; the stylesheets show the one that names
    // where a tap will take you.
    private fun themeButton(): String =
        """<a class="tbtn" id="theme" href="#" data-act="theme" title="Dark mode" aria-label="Dark mode">
  <svg class="ic ic-moon" width="24" height="24" viewBox="0 0 24 24" aria-hidden="true"><path class="s-ink" d="M20.5 14.8A8.6 8.6 0 0 1 9.2 3.5a8.6 8.6 0 1 0 11.3 11.3Z" fill="none" stroke="#000" stroke-width="2" stroke-linejoin="round"/></svg>
  <svg class="ic ic-sun" width="24" height="24" viewBox="0 0 24 24" aria-hidden="true"><circle class="s-ink" cx="12" cy="12" r="4.6" fill="none" stroke="#000" stroke-width="2"/><path class="s-ink" d="M12 2.2v3M12 18.8v3M2.2 12h3M18.8 12h3M5.1 5.1l2.1 2.1M16.8 16.8l2.1 2.1M18.9 5.1l-2.1 2.1M7.2 16.8l-2.1 2.1" fill="none" stroke="#000" stroke-width="2" stroke-linecap="round"/></svg>
</a>
"""

    // Phone only: the Kindle follows the choice rather than making it. A tile
    // is a submit button rather than a link so that with scripting off it
    // still applies its own scene on the spot.
    private fun scenesPanel(active: String): String {
        val picks = scenes.list.joinToString("") { s ->
            val here = s.id == active
            "<li><button type=\"submit\" name=\"id\" value=\"${esc(s.id)}\" " +
                "class=\"pick${if (here) " on sel" else ""}\" id=\"pick-${esc(s.id)}\" " +
                "data-act=\"scene-pick\" data-id=\"${esc(s.id)}\" aria-pressed=\"$here\">" +
                "<span class=\"thumb\">${scenes.renderThumb(s.id)}</span>" +
                "<span class=\"nm\">${esc(s.name)}</span></button></li>"
        }

        return """<section class="panel" id="p-scenes">
  <div class="head">
    <h1>Scenes</h1>
    <div class="sub">Choose a vignette for the Kindle</div>
  </div>
  <form id="scform" method="post" action="/api/action">
    <input type="hidden" name="act" value="scene">
    <ul class="scenes" id="scenes">$picks</ul>
  </form>
  <div class="applybar" id="applybar" hidden>
    <div class="applymsg" id="applymsg"></div>
    <button type="button" class="btn" id="applybtn" data-act="scene-apply" disabled>Apply</button>
  </div>
</section>

"""
    }

    private class PanelOpts(
        val view: String,
        val addForm: Boolean,
        val status: Boolean,
        val scenes: Boolean,
        val account: Boolean
    )

    private fun panels(state: State, boot: Boot, opts: PanelOpts): String {
        val now = Date()
        val time = pad(SimpleDateFormat("HH", Locale.US).format(now).toInt()) + ":" +
            SimpleDateFormat("mm", Locale.US).format(now)
        // The Node server uses toLocaleDateString with weekday/day/month; the
        // nearest fixed pattern, in the phone's own locale.
        val date = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now)

        val openCount = boot.openCount

        val account = if (opts.account) """
    <div class="acct">
      <a href="/pair">Pair a device</a>
      <form method="post" action="/logout"><button type="submit">Sign out</button></form>
    </div>""" else ""

        val status = if (opts.status) " &#183; <span id=\"status\">connected</span>" else ""
        val scenesNav = if (opts.scenes) "\n  <a href=\"#\" data-go=\"scenes\">Scenes</a>" else ""

        return themeButton() + """<section class="panel" id="p-idle">
  <div class="head">
    <div class="clock" id="clock">$time</div>
    <div class="date" id="date">${esc(date)}</div>
  </div>
  <div class="scene" id="scene">${scenes.renderScenes(state.scene)}</div>
  <div class="upnext">
    <h2>Up next</h2>
    <ul id="upnext">${boot.upNext}</ul>
  </div>
</section>

<section class="panel" id="p-tasks">
  <div class="head">
    <h1>Tasks</h1>
    <div class="sub"><span id="count">$openCount</span> open$status</div>
  </div>
${if (opts.addForm) addForm() else ""}  <ul class="todos" id="todos">${boot.todos}</ul>
  <div class="foot"><a class="btn ghost" href="#" data-act="clear">Clear finished</a>$account</div>
</section>

<section class="panel" id="p-timer">
  <div class="head"><h1>Stopwatch</h1></div>
  <div class="swwrap"><div class="sw" id="sw">0:00</div></div>
  <div class="btns">
    <a class="btn" href="#" data-act="sw-toggle" id="swbtn">Start</a>
    <a class="btn" href="#" data-act="sw-lap">Lap</a>
    <a class="btn" href="#" data-act="sw-reset">Reset</a>
  </div>
  <ul class="laps" id="laps">${boot.laps}</ul>
</section>

${if (opts.scenes) scenesPanel(state.scene) else ""}<nav id="nav">
  <a href="#" data-go="idle"${if (opts.view == "idle") " class=\"on\"" else ""}>Idle</a>
  <a href="#" data-go="tasks"${if (opts.view == "tasks") " class=\"on\"" else ""}>Tasks<b id="badge">$openCount</b></a>
  <a href="#" data-go="timer">Timer</a>$scenesNav
</nav>"""
    }

    private fun bootScript(bootJson: String): String =
        "<script>window.BOOT=$bootJson;" +
            "window.FRAMES=${scenes.frameCount};window.FRAME_MS=${scenes.frameMs};</script>"

    /**
     * An explicit choice is stamped on <html> by the server: the alternative
     * is letting a script swap it after first paint, which on e-ink means a
     * full white flash before the dark page arrives.
     */
    private fun themeClass(theme: String?): String = when (theme) {
        "dark" -> " class=\"theme-dark\""
        "light" -> " class=\"theme-light\""
        else -> ""
    }

    private fun themeColorMeta(theme: String?): String = when (theme) {
        "dark" -> "<meta name=\"theme-color\" content=\"#121211\">"
        "light" -> "<meta name=\"theme-color\" content=\"#faf9f6\">"
        else -> """<meta name="theme-color" content="#faf9f6" media="(prefers-color-scheme: light)">
<meta name="theme-color" content="#121211" media="(prefers-color-scheme: dark)">"""
    }

    /* ---------------------------------------------------------------------
       the two pages the account lives on
    --------------------------------------------------------------------- */

    private fun authHead(title: String, theme: String?): String =
        """<!DOCTYPE html>
<html lang="en"${themeClass(theme)}>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
${themeColorMeta(theme)}
<title>$title</title>
<style>$AUTH_CSS</style>
</head>"""

    /**
     * Both doors are on the one page and both are plain forms, because the
     * device most likely to need the code door is the one least able to run a
     * script to reveal it. [locked] is milliseconds of lockout left, or 0 -- a
     * locked form still renders, with the fields disabled, because telling
     * someone to come back is friendlier than a bare 429.
     */
    fun renderLoginPage(theme: String?, next: String, error: String? = null, locked: Long = 0): String {
        val off = if (locked > 0) " disabled" else ""
        val message = when {
            locked > 0 -> {
                val mins = Math.ceil(locked / 60000.0).toInt()
                "<div class=\"msg\">Too many attempts. Try again in " +
                    "$mins minute${if (mins == 1) "" else "s"}.</div>"
            }
            error == "code" ->
                "<div class=\"msg\">That code is not valid. Codes last 30 minutes " +
                    "and work once &#8212; generate a fresh one on the phone.</div>"
            error != null ->
                "<div class=\"msg\">That username and password do not match.</div>"
            else -> ""
        }

        return """${authHead("Kindle Idle", theme)}
<body>
<div class="wrap">
  <h1>Kindle Idle</h1>
  <p class="sub">This screen is on your network. Sign in to use it.</p>
  <form method="post" action="/login" autocomplete="off">
    <input type="hidden" name="next" value="${esc(next)}">
    <label for="user">Username</label>
    <input id="user" name="user" type="text" maxlength="32"
      autocapitalize="none" autocorrect="off" spellcheck="false"$off>
    <label for="pass">Password</label>
    <input id="pass" name="pass" type="password" maxlength="200"
      autocapitalize="none" autocorrect="off" spellcheck="false"$off>
    <button type="submit"$off>Sign in</button>
  </form>
  $message
  <div class="or">
    <h2>Or use a pairing code</h2>
    <p>Easier on the Kindle. On the phone remote, open <b>Tasks</b> and tap
    <b>Pair a device</b> to get a six-digit code.</p>
    <form method="post" action="/login" autocomplete="off">
      <input type="hidden" name="next" value="${esc(next)}">
      <label for="code">Six-digit code</label>
      <input id="code" name="code" type="text" maxlength="7"
        inputmode="numeric" pattern="[0-9 ]*" autocapitalize="none"
        autocorrect="off" spellcheck="false"$off>
      <button type="submit" class="ghost"$off>Pair this device</button>
    </form>
  </div>
  <p class="note">Either way, this device stays signed in for a year. The
  username and password are shown in the host app the first time it starts.</p>
</div>
</body>
</html>"""
    }

    /**
     * The pairing desk, shown on the phone. The countdown is the only script
     * in either page and it is decoration: it starts from a duration rather
     * than an absolute time, so a device with a wrong clock still counts down
     * correctly.
     */
    fun renderPairPage(code: String, expiresAt: Long, theme: String?, origin: String): String {
        val left = maxOf(0L, expiresAt - System.currentTimeMillis())
        val mins = Math.ceil(left / 60000.0).toInt()
        val plural = if (mins == 1) "" else "s"
        val grouped = code.substring(0, 3) + " " + code.substring(3)

        return """${authHead("Pair a device", theme)}
<body>
<div class="wrap">
  <h1>Pair a device</h1>
  <p class="sub">Type this on the Kindle. It works once, and only for the next
  $mins minute$plural.</p>
  <div class="code">${esc(grouped)}</div>
  <div class="left" id="left">Expires in $mins minute$plural</div>
  <ol class="steps">
    <li>Open <b>${esc(origin)}</b> on the Kindle.</li>
    <li>Scroll to <b>Or use a pairing code</b>.</li>
    <li>Enter the six digits above.</li>
  </ol>
  <form method="post" action="/pair">
    <button type="submit" class="ghost">New code</button>
  </form>
  <a class="back" href="/remote">Back to the remote</a>
</div>
<script>
(function () {
  var el = document.getElementById('left');
  var left = $left;
  var t0 = new Date().getTime();
  function pad(n) { return n < 10 ? '0' + n : '' + n; }
  function tick() {
    var s = Math.round((left - (new Date().getTime() - t0)) / 1000);
    if (s <= 0) { el.innerHTML = 'Expired \\u2014 tap New code.'; return; }
    el.innerHTML = 'Expires in ' + Math.floor(s / 60) + ':' + pad(s % 60);
    setTimeout(tick, 1000);
  }
  tick();
})();
</script>
</body>
</html>"""
    }

    /* ---------------------------------------------------------------------
       the two screens
    --------------------------------------------------------------------- */

    fun renderKindlePage(state: State, version: Int, theme: String?): String {
        val boot = boot(state, version, "kindle")
        return """<!DOCTYPE html>
<html lang="en"${themeClass(theme)}>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<meta http-equiv="Cache-Control" content="no-cache">
<title>Idle</title>
<link rel="stylesheet" href="/kindle.css?v=$assetV">
</head>
<body class="view-idle">

${panels(state, boot, PanelOpts("idle", addForm = false, status = false, scenes = false, account = false))}

${bootScript(boot.json)}
<script src="/kindle.js?v=$assetV"></script>
</body>
</html>"""
    }

    fun renderRemotePage(state: State, version: Int, theme: String?): String {
        val boot = boot(state, version, "remote")
        return """<!DOCTYPE html>
<html lang="en"${themeClass(theme)}>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
${themeColorMeta(theme)}
<title>Kindle Remote</title>
<link rel="stylesheet" href="/remote.css?v=$assetV">
</head>
<body class="view-tasks">

${panels(state, boot, PanelOpts("tasks", addForm = true, status = true, scenes = true, account = true))}

${bootScript(boot.json)}
<script src="/remote.js?v=$assetV"></script>
</body>
</html>"""
    }

    companion object {
        private const val UP_NEXT_LIMIT = 4

        /**
         * Inline, because the login page is served before a session exists:
         * pulling in /remote.css would mean opening the static directory to
         * anyone who can reach the port.
         */
        private const val AUTH_CSS = """* { margin: 0; padding: 0; box-sizing: border-box; }
html, body { height: 100%; background: #faf9f6; color: #1b1a17; }
body { font-family: Georgia, "Times New Roman", Times, serif;
  -webkit-text-size-adjust: 100%; }
.wrap { width: 100%; max-width: 420px; margin: 0 auto; padding: 10% 30px 40px; }
h1 { font-size: 26px; font-weight: normal; letter-spacing: 0.26em;
  text-transform: uppercase; border-bottom: 2px solid #1b1a17; padding-bottom: 14px; }
p.sub { font-size: 17px; color: #6f6b62; margin-top: 14px; line-height: 1.5; }
form { margin-top: 22px; }
label { display: block; font-size: 14px; letter-spacing: 0.18em;
  text-transform: uppercase; color: #6f6b62; margin: 16px 0 8px; }
input { display: block; width: 100%; font: inherit; font-size: 20px;
  padding: 12px 14px; color: #1b1a17; background: #fff;
  border: 1px solid #a19c92; -webkit-appearance: none; border-radius: 0; }
button { display: block; width: 100%; margin-top: 18px; font: inherit;
  font-size: 18px; letter-spacing: 0.12em; text-transform: uppercase;
  padding: 14px; color: #faf9f6; background: #1b1a17;
  border: 1px solid #1b1a17; border-radius: 0; cursor: pointer; }
button.ghost { color: #1b1a17; background: none; border-color: #a19c92; }
.msg { margin-top: 18px; padding: 12px 14px; font-size: 17px;
  border: 1px solid #1b1a17; background: #f2f0ea; }
.note { margin-top: 24px; font-size: 14px; color: #a19c92; line-height: 1.6; }
.or { margin-top: 34px; padding-top: 26px; border-top: 1px solid #d8d3c8; }
.or h2 { font-size: 14px; font-weight: normal; letter-spacing: 0.18em;
  text-transform: uppercase; color: #6f6b62; }
.or p { margin-top: 10px; font-size: 15px; color: #6f6b62; line-height: 1.55; }
.or form { margin-top: 12px; }
.or input { font-family: "Helvetica Neue", Helvetica, Arial, sans-serif;
  font-size: 30px; letter-spacing: 0.32em; text-align: center; padding: 14px 10px; }
/* The code itself: the one thing on the page anyone is looking at, sized to
   be read off a phone held at arm's length while standing at the Kindle. */
.code { font-family: "Helvetica Neue", Helvetica, Arial, sans-serif;
  font-size: 54px; letter-spacing: 0.16em; text-align: center;
  padding: 26px 10px 22px; border: 1px solid #a19c92; background: #f2f0ea;
  margin-top: 24px; }
.left { margin-top: 12px; text-align: center; font-size: 15px; color: #6f6b62; }
.steps { margin-top: 26px; font-size: 16px; color: #6f6b62; line-height: 1.6; }
.steps li { margin-left: 20px; padding-left: 4px; }
.back { display: block; margin-top: 28px; text-align: center; font-size: 15px;
  color: #6f6b62; }
html.theme-dark, html.theme-dark body { background: #121211; color: #ebe7dd; }
html.theme-dark h1 { border-bottom-color: #ebe7dd; }
html.theme-dark p.sub, html.theme-dark label, html.theme-dark .left,
html.theme-dark .steps, html.theme-dark .back, html.theme-dark .or h2,
html.theme-dark .or p { color: #948e83; }
html.theme-dark input { color: #ebe7dd; background: #1e1d1a; border-color: #35322c; }
html.theme-dark button { color: #121211; background: #ebe7dd; border-color: #ebe7dd; }
html.theme-dark button.ghost { color: #ebe7dd; background: none; border-color: #35322c; }
html.theme-dark .msg { background: #1e1d1a; border-color: #35322c; }
html.theme-dark .note { color: #6b665d; }
html.theme-dark .or { border-top-color: #35322c; }
html.theme-dark .code { background: #1e1d1a; border-color: #35322c; }
@media (prefers-color-scheme: dark) {
  html:not(.theme-light), html:not(.theme-light) body { background: #121211; color: #ebe7dd; }
  html:not(.theme-light) h1 { border-bottom-color: #ebe7dd; }
  html:not(.theme-light) p.sub, html:not(.theme-light) label,
  html:not(.theme-light) .left, html:not(.theme-light) .steps,
  html:not(.theme-light) .back, html:not(.theme-light) .or h2,
  html:not(.theme-light) .or p { color: #948e83; }
  html:not(.theme-light) input { color: #ebe7dd; background: #1e1d1a; border-color: #35322c; }
  html:not(.theme-light) button { color: #121211; background: #ebe7dd; border-color: #ebe7dd; }
  html:not(.theme-light) button.ghost { color: #ebe7dd; background: none; border-color: #35322c; }
  html:not(.theme-light) .msg { background: #1e1d1a; border-color: #35322c; }
  html:not(.theme-light) .note { color: #6b665d; }
  html:not(.theme-light) .or { border-top-color: #35322c; }
  html:not(.theme-light) .code { background: #1e1d1a; border-color: #35322c; }
}"""
    }
}
