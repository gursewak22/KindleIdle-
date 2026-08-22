# ADR 0005: No runtime dependencies, no build step

- **Status:** Accepted
- **Date:** 2026-08-22

## Context

This runs on a home machine and is expected to still start years from now with
no maintenance. Every dependency is something to update, audit, and reinstall;
every build step is something to run before the app works.

## Decision

The server uses only Node core (`http`, `fs`, `path`, `os`). `package.json`
declares no dependencies and one script, `npm start`. Client assets are served
as plain files from `public/` with a `?v=` cache-buster from `ASSET_V`; nothing
is bundled, transpiled, or minified.

## Consequences

- `git clone && npm start` works, offline, with no install step.
- No supply-chain surface and no lockfile drift.
- We hand-roll what a framework would give us: routing, body parsing and size
  limits, MIME types, static path-escape checks, and HTML escaping. That code
  is small and lives in `index.js` and `render.js`; the tradeoff is that it is
  ours to get right.
