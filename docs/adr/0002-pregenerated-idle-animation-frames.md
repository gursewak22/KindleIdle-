# ADR 0002: Pre-generate the idle animation as inline SVG frames

- **Status:** Accepted
- **Date:** 2026-08-22

## Context

The idle screen should feel alive, but an e-ink panel cannot animate: each
repaint is slow and a large one ghosts. Fetching frames or computing paths on
the device would also spend battery and network on something purely decorative.

## Decision

`idle.js` builds the whole scene at boot, once, and exports it as inline SVG:

- a **static layer** (room, chair, reader, lamp, sky) drawn a single time, and
- **8 overlay frames** holding only the parts that move — three steam strands,
  a turning page on frames 3-6, and two twinkling stars.

Frames advance every 6 s by toggling a class, so only the small overlay region
changes. The markup is inlined into the page rather than requested as assets.

## Consequences

- Repaints are confined to a few small regions, which is what e-ink handles
  well; the device fetches and computes nothing per frame.
- No image assets, no animation library, no network traffic while idle.
- The scene is hand-authored path data in JavaScript - editing it means editing
  code, and the animation is fixed at 8 frames. Accepted for a decorative
  scene; a richer animation would need a different approach.
