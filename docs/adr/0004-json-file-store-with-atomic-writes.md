# ADR 0004: Keep state in memory, persist to one JSON file atomically

- **Status:** Accepted
- **Date:** 2026-08-22

## Context

The data is a handful of todos and one stopwatch - small, single-user, and read
on every poll. It has to survive a restart of the server process and an abrupt
power cut of the machine hosting it, but it does not need queries, migrations,
or concurrent writers.

## Decision

`store.js` holds state in memory as the source of truth and mirrors it to
`data/state.json`:

- every mutation calls `bump()` - version++, wake waiters, `scheduleSave()`;
- saves are debounced 250 ms so a burst of edits becomes one write;
- a write goes to `state.json.tmp` and is then renamed over the real file;
- `load()` merges the file onto a blank shape and falls back to blank on a
  parse failure or a missing file.

## Consequences

- Reads are free - no I/O in the request path, which is what makes parking a
  poll on an in-memory version counter cheap.
- The rename is atomic, so a crash mid-write leaves the previous good file; a
  corrupt file loses data but never blocks boot.
- Up to 250 ms of edits can be lost on a hard kill, and state must fit in
  memory. Both are acceptable for a personal to-do list.
- No dependency, no schema, no migration story. A field added to the state
  shape must be defaulted in `blank()` for old files to keep loading.
