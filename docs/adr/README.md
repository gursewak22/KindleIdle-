# Architecture decision records

Every non-obvious architecture decision for KindleIdle lives here as an ADR —
one Markdown file per decision, numbered in the order it was made.

## Conventions

- **Location:** `docs/adr/`
- **Filename:** `NNNN-kebab-case-title.md`, e.g. `0001-server-render-the-idle-scene.md`
- **Numbering:** four digits, monotonic, never reused. `0000-template.md` is the
  template and is not itself a decision.
- **Status:** an ADR is never edited to reverse it. Write a new ADR and mark the
  old one `Superseded by [ADR NNNN](...)`.

Start a new one by copying the template:

```sh
cp docs/adr/0000-template.md docs/adr/0010-my-decision.md
```

## What belongs in an ADR

A decision earns an ADR when reversing it later would mean reworking more than
one file, or when the reasoning is a Kindle-specific constraint that is not
visible from the code alone (e-ink refresh cost, browser age, offline
behaviour, storage durability). Routine implementation choices do not — those
belong in a code comment next to the code.

## Index

| # | Decision | Status |
|---|----------|--------|
| [0001](0001-server-renders-all-markup.md) | Render all markup on the server | Accepted |
| [0002](0002-pregenerated-idle-animation-frames.md) | Pre-generate the idle animation as inline SVG frames | Accepted |
| [0003](0003-long-polling-for-state-sync.md) | Sync state with long-polling, not WebSockets or SSE | Accepted |
| [0004](0004-json-file-store-with-atomic-writes.md) | Keep state in memory, persist to one JSON file atomically | Accepted |
| [0005](0005-no-runtime-dependencies.md) | No runtime dependencies, no build step | Accepted |
| [0006](0006-phone-remote-shares-the-server.md) | The phone remote is a second view of the same state | Accepted |
| [0007](0007-scene-choice-is-shared-state.md) | Ship every idle scene in the page and choose one from shared state | Accepted |
| [0008](0008-dark-mode-is-a-per-device-cookie.md) | Keep dark mode per device, in a cookie | Accepted |
| [0009](0009-sign-in-on-the-phone-pair-the-kindle.md) | Sign in on the phone, pair the Kindle with a short-lived code | Accepted |

For how these fit together, see [../architecture.md](../architecture.md).
