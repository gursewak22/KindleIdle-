'use strict';

const idle = require('./idle');

const ASSET_V = Date.now().toString(36);
const UP_NEXT_LIMIT = 4;

function esc(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function open(state) {
  return state.todos.filter((t) => !t.done);
}

function todoItem(t) {
  return `<li class="t${t.done ? ' done' : ''}">` +
    `<a class="box" href="#" data-act="toggle" data-id="${esc(t.id)}"></a>` +
    `<span class="tx">${esc(t.text)}</span>` +
    `<a class="del" href="#" data-act="del" data-id="${esc(t.id)}">&#215;</a>` +
    `</li>`;
}

function renderTodos(state, hint) {
  if (!state.todos.length) {
    return `<li class="empty">Nothing on the list.<br><span>${hint}</span></li>`;
  }
  const pending = state.todos.filter((t) => !t.done);
  const done = state.todos.filter((t) => t.done);
  let html = pending.map(todoItem).join('');
  if (done.length) {
    html += `<li class="sep">Done &#183; ${done.length}</li>` + done.map(todoItem).join('');
  }
  return html;
}

function renderUpNext(state) {
  const pending = open(state);
  if (!pending.length) return `<li class="empty">All clear.</li>`;
  let html = pending.slice(0, UP_NEXT_LIMIT)
    .map((t) => `<li>${esc(t.text)}</li>`).join('');
  const rest = pending.length - UP_NEXT_LIMIT;
  if (rest > 0) html += `<li class="more">and ${rest} more</li>`;
  return html;
}

function pad(n) {
  return n < 10 ? '0' + n : String(n);
}

function fmtElapsed(ms) {
  const total = Math.floor(ms / 1000);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  return (h ? h + ':' + pad(m) : String(m)) + ':' + pad(s);
}

function renderLaps(state) {
  const laps = state.stopwatch.laps;
  if (!laps.length) return '';
  return `<li class="lhead"><b>#</b><span>Split</span><em>Total</em></li>` +
    laps.map((lap, i) =>
      `<li><b>${laps.length - i}</b><span>${fmtElapsed(lap.split)}</span><em>${fmtElapsed(lap.at)}</em></li>`
    ).join('');
}

function swSnapshot(state) {
  const sw = state.stopwatch;
  return {
    running: sw.running,
    startedAt: sw.startedAt,
    accumulated: sw.accumulated
  };
}

// The Kindle's empty-list hint points at the phone; the phone's points at the
// input right above it. Everything else about the two lists is identical.
// This is a check rather than a map lookup because the audience arrives as a
// URL query param, and `?for=constructor` would walk Object.prototype.
function emptyHint(audience) {
  return audience === 'remote'
    ? 'Add one above.'
    : 'Add tasks from your phone at /remote';
}

// One payload shape for both the long-poll and the action responses: neither
// client ever builds markup, it only swaps in strings the server made.
function statePayload(state, version, hint) {
  return {
    v: version,
    now: Date.now(),
    todos: renderTodos(state, emptyHint(hint)),
    upNext: renderUpNext(state),
    laps: renderLaps(state),
    scene: state.scene,
    openCount: open(state).length,
    sw: swSnapshot(state)
  };
}

/* ---------------------------------------------------------------------------
   Both screens are the same three panels over the same nav, so they share one
   body. They differ only in the stylesheet, the add form, and which panel
   opens first -- keeping the markup here stops the two designs from drifting.
--------------------------------------------------------------------------- */

function addForm() {
  return `  <form id="add" method="post" action="/api/action" autocomplete="off">
    <input type="hidden" name="act" value="add">
    <input id="text" name="text" placeholder="Add a task&hellip;" maxlength="200" required>
    <button type="submit">Add</button>
  </form>
`;
}

// The dark-mode switch is a sibling of the panels, pinned to the top-right of
// the viewport, so it stays reachable whichever panel is open. Both glyphs
// ship; the stylesheets show the one that names where a tap will take you.
function themeButton() {
  return `<a class="tbtn" id="theme" href="#" data-act="theme" title="Dark mode" aria-label="Dark mode">
  <svg class="ic ic-moon" width="24" height="24" viewBox="0 0 24 24" aria-hidden="true"><path class="s-ink" d="M20.5 14.8A8.6 8.6 0 0 1 9.2 3.5a8.6 8.6 0 1 0 11.3 11.3Z" fill="none" stroke="#000" stroke-width="2" stroke-linejoin="round"/></svg>
  <svg class="ic ic-sun" width="24" height="24" viewBox="0 0 24 24" aria-hidden="true"><circle class="s-ink" cx="12" cy="12" r="4.6" fill="none" stroke="#000" stroke-width="2"/><path class="s-ink" d="M12 2.2v3M12 18.8v3M2.2 12h3M18.8 12h3M5.1 5.1l2.1 2.1M16.8 16.8l2.1 2.1M18.9 5.1l-2.1 2.1M7.2 16.8l-2.1 2.1" fill="none" stroke="#000" stroke-width="2" stroke-linecap="round"/></svg>
</a>
`;
}

// Phone only: the Kindle follows the choice rather than making it, so it gets
// neither this panel nor a fourth nav slot to squeeze into.
// A tile is a submit button rather than a link so that with scripting off it
// still applies its own scene on the spot. The staging bar below is hidden
// until the script un-hides it, because staging is the thing that needs JS:
// without it there is nowhere to hold a choice that has not been sent yet.
function scenesPanel(active) {
  const picks = idle.SCENES.map((s) => {
    const here = s.id === active;
    return `<li><button type="submit" name="id" value="${esc(s.id)}" ` +
      `class="pick${here ? ' on sel' : ''}" id="pick-${esc(s.id)}" ` +
      `data-act="scene-pick" data-id="${esc(s.id)}" aria-pressed="${here}">` +
      `<span class="thumb">${idle.renderThumb(s.id)}</span>` +
      `<span class="nm">${esc(s.name)}</span></button></li>`;
  }).join('');

  return `<section class="panel" id="p-scenes">
  <div class="head">
    <h1>Scenes</h1>
    <div class="sub">Choose a vignette for the Kindle</div>
  </div>
  <form id="scform" method="post" action="/api/action">
    <input type="hidden" name="act" value="scene">
    <ul class="scenes" id="scenes">${picks}</ul>
  </form>
  <div class="applybar" id="applybar" hidden>
    <div class="applymsg" id="applymsg"></div>
    <button type="button" class="btn" id="applybtn" data-act="scene-apply" disabled>Apply</button>
  </div>
</section>

`;
}

function panels(boot, opts) {
  const now = new Date();
  const time = pad(now.getHours()) + ':' + pad(now.getMinutes());
  const date = now.toLocaleDateString(undefined, {
    weekday: 'long', day: 'numeric', month: 'long'
  });

  return `${themeButton()}<section class="panel" id="p-idle">
  <div class="head">
    <div class="clock" id="clock">${time}</div>
    <div class="date" id="date">${esc(date)}</div>
  </div>
  <div class="scene" id="scene">${idle.renderScenes(boot.scene)}</div>
  <div class="upnext">
    <h2>Up next</h2>
    <ul id="upnext">${boot.upNext}</ul>
  </div>
</section>

<section class="panel" id="p-tasks">
  <div class="head">
    <h1>Tasks</h1>
    <div class="sub"><span id="count">${boot.openCount}</span> open${
      opts.status ? ` &#183; <span id="status">connected</span>` : ''
    }</div>
  </div>
${opts.addForm ? addForm() : ''}  <ul class="todos" id="todos">${boot.todos}</ul>
  <div class="foot"><a class="btn ghost" href="#" data-act="clear">Clear finished</a>${
    opts.account ? `
    <div class="acct">
      <a href="/pair">Pair a device</a>
      <form method="post" action="/logout"><button type="submit">Sign out</button></form>
    </div>` : ''
  }</div>
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

${opts.scenes ? scenesPanel(boot.scene) : ''}<nav id="nav">
  <a href="#" data-go="idle"${opts.view === 'idle' ? ' class="on"' : ''}>Idle</a>
  <a href="#" data-go="tasks"${opts.view === 'tasks' ? ' class="on"' : ''}>Tasks<b id="badge">${boot.openCount}</b></a>
  <a href="#" data-go="timer">Timer</a>${
    opts.scenes ? `
  <a href="#" data-go="scenes">Scenes</a>` : ''
  }
</nav>`;
}

function bootScript(boot) {
  return `<script>window.BOOT=${JSON.stringify(boot)};` +
    `window.FRAMES=${idle.FRAME_COUNT};window.FRAME_MS=${idle.FRAME_MS};</script>`;
}

// An explicit choice is stamped on <html> by the server: the alternative is
// letting a script swap it after first paint, which on e-ink means a full
// white flash before the dark page arrives.
function themeClass(theme) {
  if (theme === 'dark') return ' class="theme-dark"';
  if (theme === 'light') return ' class="theme-light"';
  return '';
}

// Untouched, the phone follows the system; the two-media form lets the browser
// pick the address-bar colour without us guessing.
function themeColorMeta(theme) {
  if (theme === 'dark') return `<meta name="theme-color" content="#121211">`;
  if (theme === 'light') return `<meta name="theme-color" content="#faf9f6">`;
  return `<meta name="theme-color" content="#faf9f6" media="(prefers-color-scheme: light)">
<meta name="theme-color" content="#121211" media="(prefers-color-scheme: dark)">`;
}

/* ---------------------------------------------------------------------------
   The two pages the account lives on: the login form, and the pairing desk.

   Both are entirely inline -- the login page is served before a session
   exists, so pulling in /remote.css would mean opening the static directory
   to anyone who can reach the port, and the pairing page keeps the same look
   by keeping the same rules. Neither needs a script to work: the countdown on
   the pairing page is a garnish over text the server already rendered.
--------------------------------------------------------------------------- */

const AUTH_CSS = `* { margin: 0; padding: 0; box-sizing: border-box; }
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
}`;

function authHead(title, theme) {
  return `<!DOCTYPE html>
<html lang="en"${themeClass(theme)}>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
${themeColorMeta(theme)}
<title>${title}</title>
<style>${AUTH_CSS}</style>
</head>`;
}

// Both doors are on the one page and both are plain forms, because the device
// most likely to need the code door is the one least able to run a script to
// reveal it. `locked` is milliseconds of lockout left, or 0 -- a locked form
// still renders, with the fields disabled, because telling someone to come
// back is friendlier than a bare 429 and the Kindle has nowhere to show a
// status code anyway.
function renderLoginPage(opts) {
  const next = opts.next || '/';
  const off = opts.locked ? ' disabled' : '';
  let message = '';
  if (opts.locked) {
    const mins = Math.ceil(opts.locked / 60000);
    message = `<div class="msg">Too many attempts. Try again in ` +
      `${mins} minute${mins === 1 ? '' : 's'}.</div>`;
  } else if (opts.error === 'code') {
    message = `<div class="msg">That code is not valid. Codes last 30 minutes ` +
      `and work once &#8212; generate a fresh one on the phone.</div>`;
  } else if (opts.error) {
    message = `<div class="msg">That username and password do not match.</div>`;
  }

  return `${authHead('Kindle Idle', opts.theme)}
<body>
<div class="wrap">
  <h1>Kindle Idle</h1>
  <p class="sub">This screen is on your network. Sign in to use it.</p>
  <form method="post" action="/login" autocomplete="off">
    <input type="hidden" name="next" value="${esc(next)}">
    <label for="user">Username</label>
    <input id="user" name="user" type="text" maxlength="32"
      autocapitalize="none" autocorrect="off" spellcheck="false"${off}>
    <label for="pass">Password</label>
    <input id="pass" name="pass" type="password" maxlength="200"
      autocapitalize="none" autocorrect="off" spellcheck="false"${off}>
    <button type="submit"${off}>Sign in</button>
  </form>
  ${message}
  <div class="or">
    <h2>Or use a pairing code</h2>
    <p>Easier on the Kindle. On the phone remote, open <b>Tasks</b> and tap
    <b>Pair a device</b> to get a six-digit code.</p>
    <form method="post" action="/login" autocomplete="off">
      <input type="hidden" name="next" value="${esc(next)}">
      <label for="code">Six-digit code</label>
      <input id="code" name="code" type="text" maxlength="7"
        inputmode="numeric" pattern="[0-9 ]*" autocapitalize="none"
        autocorrect="off" spellcheck="false"${off}>
      <button type="submit" class="ghost"${off}>Pair this device</button>
    </form>
  </div>
  <p class="note">Either way, this device stays signed in for a year. The
  username and password are printed in the server's console the first time it
  starts.</p>
</div>
</body>
</html>`;
}

// The pairing desk, shown on the phone. The countdown is the only script in
// either page and it is decoration: it starts from a duration rather than an
// absolute time, so a device with a wrong clock still counts down correctly,
// and with no script at all the server-rendered minutes remain.
function renderPairPage(opts) {
  const code = opts.code;
  const left = Math.max(0, opts.expiresAt - Date.now());
  const mins = Math.ceil(left / 60000);
  const grouped = code.slice(0, 3) + ' ' + code.slice(3);

  return `${authHead('Pair a device', opts.theme)}
<body>
<div class="wrap">
  <h1>Pair a device</h1>
  <p class="sub">Type this on the Kindle. It works once, and only for the next
  ${mins} minute${mins === 1 ? '' : 's'}.</p>
  <div class="code">${esc(grouped)}</div>
  <div class="left" id="left">Expires in ${mins} minute${mins === 1 ? '' : 's'}</div>
  <ol class="steps">
    <li>Open <b>${esc(opts.origin)}</b> on the Kindle.</li>
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
  var left = ${left};
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
</html>`;
}

function renderKindlePage(state, version, theme) {
  const boot = statePayload(state, version, 'kindle');
  return `<!DOCTYPE html>
<html lang="en"${themeClass(theme)}>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<meta http-equiv="Cache-Control" content="no-cache">
<title>Idle</title>
<link rel="stylesheet" href="/kindle.css?v=${ASSET_V}">
</head>
<body class="view-idle">

${panels(boot, { view: 'idle', addForm: false, status: false, scenes: false, account: false })}

${bootScript(boot)}
<script src="/kindle.js?v=${ASSET_V}"></script>
</body>
</html>`;
}

function renderRemotePage(state, version, theme) {
  const boot = statePayload(state, version, 'remote');
  return `<!DOCTYPE html>
<html lang="en"${themeClass(theme)}>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
${themeColorMeta(theme)}
<title>Kindle Remote</title>
<link rel="stylesheet" href="/remote.css?v=${ASSET_V}">
</head>
<body class="view-tasks">

${panels(boot, { view: 'tasks', addForm: true, status: true, scenes: true, account: true })}

${bootScript(boot)}
<script src="/remote.js?v=${ASSET_V}"></script>
</body>
</html>`;
}

module.exports = {
  renderKindlePage,
  renderRemotePage,
  renderLoginPage,
  renderPairPage,
  statePayload,
  ASSET_V
};
