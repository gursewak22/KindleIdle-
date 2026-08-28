# ADR 0008: Keep dark mode per device, in a cookie

- **Status:** Accepted
- **Date:** 2026-08-25

## Context

Both screens get a dark mode, reached from an icon in the top-right corner.
Unlike the idle scene ([ADR 0007](0007-scene-choice-is-shared-state.md)), this
is not a property of the room: the phone is a backlit screen read at night and
the Kindle is a reflective panel that may be perfectly readable as paper at the
same moment. Making it shared state would force one answer on both.

Where the choice is stored matters more here than it looks. `localStorage`
keeps it on the device but only reaches the page after the script runs, so the
first paint is the wrong theme and the correction is a full-screen inversion —
on e-ink, a white flash before the dark page arrives. The server already
renders both pages per request, so it can stamp the theme onto the first paint
if the request carries it.

## Decision

The theme lives in a `ki_theme` cookie (`dark` or `light`), read by
`readTheme()` in `index.js` and stamped onto `<html>` as `theme-dark` or
`theme-light`. No cookie means undecided: the phone follows
`prefers-color-scheme` and the Kindle stays light. Tapping the icon rewrites
the class and the cookie on the spot — the page repaints from the class alone,
so no reload and no round trip. The action never reaches `/api/action`.

Scene artwork carries a role class per shape (`f-ink`, `s-soft`, `f-paper`…)
which each stylesheet repaints, rather than a blanket `filter: invert(1)`.

The cookie is namespaced because cookies ignore ports: on `localhost` a bare
`theme` is shared with every other project served from the same host.

## Consequences

- The first paint is already correct on both screens, and the Kindle never
  flashes white on its way to dark.
- The two screens can disagree, which is the point, but there is no way to
  change the Kindle's theme from the phone — you tap the icon on the Kindle.
- Clearing cookies resets a device to "follow the system". Acceptable for a
  display preference.
- Because the literal `fill`/`stroke` attributes stay in the markup and only
  dark mode depends on CSS reaching SVG, a browser that ignores that CSS still
  renders the light scene correctly; the risk is confined to dark mode on a
  device we cannot test until it is on the Kindle.
- Greys stay grey under inversion instead of flipping, at the cost of every new
  shape needing a colour the role map knows.
