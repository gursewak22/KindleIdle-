# Kindle Idle

An idle screen for a Kindle's built-in browser: a clock, a slow line-art
vignette, a shared to-do list, and a stopwatch. Tasks go in from your phone;
the Kindle just displays them. The phone also picks which of the five
vignettes the Kindle shows, and either screen can be flipped to dark on its
own.

## Running it

```
npm start          # http://localhost:8080
PORT=3000 npm start
```

No dependencies — plain Node, `http` module only. State lives in
`data/state.json`, written atomically a quarter-second after each change.

Open on the Kindle: `http://<your-lan-ip>:8080/`
Open on your phone: `http://<your-lan-ip>:8080/remote`

The server prints both URLs with your LAN address on startup.

## Signing in

Both screens are behind a sign-in, but they do not use the same credential —
the phone has a keyboard and the Kindle has an on-screen one behind e-ink.

**On the phone: username and password.** The first run creates the account and
prints it in the startup banner:

```
  An account was created for this server. Write it down --
  the password is not stored in readable form and will not
  be shown again.

      username   kindle
      password   zala-pivo-vojo-gepo
```

Write it down — only the scrypt hash is kept, in `data/auth.json`. The words
are pronounceable because they get read off a console and typed on a phone.

**On the Kindle: a six-digit code.** Signed in on the phone, open **Tasks** →
**Pair a device**. Type the six digits on the Kindle, under *Or use a pairing
code*, and it is in. The code lasts 30 minutes, works exactly once, and gives
up after ten wrong guesses. Generating a new one replaces it.

The 30 minutes is the life of the *code*, not of the session it hands out.
Either way a device stays signed in for a year, across server restarts — the
Kindle is meant to be typed on once and then left alone.

To choose your own account:

```
npm run passwd                     # asks for a username and password, then restart
KI_USER=me KI_PASSWORD=... npm start   # override for one run, nothing written
```

Changing it signs every device out. So does deleting `data/auth.json`, which
also generates a fresh account on the next start.

**This is a lock, not a tunnel.** The Kindle's browser cannot do TLS — a
self-signed certificate is a wall on that device — so the password, the pairing
code and the session cookie all cross your network in the clear. Someone who
can *read packets* on your wifi can still get in; someone who merely reaches
the port cannot. Keep it on a network you trust, and if it ever goes anywhere
else, put a TLS-terminating reverse proxy with its own auth in front.

## Why it is built this way

The Kindle's browser is old WebKit on slow hardware behind an e-ink panel, so
every design decision here pushes work to the server and repaints as little
of the screen as possible.

- **The server renders all HTML.** `/api/poll` returns finished markup strings,
  not data. The Kindle never templates, formats a list, or builds a DOM node.
- **Region-level diffing.** The client remembers the exact string the server
  last sent for each region and only assigns `innerHTML` when that string
  changes. A stopwatch tick never repaints the task list.
- **One long-poll instead of polling.** `/api/poll?v=N` holds for 25s and
  returns the moment the version counter moves, so an edit on your phone shows
  up in under a second with no repeated requests in between.
- **The stopwatch runs on the client from a server timestamp.** The server owns
  `startedAt`; the device computes elapsed time locally at 1-second
  granularity. The Kindle's own clock is never trusted — skew is measured
  against `now` in every payload.
- **The idle animation is precomputed at boot.** Each scene is a static SVG
  vignette drawn once with eight tiny overlay frames on top, and the client
  toggles one attribute every 6 seconds. Only the moving parts -- steam, a
  book page, raindrops, a fire -- sit in those frames, so each tick dirties a
  few small rectangles rather than the screen.
- **All five scenes ship inside the page.** Changing scenes from the phone is
  a class swap over markup the device already holds: no reload, no fetch, and
  only the vignette repaints. The wire carries a five-byte scene id.
- **No flexbox or viewport units in the Kindle stylesheet.** CSS tables and
  absolute positioning, which that WebKit build handles reliably.
- **ES5 only in `kindle.js`** — `var`, `XMLHttpRequest`, no arrow functions,
  no `fetch`, no `classList`.

`/remote` has none of these constraints and uses ordinary modern CSS and JS.

Both screens are the same panels over the same nav, and `panels()` in
`render.js` emits that body for both — they differ only in the stylesheet,
whether the add form, the Scenes panel and the connection status are there, and
which panel opens first. The phone reuses the Kindle's paper look with a type
scale drawn for a hand-held screen. Either screen can be flipped to dark, which
is its own mechanism rather than a filter over the light one — see [Scenes and
dark mode](#scenes-and-dark-mode).

`/remote` is also sized for a laptop window, in `remote.css` alone -- the
markup and the JS are the same at every width. Past 620px the text moves into
a centred column instead of spanning the window; past 900px the bottom nav
becomes a rail down the left, where a pointer expects it; and past 1180px the
idle screen splits, with the vignette on the left and **Up next** beside it
rather than below the fold. Display type and the vignette are capped in `vw`
and `vh` rather than pixels, so a 4K panel gets a bigger clock instead of the
same small one in more whitespace. Hover states only exist behind
`(hover: hover)`, so a phone still gets the touch idiom.

## Layout

```
server/
  index.js   http routing, the auth gate, long-poll hold, static files
  auth.js    password hashing, session cookies, pairing codes, login throttle
  passwd.js  npm run passwd -- change the username and password
  store.js   state, version counter, atomic persistence, change subscribers
  render.js  all HTML for both pages, plus the login and pairing pages
  idle.js    SVG scene + animation frame generation
public/
  kindle.css kindle.js   e-ink screen
  remote.css remote.js   phone
```

## API

Everything except the login routes and `/favicon.ico` needs a session cookie.
A page request without one is redirected to `/login`; a request that asked for
JSON gets a `401`, which both clients answer by reloading onto the form.

| Route | Purpose |
| --- | --- |
| `GET /login` | Both doors — username/password, and a pairing-code field. Inline CSS, works with JS off |
| `POST /login` | Takes `user`+`pass` or a six-digit `code`; sets the session cookie and redirects to where you were headed |
| `POST /logout` | Clears it. The **Sign out** footnote on `/remote` posts here |
| `GET /pair` | The pairing desk (needs a session) — shows the live code and its countdown |
| `POST /pair` | **New code**: replaces the live one |
| `GET /` | Kindle screen |
| `GET /remote` | phone control page |
| `GET /api/poll?v=N` | long-poll; returns rendered fragments + version |
| `POST /api/action` | `{act, id?, text?}` — `add`, `toggle`, `del`, `clear`, `scene`, `sw-toggle`, `sw-start`, `sw-stop`, `sw-lap`, `sw-reset` |

`scene` takes the scene id in `id`: `reading`, `rain`, `cat`, `sky`, or
`desk`. An unknown id is a no-op, and the reply echoes the scene still in
force.

`POST /api/action` returns JSON when the request sends `Accept:
application/json`, and otherwise redirects back to the referrer so the plain
`<form>` on `/remote` still works with JavaScript disabled.

## Scenes and dark mode

The **Scenes** tab on the phone chooses the vignette. A tap only stages the
choice -- the tile marks itself and the bar at the foot of the panel names the
change -- and **Apply** is what sends it. Everything else on the remote commits
on touch, but a scene changes what someone across the room is looking at, so it
asks first. A small square marks whichever tile the Kindle is actually showing,
so a staged pick and the live one stay legible at once. With scripting off
there is nowhere to hold an unsent choice, so the staging bar stays hidden and
each tile is a plain submit button that applies itself.

That choice is shared
state: it lives in `data/state.json`, rides the same long-poll as everything
else, and the Kindle changes with it within a second. Add a scene by adding one
entry to `SCENE_DEFS` in `server/idle.js` -- a static layer and a frame
function -- and it appears in the picker on the next restart.

**Dark mode** is the icon in the top-right corner of either screen, and unlike
the scene it is per-device: the Kindle can stay on paper while the phone goes
dark at night. The choice is kept in a `ki_theme` cookie so the server can
stamp the theme onto the first paint -- on e-ink, letting a script correct it
afterwards means a full white flash first. Untouched, the phone follows its
system setting and the Kindle stays light.

The scenes are authored black-on-white, with every shape tagged by the role its
colour plays (`f-ink`, `s-soft`, `f-paper`...). The stylesheets repaint those
roles for dark mode, which beats a blanket `filter: invert(1)` -- greys stay
grey rather than flipping -- and the literal `fill`/`stroke` attributes remain
the light theme, so a browser that ignores CSS on SVG still draws it correctly.

## Tuning to your device

The Kindle's Experimental Browser has no fullscreen mode and its UI chrome
cannot be hidden from the page, so the layout has to be sized to whatever
viewport the browser hands over. Measure that viewport on the device itself and
tune `public/kindle.css` to the real numbers, rather than to an assumed screen
size.

## Notes

The login page is deliberately self-contained — inline CSS, no script, no
external asset. It is the only page served before a session exists, so pulling
in a stylesheet would mean opening the static directory to anyone who can reach
the port.

Failed logins are throttled per address: five free tries, then a lockout that
doubles from 30 seconds to a fifteen-minute cap. Both doors share that counter,
so alternating between the password form and the code field does not buy fresh
tries at either. While locked, even correct credentials are refused, so the
lockout cannot be used to test one last guess.

A six-digit code is only a million possibilities, so it leans on three things
rather than its own length: it is single use, it is abandoned after ten wrong
guesses, and it expires in half an hour. Codes live in memory only — a restart
during those thirty minutes forgets the outstanding one, and you generate
another.

`data/auth.json` holds the HMAC secret as well as the password hash, so anyone
who can read that file can forge a session without knowing the password. It is
as private as the folder it sits in and no more; Node's file `mode` only
toggles the read-only flag on Windows, so the code does not pretend otherwise.

Sessions are stateless — a signed cookie, no server-side table — so restarting
the server does not send you back to the shelf to retype anything on e-ink.
