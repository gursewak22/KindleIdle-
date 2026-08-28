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
  const picker = el('scenes');
  const applyBar = el('applybar');
  const applyMsg = el('applymsg');
  const applyBtn = el('applybtn');

  let version = boot.v;
  let sw = boot.sw;
  let skew = boot.now - Date.now();
  let view = 'tasks';
  let frame = 0;
  let scene = boot.scene;
  // What the tap selected, which is only what the Kindle shows once Apply has
  // sent it. Everything else on this page commits on touch; a scene is the one
  // choice that changes what someone across the room is looking at.
  let pending = boot.scene;

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

  const frameEl = (i) => el(`fr-${scene}-${i}`);

  const sceneName = (id) => el('pick-' + id)?.querySelector('.nm').textContent || id;

  // Two marks, not one: `on` is what the Kindle is showing right now, `sel` is
  // what a tap has staged. They coincide until someone picks something else.
  function markPick() {
    for (const pick of picker.querySelectorAll('.pick')) {
      const id = pick.dataset.id;
      pick.className = 'pick' + (id === scene ? ' on' : '') + (id === pending ? ' sel' : '');
      pick.setAttribute('aria-pressed', id === pending);
    }
    const dirty = pending !== scene;
    applyBtn.disabled = !dirty;
    applyMsg.textContent = dirty
      ? sceneName(scene) + ' → ' + sceneName(pending)
      : 'Showing ' + sceneName(scene);
  }

  // Every scene is already in the page: switching is a class swap here and on
  // the Kindle, which is the whole reason the choice is a five-byte id on the
  // wire rather than a fresh vignette.
  function switchScene(next) {
    const el2 = el('sc-' + next);
    if (!el2 || next === scene) return;
    // A change arriving from the server carries the selection with it, unless
    // this phone is holding an unapplied pick -- that stays staged.
    const wasInSync = pending === scene;
    el('sc-' + scene)?.setAttribute('class', 'sc');
    scene = next;
    if (wasInSync) pending = next;
    // Rewind the incoming scene so the ticker and the markup agree on which
    // frame is showing; the outgoing one froze wherever its loop had got to.
    for (let i = 0; i < FRAME_COUNT; i++) {
      frameEl(i)?.setAttribute('class', i === 0 ? 'fr on' : 'fr');
    }
    frame = 0;
    el2.setAttribute('class', 'sc on');
    markPick();
  }

  // No stored choice yet means the phone is following the system, so read the
  // media query rather than assuming the light default.
  function isDark() {
    const cls = document.documentElement.classList;
    if (cls.contains('theme-dark')) return true;
    if (cls.contains('theme-light')) return false;
    return window.matchMedia && matchMedia('(prefers-color-scheme: dark)').matches;
  }

  // The theme stays on this device -- the Kindle keeps its own -- and rides a
  // cookie so the server can paint the next load without a flash of the wrong
  // one. The page itself repaints from the class alone, so no reload.
  function toggleTheme() {
    const next = isDark() ? 'light' : 'dark';
    document.documentElement.className = 'theme-' + next;
    document.cookie = `ki_theme=${next};path=/;max-age=31536000;SameSite=Lax`;
    // The undecided page ships two media-scoped metas; an explicit choice
    // replaces both with the one colour that now applies.
    for (const meta of document.querySelectorAll('meta[name="theme-color"]')) meta.remove();
    const meta = document.createElement('meta');
    meta.name = 'theme-color';
    meta.content = next === 'dark' ? '#121211' : '#faf9f6';
    document.head.appendChild(meta);
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
    if (d.scene) switchScene(d.scene);
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

  // The session ran out, or the passphrase changed. Reloading lands on the
  // login form, which is the only place this device can do anything about it.
  let reloading = false;

  function signedOut() {
    if (reloading) return true;
    reloading = true;
    location.reload();
    return true;
  }

  async function send(act, id, value) {
    const res = await fetch('/api/action?for=remote', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ act, id, text: value })
    });
    if (res.status === 401) return signedOut();
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
        if (res.status === 401) return signedOut();
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
    // The theme never leaves this device, so it never reaches the server.
    if (act === 'theme') { toggleTheme(); return; }
    if (act === 'toggle') target.parentNode.classList.toggle('done');
    // Staging only -- nothing reaches the server until Apply.
    if (act === 'scene-pick') { pending = target.dataset.id; markPick(); return; }
    if (act === 'scene-apply') {
      if (pending === scene) return;
      send('scene', pending).catch(() => online(false));
      return;
    }
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
    const from = frameEl(frame);
    const to = frameEl(next);
    if (!from || !to) return;
    from.setAttribute('class', 'fr');
    to.setAttribute('class', 'fr on');
    frame = next;
  }, FRAME_MS);

  // Staging needs somewhere to hold a choice that has not been sent, so the
  // bar only exists once there is a script to hold it. Without one the tiles
  // are plain submit buttons and apply themselves.
  applyBar.hidden = false;
  markPick();

  paintSw();
  poll();
})();
