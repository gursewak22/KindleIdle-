# ADR 0007: Ship every idle scene in the page and choose one from shared state

- **Status:** Accepted
- **Date:** 2026-08-25

## Context

One vignette became five, and the phone needs to pick which one the Kindle
shows. Two questions follow from that: where the choice lives, and how the
chosen scene's markup reaches the device.

The choice is about the Kindle's screen rather than the phone's, so it belongs
with the todos and the stopwatch in shared state — a picker that only changed
the phone's own idle panel would be a preview, not a remote.

Delivering the markup is the harder half. [ADR 0001](0001-server-renders-all-markup.md)
puts every byte of markup on the server, and the natural reading of that would
be to send the chosen scene's SVG in each `/api/poll` payload. But a scene is
roughly 10 KB, the payload is re-sent on every change and every 25-second
timeout, and a scene changes a few times a month. That trades a constant cost
against a rare event. The alternative — reloading the Kindle page on a change —
costs a full-screen e-ink flash for what should be a small repaint.

## Decision

`idle.js` builds all five scenes at require time and `renderScenes(active)`
inlines all of them into both pages, with `display: none` on every one but the
chosen scene. Switching is a class swap over markup the device already holds.

The payload carries `scene` as a short id (`reading`, `rain`, `cat`, `sky`,
`desk`), not markup — the one place a client acts on data rather than swapping
in a server-made string. `store.setScene()` validates the id against the
catalogue by scan, so a stored or posted `constructor` cannot walk
`Object.prototype`, and an unknown id is a no-op whose reply echoes the scene
still in force.

## Consequences

- A scene change repaints only the vignette: no reload, no fetch, no e-ink
  flash, and nothing added to the steady-state poll payload.
- The Kindle page grows by about 40 KB of inline SVG, parsed once per load.
  Page loads are rare — the screen sits idle for hours — so this is paid at the
  right time.
- Adding a scene means one entry in `SCENE_DEFS` and costs every page that much
  more markup. At five scenes that is comfortable; at fifty it would not be,
  and the choice would have to move back onto the wire.
- The client now branches on one field of state rather than only diffing
  markup strings. Accepted as the narrow exception it is: the data is an
  enum, and the markup it selects still came from the server.
