# ADR 0006: The phone remote is a second view of the same state

- **Status:** Accepted
- **Date:** 2026-08-22

## Context

Typing on a Kindle is painful, so tasks are added from a phone. That could be a
separate app talking to an API, but it is the same data, on the same LAN, for
the same person.

## Decision

`/remote` is another server-rendered page from the same process, reading the
same `store` and the same `statePayload`. Both views share one write endpoint,
`POST /api/action`, whose body is a flat `{act, ...}` mapped by `applyAction()`.

The endpoint accepts JSON or form encoding. A request that does not ask for
JSON is answered with `303` back to the `Referer`, so the remote's controls
still work as plain form posts if scripting is unavailable.

## Consequences

- One state, one render path, one set of actions - the two views cannot drift.
- Adding a control means adding one `case` to `applyAction()` and a button.
- The server binds `0.0.0.0` and prints its LAN addresses; there is no auth, so
  anyone on the network can drive it. Accepted for a home LAN, and the reason
  not to expose the port to the internet.
