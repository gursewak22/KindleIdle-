'use strict';

// Build step: turn server/idle.js into an Android asset.
//
// The Kotlin host serves the same two web pages the Node server does, so the
// scene markup has to be byte-identical to what idle.js produces -- and the
// scenes are 468 lines of hand-tuned SVG geometry that nobody should be
// hand-porting to Kotlin. So we don't: we require idle.js, ask it for its
// output, and ship that.
//
// Two forms come out, because there are two consumers:
//
//   statics/frames  the exact strings, for the Kindle page and the web remote
//   vector          the same shapes flattened to plain path data, for the
//                   native idle screen and picker, which draw with Compose
//                   rather than a WebView
//
// Run:  node android/tools/gen-scenes.js
// Out:  android/app/src/main/assets/scenes.json

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..', '..');
const OUT = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'scenes.json');
const idle = require(path.join(ROOT, 'server', 'idle.js'));

/* ---------------------------------------------------------------------------
   a very small SVG reader

   Only what idle.js emits: self-closing shapes and <g> wrappers, no text and
   no namespaces. Anything outside that is a bug in this script rather than
   something to be lenient about, so the walker throws instead of guessing.
--------------------------------------------------------------------------- */

const TAG = /<(\/?)([a-zA-Z][a-zA-Z0-9-]*)((?:\s+[a-zA-Z-]+="[^"]*")*)\s*(\/?)>/g;

function parseAttrs(raw) {
  const out = {};
  for (const m of raw.matchAll(/([a-zA-Z-]+)="([^"]*)"/g)) out[m[1]] = m[2];
  return out;
}

// Walks `markup`, calling onShape(name, attrs, inherited) for every drawable
// leaf, with <g> attributes folded in from the outside in.
function walk(markup, onShape) {
  const stack = [{}];
  TAG.lastIndex = 0;
  let m;
  while ((m = TAG.exec(markup))) {
    const closing = m[1];
    const name = m[2];
    const attrs = parseAttrs(m[3]);
    const selfClose = m[4];
    if (closing) {
      if (name === 'g' || name === 'svg') stack.pop();
      continue;
    }
    if (name === 'g' || name === 'svg') {
      const inherited = Object.assign({}, stack[stack.length - 1], attrs);
      delete inherited.id;
      delete inherited.class;
      if (!selfClose) stack.push(inherited);
      continue;
    }
    onShape(name, attrs, stack[stack.length - 1]);
  }
  if (stack.length !== 1) throw new Error('unbalanced markup: depth ' + stack.length);
}

/* ---------------------------------------------------------------------------
   shapes -> path data

   The native side draws with androidx PathParser, which speaks the `d`
   attribute and nothing else, so every primitive becomes a path here rather
   than becoming a second code path over there.
--------------------------------------------------------------------------- */

function num(v, fallback) {
  const n = parseFloat(v);
  return Number.isFinite(n) ? n : fallback;
}

function toPathData(name, a) {
  if (name === 'path') return a.d || '';

  if (name === 'circle') {
    const cx = num(a.cx, 0);
    const cy = num(a.cy, 0);
    const r = num(a.r, 0);
    // Two half-arcs: a single 360-degree arc is degenerate and draws nothing,
    // which is the classic way a circle silently disappears.
    return 'M' + (cx - r) + ' ' + cy +
      'a' + r + ' ' + r + ' 0 1 0 ' + (r * 2) + ' 0' +
      'a' + r + ' ' + r + ' 0 1 0 ' + (-r * 2) + ' 0Z';
  }

  if (name === 'ellipse') {
    const cx = num(a.cx, 0);
    const cy = num(a.cy, 0);
    const rx = num(a.rx, 0);
    const ry = num(a.ry, 0);
    return 'M' + (cx - rx) + ' ' + cy +
      'a' + rx + ' ' + ry + ' 0 1 0 ' + (rx * 2) + ' 0' +
      'a' + rx + ' ' + ry + ' 0 1 0 ' + (-rx * 2) + ' 0Z';
  }

  if (name === 'rect') {
    const x = num(a.x, 0);
    const y = num(a.y, 0);
    const w = num(a.width, 0);
    const h = num(a.height, 0);
    const rx = num(a.rx, num(a.ry, 0));
    if (!rx) return 'M' + x + ' ' + y + 'h' + w + 'v' + h + 'h' + -w + 'Z';
    const ry = num(a.ry, rx);
    return 'M' + (x + rx) + ' ' + y +
      'h' + (w - rx * 2) + 'a' + rx + ' ' + ry + ' 0 0 1 ' + rx + ' ' + ry +
      'v' + (h - ry * 2) + 'a' + rx + ' ' + ry + ' 0 0 1 ' + -rx + ' ' + ry +
      'h' + -(w - rx * 2) + 'a' + rx + ' ' + ry + ' 0 0 1 ' + -rx + ' ' + -ry +
      'v' + -(h - ry * 2) + 'a' + rx + ' ' + ry + ' 0 0 1 ' + rx + ' ' + -ry + 'Z';
  }

  if (name === 'line') {
    return 'M' + num(a.x1, 0) + ' ' + num(a.y1, 0) +
      'L' + num(a.x2, 0) + ' ' + num(a.y2, 0);
  }

  if (name === 'polyline' || name === 'polygon') {
    const pts = String(a.points || '').trim().split(/[\s,]+/).map(Number);
    if (pts.length < 4) return '';
    let d = 'M' + pts[0] + ' ' + pts[1];
    for (let i = 2; i + 1 < pts.length; i += 2) d += 'L' + pts[i] + ' ' + pts[i + 1];
    return d + (name === 'polygon' ? 'Z' : '');
  }

  throw new Error('unhandled shape: ' + name);
}

// Paint, resolved against the inherited <g> attributes. SVG's own defaults
// apply: fill is black unless something says otherwise, stroke is nothing.
function paintOf(attrs, inherited) {
  function get(key, dflt) {
    if (attrs[key] !== undefined) return attrs[key];
    if (inherited[key] !== undefined) return inherited[key];
    return dflt;
  }

  const out = {};
  const fill = get('fill', '#000');
  const stroke = get('stroke', 'none');

  if (fill && fill !== 'none') out.fill = fill;
  if (stroke && stroke !== 'none') {
    out.stroke = stroke;
    out.width = num(get('stroke-width', 1), 1);
    const cap = get('stroke-linecap', '');
    const join = get('stroke-linejoin', '');
    const dash = get('stroke-dasharray', '');
    if (cap) out.cap = cap;
    if (join) out.join = join;
    if (dash) out.dash = dash.trim().split(/[\s,]+/).map(Number).filter(Number.isFinite);
  }
  return out;
}

function flatten(markup) {
  const shapes = [];
  walk(markup, function (name, attrs, inherited) {
    const d = toPathData(name, attrs);
    if (!d) return;
    const paint = paintOf(attrs, inherited);
    paint.d = d;
    shapes.push(paint);
  });
  return shapes;
}

/* ---------------------------------------------------------------------------
   splitting a scene back into statics and frames

   renderScenes() hands back one <svg> per scene holding a statics <g> and
   FRAME_COUNT frame <g>s. Depth counting rather than a regex, because the
   scenes contain their own <g> wrappers for shared stroke attributes.
--------------------------------------------------------------------------- */

function childGroups(svgInner) {
  const groups = [];
  let depth = 0;
  let start = -1;
  let openEnd = -1;
  TAG.lastIndex = 0;
  let m;
  while ((m = TAG.exec(svgInner))) {
    if (m[2] !== 'g') continue;
    if (!m[1]) {
      if (depth === 0) {
        start = m.index;
        openEnd = m.index + m[0].length;
      }
      depth++;
    } else {
      depth--;
      if (depth === 0) {
        groups.push({
          open: svgInner.slice(start, openEnd),
          inner: svgInner.slice(openEnd, m.index)
        });
      }
    }
  }
  if (depth !== 0) throw new Error('unbalanced <g> while splitting a scene');
  return groups;
}

function splitScenes(markup) {
  const out = new Map();
  const svgRe = /<svg id="sc-([a-z]+)"[^>]*>([\s\S]*?)<\/svg>/g;
  let m;
  while ((m = svgRe.exec(markup))) {
    const id = m[1];
    const groups = childGroups(m[2]);
    const statics = groups.filter(function (g) { return !/id="fr-/.test(g.open); });
    const frames = groups.filter(function (g) { return /id="fr-/.test(g.open); });
    if (statics.length !== 1) {
      throw new Error(id + ': expected 1 statics group, saw ' + statics.length);
    }
    if (frames.length !== idle.FRAME_COUNT) {
      throw new Error(id + ': expected ' + idle.FRAME_COUNT + ' frames, saw ' + frames.length);
    }
    out.set(id, {
      statics: statics[0].inner,
      frames: frames.map(function (g) { return g.inner; })
    });
  }
  return out;
}

/* ------------------------------------------------------------------------ */

function build() {
  const parts = splitScenes(idle.renderScenes(idle.DEFAULT_SCENE));

  const scenes = idle.SCENES.map(function (meta) {
    const built = parts.get(meta.id);
    if (!built) throw new Error('no markup found for scene ' + meta.id);
    // Every frame, not just the first: the native idle screen animates the
    // same eight the Kindle does. The picker's thumbnail is the static layer
    // plus frame 0, which the app composes from these rather than storing
    // twice.
    return {
      id: meta.id,
      name: meta.name,
      statics: built.statics,
      frames: built.frames,
      vector: {
        statics: flatten(built.statics),
        frames: built.frames.map(function (markup) { return flatten(markup); })
      }
    };
  });

  return {
    generatedFrom: 'server/idle.js',
    frameCount: idle.FRAME_COUNT,
    frameMs: idle.FRAME_MS,
    defaultScene: idle.DEFAULT_SCENE,
    viewBox: '0 0 420 300',
    scenes: scenes
  };
}

/* A round trip is the only check that matters. If reassembling the pieces
   does not reproduce renderScenes() character for character, the Kindle would
   be getting subtly different markup from the Android host than from Node. */
function verify(data) {
  const rebuilt = data.scenes.map(function (s) {
    const frames = s.frames.map(function (markup, i) {
      return '<g id="fr-' + s.id + '-' + i + '" class="fr' + (i === 0 ? ' on' : '') + '">' +
        markup + '</g>';
    }).join('');
    const on = s.id === data.defaultScene ? ' on' : '';
    return '<svg id="sc-' + s.id + '" class="sc' + on + '" viewBox="' + data.viewBox + '" ' +
      'preserveAspectRatio="xMidYMid meet" aria-hidden="true">' +
      '<g>' + s.statics + '</g>' + frames + '</svg>';
  }).join('');

  const expected = idle.renderScenes(idle.DEFAULT_SCENE);
  if (rebuilt === expected) return;

  for (let i = 0; i < Math.max(rebuilt.length, expected.length); i++) {
    if (rebuilt[i] !== expected[i]) {
      throw new Error('round trip differs at ' + i +
        '\n  got:  ' + JSON.stringify(rebuilt.slice(Math.max(0, i - 60), i + 60)) +
        '\n  want: ' + JSON.stringify(expected.slice(Math.max(0, i - 60), i + 60)));
    }
  }
  throw new Error('round trip differs in length only');
}

const data = build();
verify(data);

fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, JSON.stringify(data));

const shapes = data.scenes.reduce(function (n, s) {
  return n + s.vector.statics.length +
    s.vector.frames.reduce(function (m, f) { return m + f.length; }, 0);
}, 0);
console.log('scenes.json written');
console.log('  ' + data.scenes.length + ' scenes, ' + data.frameCount + ' frames each');
console.log('  ' + shapes + ' drawable shapes');
console.log('  ' + (fs.statSync(OUT).size / 1024).toFixed(1) + ' kB');
console.log('  round trip against idle.renderScenes() matches');
