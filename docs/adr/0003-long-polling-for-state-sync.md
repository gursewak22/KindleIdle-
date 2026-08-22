# ADR 0003: Sync state with long-polling, not WebSockets or SSE

- **Status:** Accepted
- **Date:** 2026-08-22

## Context

Two clients - the Kindle and a phone - must see the same todos and stopwatch.
Edits happen on the phone and have to appear on the Kindle within a second or
two. The Kindle browser predates reliable WebSocket and EventSource support,
and the device sleeps and reconnects on its own schedule.

## Decision

Clients poll `GET /api/poll?v=N`. `store.waitForChange()` parks the request
until the version moves or 25 s elapse, then the server replies with a full
`statePayload`. The client re-polls immediately with the new version.

Because a request may sit idle for the whole hold, `index.js` raises
`keepAliveTimeout` and `headersTimeout` past `POLL_MS` and disables
`requestTimeout`.

Alternatives rejected:

- **WebSockets / SSE** - not dependable on the Kindle browser, and they add a
  reconnect state machine for a two-client LAN app.
- **Short-interval polling** - either wastes wakeups or adds latency.

## Consequences

- Updates land as fast as a request round trip, using only plain HTTP that
  every browser here supports.
- Recovery is free: a dropped connection is just a poll that never returns, and
  the next poll carries the last known version.
- One socket is held open per client, and the 25 s ceiling means a stale client
  can be at most one hold behind. Fine at this scale; it would not be at a
  larger one.
