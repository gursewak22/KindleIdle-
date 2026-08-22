# ADR 0001: Render all markup on the server

- **Status:** Accepted
- **Date:** 2026-08-22

## Context

The Kindle browser is an old WebKit build on a device with little CPU and an
e-ink panel. Client-side templating there is slow, and every DOM rebuild risks
a full-screen flash instead of a small regional repaint. We control the server,
which is an ordinary Node process on a laptop or Pi with cycles to spare.

## Decision

The server produces every piece of markup. `render.js` builds the two full
pages, and `statePayload()` returns the same HTML fragments (`todos`, `upNext`,
`laps`) for both the long-poll and the action response. Client code only swaps
those strings into place and updates counters.

Escaping (`esc()`), ordering, the done/pending split, the "and N more" cut-off
and elapsed-time formatting all live on the server. Boot state is inlined as
`window.BOOT` so the first paint needs no round trip.

## Consequences

- The Kindle never parses a template or builds a node tree; a refresh is one
  `innerHTML` assignment per region.
- One payload shape serves both endpoints, so there is a single place where
  markup is decided.
- Fragments are HTML, not data, so the wire format is bigger than JSON state
  and the client cannot re-sort or re-filter on its own. Accepted: any new view
  of the data is a server change.
