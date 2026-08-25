# Kindle Idle

An idle screen for a Kindle's built-in browser: a clock, a slow line-art
vignette, a shared to-do list, and a stopwatch. Tasks go in from your phone;
the Kindle just displays them.

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
- **The idle animation is precomputed at boot.** A static SVG vignette is drawn
  once; eight tiny overlay frames sit on top of it, and the client toggles one
  attribute every 6 seconds. Only the steam, one book page, and two stars ever
  move, so each frame dirties a few small rectangles rather than the screen.
- **No flexbox or viewport units in the Kindle stylesheet.** CSS tables and
  absolute positioning, which that WebKit build handles reliably.
- **ES5 only in `kindle.js`** — `var`, `XMLHttpRequest`, no arrow functions,
  no `fetch`, no `classList`.

`/remote` has none of these constraints and uses ordinary modern CSS and JS.

Both screens are the same three panels over the same bottom nav, and
`panels()` in `render.js` emits that body for both — they differ only in the
stylesheet, whether the add form is present, and which panel opens first. The
phone reuses the Kindle's paper look with a type scale drawn for a hand-held
screen, and inverts to a dark variant under `prefers-color-scheme: dark` (one
`filter: invert(1)` re-inks the monochrome vignette).

## Layout

```
server/
  index.js   http routing, long-poll hold, static files
  store.js   state, version counter, atomic persistence, change subscribers
  render.js  all HTML generation for both pages
  idle.js    SVG scene + animation frame generation
public/
  kindle.css kindle.js   e-ink screen
  remote.css remote.js   phone
```

## API

| Route | Purpose |
| --- | --- |
| `GET /` | Kindle screen |
| `GET /remote` | phone control page |
| `GET /api/poll?v=N` | long-poll; returns rendered fragments + version |
| `POST /api/action` | `{act, id?, text?}` — `add`, `toggle`, `del`, `clear`, `sw-toggle`, `sw-start`, `sw-stop`, `sw-lap`, `sw-reset` |

`POST /api/action` returns JSON when the request sends `Accept:
application/json`, and otherwise redirects back to the referrer so the plain
`<form>` on `/remote` still works with JavaScript disabled.

## Tuning to your device

The Kindle's Experimental Browser has no fullscreen mode and its UI chrome
cannot be hidden from the page, so the layout has to be sized to whatever
viewport the browser hands over. Measure that viewport on the device itself and
tune `public/kindle.css` to the real numbers, rather than to an assumed screen
size.

## Notes

There is no authentication. Bind it to your LAN only; if you later put it on
the public internet, put it behind a reverse proxy that handles auth and TLS.
