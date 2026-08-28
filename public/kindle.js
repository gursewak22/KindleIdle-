/* ES5 only, no fetch, no arrow functions: the Kindle browser is old WebKit.
   This file does as little as possible -- the server ships finished HTML and
   this just swaps strings in, ticks two counters, and holds one long poll. */
(function () {
  'use strict';

  var boot = window.BOOT;
  var FRAME_COUNT = window.FRAMES;
  var FRAME_MS = window.FRAME_MS;
  var IDLE_AFTER = 150000;

  var body = document.body;
  var nav = document.getElementById('nav');
  var elClock = document.getElementById('clock');
  var elDate = document.getElementById('date');
  var elUpNext = document.getElementById('upnext');
  var elTodos = document.getElementById('todos');
  var elLaps = document.getElementById('laps');
  var elCount = document.getElementById('count');
  var elBadge = document.getElementById('badge');
  var elSw = document.getElementById('sw');
  var elSwBtn = document.getElementById('swbtn');

  var version = boot.v;
  var sw = boot.sw;
  var skew = boot.now - new Date().getTime();
  var view = 'idle';
  var lastTouch = 0;
  var frame = 0;
  var scene = boot.scene;

  // Last markup the server sent for each region, so we only touch the DOM
  // when that region actually changed. Every repaint costs an e-ink refresh.
  var shown = { todos: boot.todos, upNext: boot.upNext, laps: boot.laps };
  var shownClock = '';
  var shownDate = '';
  var shownSw = '';
  var shownBtn = '';

  function now() { return new Date().getTime() + skew; }

  function pad(n) { return n < 10 ? '0' + n : '' + n; }

  function fmt(ms) {
    var t = Math.floor(ms / 1000);
    var h = Math.floor(t / 3600);
    var m = Math.floor((t % 3600) / 60);
    return (h ? h + ':' + pad(m) : '' + m) + ':' + pad(t % 60);
  }

  function setHtml(el, html) {
    if (el && el.innerHTML !== html) el.innerHTML = html;
  }

  /* ---------- views ---------- */

  function setView(name) {
    if (view === name) return;
    view = name;
    body.className = 'view-' + name;
    var links = nav.getElementsByTagName('a');
    for (var i = 0; i < links.length; i++) {
      links[i].className = links[i].getAttribute('data-go') === name ? 'on' : '';
    }
    if (name === 'idle') paintClock();
    if (name === 'timer') paintSw();
  }

  function frameEl(i) {
    return document.getElementById('fr-' + scene + '-' + i);
  }

  // Every scene is already in the page, so a change from the phone costs one
  // class swap per svg -- no reload, and only the vignette repaints.
  function switchScene(next) {
    var el = document.getElementById('sc-' + next);
    if (!el || next === scene) return;
    var old = document.getElementById('sc-' + scene);
    if (old) old.setAttribute('class', 'sc');
    scene = next;
    // Rewind the incoming scene so the ticker and the markup agree on which
    // frame is showing; the outgoing one froze wherever its loop had got to.
    for (var i = 0; i < FRAME_COUNT; i++) {
      var g = frameEl(i);
      if (g) g.setAttribute('class', i === 0 ? 'fr on' : 'fr');
    }
    frame = 0;
    el.setAttribute('class', 'sc on');
  }

  // Dark mode is this device's own choice, kept in a cookie so the server can
  // paint the next load correctly instead of the page flashing white first.
  function toggleTheme() {
    var el = document.documentElement;
    var dark = el.className.indexOf('theme-dark') > -1;
    var next = dark ? 'light' : 'dark';
    el.className = 'theme-' + next;
    document.cookie = 'ki_theme=' + next + ';path=/;max-age=31536000';
  }

  function paintClock() {
    var d = new Date(now());
    var t = pad(d.getHours()) + ':' + pad(d.getMinutes());
    if (t === shownClock) return;
    shownClock = t;
    elClock.innerHTML = t;
    var ds = d.getDate() + '';
    if (ds !== shownDate) {
      shownDate = ds;
      elDate.innerHTML = d.toLocaleDateString(undefined, {
        weekday: 'long', day: 'numeric', month: 'long'
      });
    }
  }

  function elapsed() {
    return sw.accumulated + (sw.running ? now() - sw.startedAt : 0);
  }

  function paintSw() {
    var t = fmt(elapsed());
    if (t !== shownSw) { shownSw = t; elSw.innerHTML = t; }
    var label = sw.running ? 'Stop' : 'Start';
    if (label !== shownBtn) { shownBtn = label; elSwBtn.innerHTML = label; }
  }

  function setCount(n) {
    var s = '' + n;
    setHtml(elCount, s);
    setHtml(elBadge, s);
    elBadge.className = n ? '' : 'zero';
  }

  function apply(d) {
    if (!d || typeof d.v !== 'number') return;
    skew = d.now - new Date().getTime();
    if (d.v === version) return;
    version = d.v;
    if (d.todos !== shown.todos) { shown.todos = d.todos; elTodos.innerHTML = d.todos; }
    if (d.upNext !== shown.upNext) { shown.upNext = d.upNext; elUpNext.innerHTML = d.upNext; }
    if (d.laps !== shown.laps) { shown.laps = d.laps; elLaps.innerHTML = d.laps; }
    setCount(d.openCount);
    if (d.scene && d.scene !== scene) switchScene(d.scene);
    sw = d.sw;
    paintSw();
  }

  /* ---------- network ---------- */

  // The session ran out, or the passphrase changed. Reloading lands on the
  // login form, which is the only place this device can do anything about it.
  // Guarded because a reload is not instant and the poll would otherwise fire
  // a second one on its way out.
  var reloading = false;

  function signedOut() {
    if (reloading) return;
    reloading = true;
    window.location.reload();
  }

  function post(act, id) {
    var xhr = new XMLHttpRequest();
    xhr.open('POST', '/api/action', true);
    xhr.setRequestHeader('Content-Type', 'application/json');
    xhr.setRequestHeader('Accept', 'application/json');
    xhr.onreadystatechange = function () {
      if (xhr.readyState !== 4) return;
      if (xhr.status === 401) { signedOut(); return; }
      if (xhr.status === 200) {
        try { apply(JSON.parse(xhr.responseText)); } catch (e) {}
      }
    };
    xhr.send(JSON.stringify({ act: act, id: id || '' }));
  }

  function poll() {
    var xhr = new XMLHttpRequest();
    var settled = false;
    var guard = setTimeout(function () {
      if (settled) return;
      settled = true;
      try { xhr.abort(); } catch (e) {}
      setTimeout(poll, 2000);
    }, 40000);
    xhr.open('GET', '/api/poll?v=' + version, true);
    xhr.setRequestHeader('Accept', 'application/json');
    xhr.onreadystatechange = function () {
      if (xhr.readyState !== 4 || settled) return;
      settled = true;
      clearTimeout(guard);
      if (xhr.status === 401) { signedOut(); return; }
      if (xhr.status === 200) {
        try { apply(JSON.parse(xhr.responseText)); } catch (e) {}
        setTimeout(poll, 150);
      } else {
        setTimeout(poll, 5000);
      }
    };
    xhr.send(null);
  }

  /* ---------- input ---------- */

  function swToggleLocal() {
    if (sw.running) {
      sw.accumulated += now() - sw.startedAt;
      sw.running = false;
      sw.startedAt = null;
    } else {
      sw.running = true;
      sw.startedAt = now();
    }
    paintSw();
  }

  document.addEventListener('click', function (e) {
    lastTouch = now();
    var el = e.target;
    for (var hop = 0; el && el.getAttribute && hop < 6; hop++) {
      var go = el.getAttribute('data-go');
      if (go) { e.preventDefault(); setView(go); return; }
      var act = el.getAttribute('data-act');
      if (act) {
        e.preventDefault();
        // The theme never leaves this device, so it never reaches the server.
        if (act === 'theme') { toggleTheme(); return; }
        if (act === 'sw-toggle') swToggleLocal();
        if (act === 'toggle' && el.parentNode) {
          var li = el.parentNode;
          li.className = li.className.indexOf('done') > -1 ? 't' : 't done';
        }
        post(act, el.getAttribute('data-id'));
        return;
      }
      el = el.parentNode;
    }
  }, false);

  /* ---------- clocks ---------- */

  setInterval(function () {
    if (view === 'idle') { paintClock(); return; }
    if (view === 'timer' && sw.running) { paintSw(); return; }
    if (now() - lastTouch > IDLE_AFTER) setView('idle');
  }, 1000);

  setInterval(function () {
    if (view !== 'idle') return;
    var next = (frame + 1) % FRAME_COUNT;
    var from = frameEl(frame);
    var to = frameEl(next);
    if (!from || !to) return;
    from.setAttribute('class', 'fr');
    to.setAttribute('class', 'fr on');
    frame = next;
  }, FRAME_MS);

  lastTouch = now();
  setCount(boot.openCount);
  paintSw();
  poll();
})();
