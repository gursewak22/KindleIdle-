/* The phone view of the same three panels. Same contract as the Kindle client
   -- the server sends finished markup, this swaps it in -- but written with
   modern JS since the constraint here is a normal phone browser. */
(function () {
  'use strict';

  const boot = window.BOOT;
  const FRAME_COUNT = window.FRAMES;
  const FRAME_MS = window.FRAME_MS;

  const nav = document.getElementById('nav');
  const el = (id) => document.getElementById(id);
  const clock = el('clock');
  const date = el('date');
  const upNext = el('upnext');
  const todos = el('todos');
  const laps = el('laps');
  const count = el('count');
  const badge = el('badge');
  const swEl = el('sw');
  const swBtn = el('swbtn');
  const status = el('status');
  const form = el('add');
  const text = el('text');

  let version = boot.v;
  let sw = boot.sw;
  let skew = boot.now - Date.now();
  let view = 'tasks';
  let frame = 0;

  const shown = { todos: boot.todos, upNext: boot.upNext, laps: boot.laps };
  let shownClock = '';
  let shownDay = '';

  const now = () => Date.now() + skew;
  const pad = (n) => (n < 10 ? '0' + n : '' + n);

  function fmt(ms) {
    const t = Math.floor(ms / 1000);
    const h = Math.floor(t / 3600);
    const m = Math.floor((t % 3600) / 60);
    return (h ? h + ':' + pad(m) : '' + m) + ':' + pad(t % 60);
  }

  /* ---------- painting ---------- */

  function setView(name) {
    if (view === name) return;
    view = name;
    document.body.className = 'view-' + name;
    for (const a of nav.querySelectorAll('a')) {
      a.className = a.dataset.go === name ? 'on' : '';
    }
    if (name === 'idle') paintClock();
    if (name === 'timer') paintSw();
  }

  function paintClock() {
    const d = new Date(now());
    const t = pad(d.getHours()) + ':' + pad(d.getMinutes());
    if (t === shownClock) return;
    shownClock = t;
    clock.textContent = t;
    const day = '' + d.getDate();
    if (day !== shownDay) {
      shownDay = day;
      date.textContent = d.toLocaleDateString(undefined, {
        weekday: 'long', day: 'numeric', month: 'long'
      });
    }
  }

  function paintSw() {
    swEl.textContent = fmt(sw.accumulated + (sw.running ? now() - sw.startedAt : 0));
    swBtn.textContent = sw.running ? 'Stop' : 'Start';
  }

  function apply(d) {
    skew = d.now - Date.now();
    if (d.v === version) return;
    version = d.v;
    if (d.todos !== shown.todos) { shown.todos = d.todos; todos.innerHTML = d.todos; }
    if (d.upNext !== shown.upNext) { shown.upNext = d.upNext; upNext.innerHTML = d.upNext; }
    if (d.laps !== shown.laps) { shown.laps = d.laps; laps.innerHTML = d.laps; }
    const n = d.openCount;
    count.textContent = n;
    badge.textContent = n;
    badge.className = n ? '' : 'zero';
    sw = d.sw;
    paintSw();
  }

  function online(ok) {
    status.textContent = ok ? 'connected' : 'reconnecting…';
    status.className = ok ? '' : 'off';
  }

  /* ---------- network ---------- */

  async function send(act, id, value) {
    const res = await fetch('/api/action?for=remote', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ act, id, text: value })
    });
    if (!res.ok) throw new Error(res.status);
    apply(await res.json());
    online(true);
  }

  async function poll() {
    for (;;) {
      try {
        const res = await fetch(`/api/poll?for=remote&v=${version}`, {
          headers: { Accept: 'application/json' }
        });
        if (!res.ok) throw new Error(res.status);
        apply(await res.json());
        online(true);
      } catch {
        online(false);
        await new Promise((r) => setTimeout(r, 3000));
      }
    }
  }

  /* ---------- input ---------- */

  form.addEventListener('submit', (e) => {
    e.preventDefault();
    const value = text.value.trim();
    if (!value) return;
    text.value = '';
    send('add', null, value).catch(() => online(false));
  });

  document.addEventListener('click', (e) => {
    const target = e.target.closest('[data-go], [data-act]');
    if (!target) return;
    e.preventDefault();
    if (target.dataset.go) { setView(target.dataset.go); return; }

    const act = target.dataset.act;
    if (act === 'toggle') target.parentNode.classList.toggle('done');
    if (act === 'sw-toggle') {
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
    send(act, target.dataset.id).catch(() => online(false));
  });

  /* ---------- clocks ---------- */

  setInterval(() => {
    if (view === 'idle') paintClock();
    else if (view === 'timer' && sw.running) paintSw();
  }, 1000);

  setInterval(() => {
    if (view !== 'idle') return;
    const next = (frame + 1) % FRAME_COUNT;
    el('fr' + frame).setAttribute('class', 'fr');
    el('fr' + next).setAttribute('class', 'fr on');
    frame = next;
  }, FRAME_MS);

  paintSw();
  poll();
})();
