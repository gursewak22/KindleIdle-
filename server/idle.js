'use strict';

// Five idle scenes, all built once at boot and inlined into both pages. The
// device never fetches or computes a frame, and switching scenes is a class
// toggle over markup that is already in the DOM -- the only kind of change an
// e-ink panel makes cheaply. See docs/adr/0002 and docs/adr/0007.
//
// Every scene has the same shape: a static layer drawn once, plus FRAME_COUNT
// overlay frames holding only the parts that move. Frames are additive: they
// draw on top of the static layer, so anything that moves has to be left out
// of the static layer rather than painted over later.

const FRAME_COUNT = 8;
const FRAME_MS = 6000;

/* ---------------------------------------------------------------------------
   shared marks
--------------------------------------------------------------------------- */

function star(x, y, r) {
  const i = r * 0.3;
  return `<path d="M${x} ${y - r}L${x + i} ${y - i}L${x + r} ${y}L${x + i} ${y + i}L${x} ${y + r}L${x - i} ${y + i}L${x - r} ${y}L${x - i} ${y - i}Z" fill="#000"/>`;
}

// One rising strand of steam. `phase` shifts the bend and the lift, so three
// strands out of step read as a drift rather than a pulse.
function steamStrand(x, baseY, phase, height) {
  const dir = phase % 2 === 0 ? 1 : -1;
  const lift = (phase % 4) * 3;
  const top = baseY - height - lift;
  const bend = 7 * dir;
  return `<path d="M${x} ${baseY - lift}c${bend} -9 ${-bend} -14 0 -23c${bend} -9 ${-bend} -14 0 -${Math.max(10, baseY - lift - top - 23)}" fill="none" stroke="#666" stroke-width="2" stroke-linecap="round"/>`;
}

function mug(x, y) {
  return `<path d="M${x} ${y}h20v14a6 6 0 0 1-6 6h-8a6 6 0 0 1-6-6Z" fill="#fff" stroke="#000" stroke-width="2.5" stroke-linejoin="round"/>` +
    `<path d="M${x + 20} ${y + 4}h5a5 5 0 0 1 0 10h-5" fill="none" stroke="#000" stroke-width="2.5"/>`;
}

const TWINKLE = [3, 3.6, 4.3, 4.8, 4.3, 3.6, 3, 3];

/* ---------------------------------------------------------------------------
   scene 1 -- reading nook
--------------------------------------------------------------------------- */

function readingStatic() {
  return [
    // night sky
    `<path d="M62 30a22 22 0 1 0 0 44a17 17 0 0 1 0-44Z" fill="#000"/>`,
    star(118, 34, 3),
    star(212, 26, 2.4),
    star(258, 96, 2.4),
    star(342, 40, 3),
    star(390, 78, 2.2),
    star(150, 84, 2),

    // floor + rug
    `<path d="M8 272H412" stroke="#000" stroke-width="2.5" stroke-linecap="round"/>`,
    `<ellipse cx="196" cy="282" rx="168" ry="11" fill="none" stroke="#888" stroke-width="2"/>`,

    // floor lamp
    `<ellipse cx="352" cy="272" rx="26" ry="6" fill="#fff" stroke="#000" stroke-width="2.5"/>`,
    `<path d="M352 266V128" stroke="#000" stroke-width="3" stroke-linecap="round"/>`,
    `<path d="M336 86h32l16 42h-64Z" fill="#fff" stroke="#000" stroke-width="2.5" stroke-linejoin="round"/>`,
    `<g stroke="#888" stroke-width="1.8" stroke-linecap="round" stroke-dasharray="7 9">`,
    `<path d="M312 132 258 214"/><path d="M330 134 306 226"/><path d="M374 134 398 226"/>`,
    `</g>`,

    // armchair
    `<path d="M70 250V174q0-26 26-26h60q26 0 26 26v76Z" fill="#fff" stroke="#000" stroke-width="2.5" stroke-linejoin="round"/>`,
    `<path d="M96 160h60" stroke="#ccc" stroke-width="2"/>`,
    `<rect x="48" y="204" width="26" height="56" rx="13" fill="#fff" stroke="#000" stroke-width="2.5"/>`,
    `<rect x="178" y="204" width="26" height="56" rx="13" fill="#fff" stroke="#000" stroke-width="2.5"/>`,

    // reader
    `<circle cx="126" cy="176" r="17" fill="#fff" stroke="#000" stroke-width="2.5"/>`,
    `<path d="M110 172q4-16 16-16t16 16" fill="#000"/>`,
    `<path d="M108 228l4-32q14-9 28 0l4 32Z" fill="#fff" stroke="#000" stroke-width="2.5" stroke-linejoin="round"/>`,
    `<path d="M112 200q-13 12-6 20" fill="none" stroke="#000" stroke-width="2.5" stroke-linecap="round"/>`,
    `<path d="M140 200q13 12 6 20" fill="none" stroke="#000" stroke-width="2.5" stroke-linecap="round"/>`,

    // seat + blanket over the legs
    `<rect x="60" y="230" width="130" height="28" rx="13" fill="#fff" stroke="#000" stroke-width="2.5"/>`,
    `<path d="M90 236q36-10 74 2l10 22q-46 12-94 0Z" fill="#fff" stroke="#000" stroke-width="2.5" stroke-linejoin="round"/>`,
    `<path d="M104 246q30-6 58 2" fill="none" stroke="#ccc" stroke-width="2"/>`,
    `<path d="M66 258v14M186 258v14" stroke="#000" stroke-width="3" stroke-linecap="round"/>`,

    // open book (resting pages)
    `<path d="M126 214 98 202v18l28 12Z" fill="#fff" stroke="#000" stroke-width="2.5" stroke-linejoin="round"/>`,
    `<path d="M126 214 154 202v18l-28 12Z" fill="#fff" stroke="#000" stroke-width="2.5" stroke-linejoin="round"/>`,

    // side table + mug
    `<path d="M212 214h56v6h-56Z" fill="#fff" stroke="#000" stroke-width="2.5" stroke-linejoin="round"/>`,
    `<path d="M220 220v52M260 220v52" stroke="#000" stroke-width="2.5" stroke-linecap="round"/>`,
    mug(230, 196)
  ].join('');
}

function turningPage(p) {
  if (p === null) return '';
  const tipX = 154 - 56 * p;
  const tipY = 202 - 24 * Math.sin(Math.PI * p);
  const cx = 126 + (tipX - 126) * 0.5;
  const cy = 214 - 44 * Math.sin(Math.PI * p) - 6;
  return `<path d="M126 214Q${cx.toFixed(1)} ${cy.toFixed(1)} ${tipX.toFixed(1)} ${tipY.toFixed(1)}Q${cx.toFixed(1)} ${(cy + 10).toFixed(1)} 126 224Z" fill="#fff" stroke="#000" stroke-width="2.5" stroke-linejoin="round"/>`;
}

// Only frames 3..6 lift a page; the rest of the loop is a quiet read.
const PAGE_PROGRESS = [null, null, null, 0.15, 0.5, 0.82, 0.98, null];

function readingFrame(i) {
  return [
    steamStrand(234, 192, i, 26),
    steamStrand(242, 192, (i + 3) % FRAME_COUNT, 34),
    steamStrand(249, 192, (i + 5) % FRAME_COUNT, 20),
    turningPage(PAGE_PROGRESS[i]),
    star(300, 64, TWINKLE[i]),
    star(76, 108, TWINKLE[(i + 4) % FRAME_COUNT] - 1)
  ].join('');
}

/* ---------------------------------------------------------------------------
   scene 2 -- rain on a window
--------------------------------------------------------------------------- */

function rainStatic() {
  return [
    // outside, drawn first so the frame and the sill sit over it
    `<path d="M70 178q40-30 82-6t74-12 66 22" fill="none" stroke="#888" stroke-width="2"/>`,
    `<circle cx="308" cy="72" r="15" fill="#fff" stroke="#888" stroke-width="2"/>`,
    `<path d="M96 178v-28l24-18 24 18v28" fill="#fff" stroke="#888" stroke-width="2" stroke-linejoin="round"/>`,
    `<rect x="110" y="152" width="18" height="16" fill="#fff" stroke="#888" stroke-width="1.6"/>`,

    // window frame + mullions
    `<rect x="58" y="26" width="304" height="212" fill="none" stroke="#000" stroke-width="3"/>`,
    `<path d="M210 26V238M58 132H362" stroke="#000" stroke-width="2.5"/>`,

    // runnels already on the glass
    `<path d="M136 38q7 22 0 44t0 46" fill="none" stroke="#ccc" stroke-width="2"/>`,
    `<path d="M296 44q-7 26 0 50t0 40" fill="none" stroke="#ccc" stroke-width="2"/>`,

    // sill, wall, floor
    `<path d="M40 238h340v14H40Z" fill="#fff" stroke="#000" stroke-width="2.5" stroke-linejoin="round"/>`,
    `<path d="M8 272H412" stroke="#000" stroke-width="2.5" stroke-linecap="round"/>`,
    `<path d="M96 252v20M324 252v20" stroke="#ccc" stroke-width="2"/>`,

    mug(250, 218)
  ].join('');
}

// A bead of water, with the streak it has already left above it.
function raindrop(x, y) {
  return `<path d="M${x} ${y - 18}V${y}" stroke="#ccc" stroke-width="2" stroke-linecap="round"/>` +
    `<path d="M${x} ${y}q4.5 6 0 11q-4.5-5 0-11Z" fill="#fff" stroke="#666" stroke-width="2" stroke-linejoin="round"/>`;
}

// Four beads on their own tracks and speeds, so the pane never reads as one
// row of drops marching down together.
const RAIN_TRACKS = [
  { x: 92, top: 46, span: 172, step: 29 },
  { x: 166, top: 62, span: 156, step: 21 },
  { x: 262, top: 40, span: 180, step: 37 },
  { x: 336, top: 74, span: 144, step: 25 }
];

function rainFrame(i) {
  let out = '';
  for (const track of RAIN_TRACKS) {
    out += raindrop(track.x, track.top + ((i * track.step) % track.span));
  }
  // The lamp behind the glass swells and fades; the mug goes on steaming.
  return out +
    star(308, 72, TWINKLE[i] * 1.2) +
    steamStrand(258, 214, i, 22) +
    steamStrand(266, 214, (i + 4) % FRAME_COUNT, 30);
}

/* ---------------------------------------------------------------------------
   scene 3 -- sleeping cat
--------------------------------------------------------------------------- */

function catStatic() {
  return [
    // floor + rug
    `<path d="M8 272H412" stroke="#000" stroke-width="2.5" stroke-linecap="round"/>`,
    `<ellipse cx="164" cy="280" rx="146" ry="11" fill="none" stroke="#888" stroke-width="2"/>`,

    // hearth
    `<path d="M298 272V152h100v120" fill="#fff" stroke="#000" stroke-width="2.5" stroke-linejoin="round"/>`,
    `<path d="M288 152h120v-14H288Z" fill="#fff" stroke="#000" stroke-width="2.5" stroke-linejoin="round"/>`,
    `<path d="M318 272v-62a30 30 0 0 1 60 0v62Z" fill="#fff" stroke="#000" stroke-width="2.5" stroke-linejoin="round"/>`,
    `<path d="M330 262h36M336 254h26" stroke="#000" stroke-width="3" stroke-linecap="round"/>`,
    `<path d="M300 200h-8M404 200h-8" stroke="#ccc" stroke-width="2"/>`,

    // stack of books
    `<path d="M92 262h120v10H92Z" fill="#fff" stroke="#000" stroke-width="2.5" stroke-linejoin="round"/>`,
    `<path d="M86 252h128v10H86Z" fill="#fff" stroke="#000" stroke-width="2.5" stroke-linejoin="round"/>`,
    `<path d="M96 242h108v10H96Z" fill="#fff" stroke="#000" stroke-width="2.5" stroke-linejoin="round"/>`,
    `<path d="M100 267h10M92 257h10M104 247h10" stroke="#ccc" stroke-width="2"/>`,

    // the cat, curled -- its tail stops short so a frame can finish the curl
    `<path d="M94 242q2-52 56-52q54 0 56 52Z" fill="#fff" stroke="#000" stroke-width="2.5" stroke-linejoin="round"/>`,
    `<circle cx="188" cy="212" r="21" fill="#fff" stroke="#000" stroke-width="2.5"/>`,
    `<path d="M172 197l-3-16 16 9M204 197l3-16-16 9" fill="#fff" stroke="#000" stroke-width="2.5" stroke-linejoin="round"/>`,
    `<path d="M178 210q4 5 9 0M194 210q4 5 9 0" fill="none" stroke="#000" stroke-width="2"/>`,
    `<path d="M189 219h5" stroke="#000" stroke-width="2.5" stroke-linecap="round"/>`,
    `<path d="M206 216h16M206 221h14" stroke="#ccc" stroke-width="1.6"/>`,
    `<path d="M94 232q-20 4-26 16" fill="none" stroke="#000" stroke-width="2.5" stroke-linecap="round"/>`
  ].join('');
}

function flame(x, base, h, w) {
  const f = (n) => Number(n.toFixed(1));
  return `<path d="M${x} ${base}c${-w} ${f(-h * 0.4)} ${f(-w * 0.4)} ${f(-h * 0.65)} 0 ${-h}c${f(w * 0.4)} ${f(h * 0.35)} ${w} ${f(h * 0.6)} 0 ${h}Z" fill="#fff" stroke="#000" stroke-width="2" stroke-linejoin="round"/>`;
}

// A drowsy z, drawn smaller and fainter the further it has drifted.
function zmark(x, y, size) {
  return `<path d="M${x} ${y}h${size}l${-size} ${size}h${size}" fill="none" stroke="#888" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>`;
}

const FLAME_H = [26, 32, 38, 34, 28, 34, 40, 30];

function catFrame(i) {
  const sway = [0, 4, 8, 6, 0, -5, -8, -4][i];
  const rise = i * 5;
  return [
    // fire
    flame(348, 262, FLAME_H[i], 11),
    flame(334, 262, FLAME_H[(i + 3) % FRAME_COUNT] * 0.6, 8),
    flame(362, 262, FLAME_H[(i + 5) % FRAME_COUNT] * 0.7, 8),
    // the tail tip, finishing the curl the static layer left open
    `<path d="M68 248q${-12 + sway} 2 ${-16 + sway} -16" fill="none" stroke="#000" stroke-width="2.5" stroke-linecap="round"/>`,
    // sleep marks drifting off the head
    zmark(216, 176 - rise, 13 - i),
    zmark(238, 156 - rise * 0.6, 9 - i * 0.5)
  ].join('');
}

/* ---------------------------------------------------------------------------
   scene 4 -- night sky
--------------------------------------------------------------------------- */

function pine(x, base, h) {
  const w = Number((h * 0.42).toFixed(1));
  return `<path d="M${x} ${base - h}L${x + w} ${base}H${x - w}Z" fill="#fff" stroke="#000" stroke-width="2.5" stroke-linejoin="round"/>` +
    `<path d="M${x} ${base}v7" stroke="#000" stroke-width="2.5" stroke-linecap="round"/>`;
}

function skyStatic() {
  return [
    // moon
    `<path d="M330 60a28 28 0 1 0 0 56a22 22 0 0 1 0-56Z" fill="#000"/>`,

    // constellation, joined with a hairline
    `<path d="M78 76 122 104 168 72 214 100 246 62" fill="none" stroke="#ccc" stroke-width="1.6"/>`,
    star(78, 76, 3),
    star(122, 104, 2.4),
    star(168, 72, 2.8),
    star(214, 100, 2.4),
    star(246, 62, 3),
    star(56, 130, 2.2),
    star(384, 132, 2.4),
    star(292, 30, 2),

    // hills, horizon, trees
    `<path d="M8 246q66-54 132-28t118-40 154 26" fill="none" stroke="#000" stroke-width="2.5"/>`,
    `<path d="M8 246H412" stroke="#000" stroke-width="2.5" stroke-linecap="round"/>`,
    pine(58, 246, 40),
    pine(92, 246, 28),
    pine(366, 246, 34),
    pine(392, 246, 24),

    // bench, facing the view
    `<path d="M172 232h72v7h-72Z" fill="#fff" stroke="#000" stroke-width="2.5" stroke-linejoin="round"/>`,
    `<path d="M180 239v18M236 239v18" stroke="#000" stroke-width="2.5" stroke-linecap="round"/>`,
    `<path d="M172 224h72" stroke="#000" stroke-width="2.5" stroke-linecap="round"/>`,
    `<path d="M176 232v-16M240 232v-16" stroke="#000" stroke-width="2.5" stroke-linecap="round"/>`,
    `<ellipse cx="208" cy="262" rx="60" ry="7" fill="none" stroke="#ccc" stroke-width="2"/>`
  ].join('');
}

function cloud(x, y) {
  return `<path d="M${x} ${y}a15 15 0 0 1 28-7a17 17 0 0 1 32 7a10 10 0 0 1-5 12H${x + 5}a10 10 0 0 1-5-12Z" fill="#fff" stroke="#000" stroke-width="2" stroke-linejoin="round"/>`;
}

function skyFrame(i) {
  const out = [
    // a thin cloud crossing the moon, left to right over the loop
    cloud(236 + i * 20, 92),
    star(78, 76, TWINKLE[i]),
    star(168, 72, TWINKLE[(i + 2) % FRAME_COUNT] - 0.4),
    star(246, 62, TWINKLE[(i + 5) % FRAME_COUNT]),
    star(384, 132, TWINKLE[(i + 3) % FRAME_COUNT] - 1)
  ];
  // One frame in eight gets a shooting star -- rare enough to feel like luck.
  if (i === 5) {
    out.push(`<path d="M120 38l34 20" stroke="#000" stroke-width="2" stroke-linecap="round"/>`);
    out.push(star(156, 59, 3));
  }
  return out.join('');
}

/* ---------------------------------------------------------------------------
   scene 5 -- desk still life
--------------------------------------------------------------------------- */

function deskStatic() {
  return [
    // desk
    `<path d="M24 246H396" stroke="#000" stroke-width="3" stroke-linecap="round"/>`,
    `<path d="M52 246v34M368 246v34" stroke="#000" stroke-width="3" stroke-linecap="round"/>`,
    `<path d="M24 252H396" stroke="#ccc" stroke-width="2"/>`,

    // lamp
    `<ellipse cx="86" cy="244" rx="28" ry="6" fill="#fff" stroke="#000" stroke-width="2.5"/>`,
    `<path d="M86 240V152q0-16 16-16h18" fill="none" stroke="#000" stroke-width="3" stroke-linecap="round"/>`,
    `<path d="M120 118h36l16 38h-68Z" fill="#fff" stroke="#000" stroke-width="2.5" stroke-linejoin="round"/>`,

    // open notebook
    `<path d="M196 240 150 226l8-20 44 16Z" fill="#fff" stroke="#000" stroke-width="2.5" stroke-linejoin="round"/>`,
    `<path d="M196 240 242 226l-8-20-44 16Z" fill="#fff" stroke="#000" stroke-width="2.5" stroke-linejoin="round"/>`,
    `<path d="M162 218l28 10M166 212l26 9M206 226l26-9M210 232l24-9" stroke="#ccc" stroke-width="1.8"/>`,

    // pen
    `<path d="M256 240l32-13" stroke="#000" stroke-width="3" stroke-linecap="round"/>`,
    `<path d="M288 227l9-4l-4 9Z" fill="#fff" stroke="#000" stroke-width="2.5" stroke-linejoin="round"/>`,

    // plant, stems only -- the leaves are frame work
    `<path d="M318 222h48l-7 24h-34Z" fill="#fff" stroke="#000" stroke-width="2.5" stroke-linejoin="round"/>`,
    `<path d="M322 230h40" stroke="#ccc" stroke-width="2"/>`,
    `<path d="M342 222v-24M342 212q-12-6-16-18M342 206q12-6 16-20" fill="none" stroke="#000" stroke-width="2.5" stroke-linecap="round"/>`
  ].join('');
}

// A leaf blade grown along `ang` from a stem tip, drawn as two mirrored curves
// so a few degrees of sway reads as the whole leaf nodding.
function leaf(x, y, ang, len) {
  const rad = (ang * Math.PI) / 180;
  const tx = x + Math.cos(rad) * len;
  const ty = y + Math.sin(rad) * len;
  const mx = (x + tx) / 2;
  const my = (y + ty) / 2;
  const nx = -Math.sin(rad) * len * 0.34;
  const ny = Math.cos(rad) * len * 0.34;
  const f = (n) => n.toFixed(1);
  return `<path d="M${f(x)} ${f(y)}Q${f(mx + nx)} ${f(my + ny)} ${f(tx)} ${f(ty)}Q${f(mx - nx)} ${f(my - ny)} ${f(x)} ${f(y)}Z" fill="#fff" stroke="#000" stroke-width="2.5" stroke-linejoin="round"/>`;
}

const SWAY = [0, 3, 6, 4, 0, -4, -6, -3];

function deskFrame(i) {
  const s = SWAY[i];
  const t = SWAY[(i + 3) % FRAME_COUNT];
  return [
    // lamplight, reaching a little further on some frames
    `<g stroke="#888" stroke-width="1.8" stroke-linecap="round" stroke-dasharray="7 9">` +
      `<path d="M104 160 ${86 + i * 3} ${218 + i}"/>` +
      `<path d="M136 160 152 ${214 + (i % 3) * 4}"/>` +
      `<path d="M168 160 ${210 - i * 2} ${216 + i}"/>` +
      `</g>`,
    // leaves at the three stem tips
    leaf(342, 198, -90 + s, 26),
    leaf(326, 194, -122 + t, 22),
    leaf(358, 186, -58 - s, 24),
    // the top page lifting at its corner
    `<path d="M196 240 ${(242 + s * 0.6).toFixed(1)} ${(226 - Math.abs(s) * 0.8).toFixed(1)}l-8-20-44 16Z" fill="#fff" stroke="#000" stroke-width="2.5" stroke-linejoin="round"/>`
  ].join('');
}

/* ---------------------------------------------------------------------------
   theming
--------------------------------------------------------------------------- */

// The art is authored in black on white. Rather than keep a second palette
// per scene, each shape is tagged with the role its colour plays and the
// stylesheets repaint those roles for dark mode. The literal fill and stroke
// attributes stay put, so a browser that ignores CSS on SVG still gets the
// light scene exactly right.
const FILL_ROLE = {
  '#000': 'f-ink', '#fff': 'f-paper',
  '#666': 'f-soft', '#888': 'f-soft', '#999': 'f-soft',
  '#ccc': 'f-faint', '#ddd': 'f-faint'
};

const STROKE_ROLE = {
  '#000': 's-ink', '#fff': 's-paper',
  '#666': 's-soft', '#888': 's-soft', '#999': 's-soft',
  '#ccc': 's-faint', '#ddd': 's-faint'
};

function themed(markup) {
  return markup.replace(/<(path|circle|ellipse|rect|line|polyline|g)\b([^>]*?)(\/?)>/g,
    (tag, name, attrs, close) => {
      const roles = [];
      const fill = /\bfill="(#[0-9a-fA-F]{3,6})"/.exec(attrs);
      const stroke = /\bstroke="(#[0-9a-fA-F]{3,6})"/.exec(attrs);
      if (fill && FILL_ROLE[fill[1]]) roles.push(FILL_ROLE[fill[1]]);
      if (stroke && STROKE_ROLE[stroke[1]]) roles.push(STROKE_ROLE[stroke[1]]);
      if (!roles.length) return tag;
      return `<${name}${attrs} class="${roles.join(' ')}"${close}>`;
    });
}

/* ---------------------------------------------------------------------------
   the catalogue
--------------------------------------------------------------------------- */

const SCENE_DEFS = [
  { id: 'reading', name: 'Reading nook', statics: readingStatic, frame: readingFrame },
  { id: 'rain', name: 'Rain on a window', statics: rainStatic, frame: rainFrame },
  { id: 'cat', name: 'Sleeping cat', statics: catStatic, frame: catFrame },
  { id: 'sky', name: 'Night sky', statics: skyStatic, frame: skyFrame },
  { id: 'desk', name: 'Desk still life', statics: deskStatic, frame: deskFrame }
];

const DEFAULT_SCENE = SCENE_DEFS[0].id;

// Built once, at require time: none of this is recomputed per request.
const BUILT = {};
for (const def of SCENE_DEFS) {
  BUILT[def.id] = {
    statics: themed(def.statics()),
    frames: Array.from({ length: FRAME_COUNT }, (_, i) => themed(def.frame(i)))
  };
}

// A scan rather than a property lookup: the id arrives from a stored file or
// a client action, and `scene = 'constructor'` would walk Object.prototype.
function isScene(id) {
  return SCENE_DEFS.some((def) => def.id === id);
}

function svgOpen(attrs) {
  return `<svg ${attrs} viewBox="0 0 420 300" preserveAspectRatio="xMidYMid meet" aria-hidden="true">`;
}

// Every scene is inlined into the page and only the chosen one is displayed.
// Switching is then a class swap over markup the device already holds, which
// costs one small repaint instead of a page load.
function renderScenes(active) {
  const chosen = isScene(active) ? active : DEFAULT_SCENE;
  return SCENE_DEFS.map((def) => {
    const built = BUILT[def.id];
    const frames = built.frames
      .map((markup, i) => `<g id="fr-${def.id}-${i}" class="fr${i === 0 ? ' on' : ''}">${markup}</g>`)
      .join('');
    return svgOpen(`id="sc-${def.id}" class="sc${def.id === chosen ? ' on' : ''}"`) +
      `<g>${built.statics}</g>${frames}</svg>`;
  }).join('');
}

// The picker's thumbnails: static layer plus the first frame, so even a still
// preview shows the parts that move.
function renderThumb(id) {
  const built = BUILT[isScene(id) ? id : DEFAULT_SCENE];
  return svgOpen('class="th"') + `<g>${built.statics}</g><g>${built.frames[0]}</g></svg>`;
}

module.exports = {
  SCENES: SCENE_DEFS.map((def) => ({ id: def.id, name: def.name })),
  DEFAULT_SCENE,
  isScene,
  renderScenes,
  renderThumb,
  FRAME_COUNT,
  FRAME_MS
};
