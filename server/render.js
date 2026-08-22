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

function panels(boot, opts) {
  const now = new Date();
  const time = pad(now.getHours()) + ':' + pad(now.getMinutes());
  const date = now.toLocaleDateString(undefined, {
    weekday: 'long', day: 'numeric', month: 'long'
  });

  return `<section class="panel" id="p-idle">
  <div class="head">
    <div class="clock" id="clock">${time}</div>
    <div class="date" id="date">${esc(date)}</div>
  </div>
  <div class="scene">${idle.renderScene()}</div>
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
  <div class="foot"><a class="btn ghost" href="#" data-act="clear">Clear finished</a></div>
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

<nav id="nav">
  <a href="#" data-go="idle"${opts.view === 'idle' ? ' class="on"' : ''}>Idle</a>
  <a href="#" data-go="tasks"${opts.view === 'tasks' ? ' class="on"' : ''}>Tasks<b id="badge">${boot.openCount}</b></a>
  <a href="#" data-go="timer">Timer</a>
</nav>`;
}

function bootScript(boot) {
  return `<script>window.BOOT=${JSON.stringify(boot)};` +
    `window.FRAMES=${idle.FRAME_COUNT};window.FRAME_MS=${idle.FRAME_MS};</script>`;
}

function renderKindlePage(state, version) {
  const boot = statePayload(state, version, 'kindle');
  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<meta http-equiv="Cache-Control" content="no-cache">
<title>Idle</title>
<link rel="stylesheet" href="/kindle.css?v=${ASSET_V}">
</head>
<body class="view-idle">

${panels(boot, { view: 'idle', addForm: false, status: false })}

${bootScript(boot)}
<script src="/kindle.js?v=${ASSET_V}"></script>
</body>
</html>`;
}

function renderRemotePage(state, version) {
  const boot = statePayload(state, version, 'remote');
  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="theme-color" content="#faf9f6">
<title>Kindle Remote</title>
<link rel="stylesheet" href="/remote.css?v=${ASSET_V}">
</head>
<body class="view-tasks">

${panels(boot, { view: 'tasks', addForm: true, status: true })}

${bootScript(boot)}
<script src="/remote.js?v=${ASSET_V}"></script>
</body>
</html>`;
}

module.exports = { renderKindlePage, renderRemotePage, statePayload, ASSET_V };
