'use strict';

// The idle scene is split in two so the Kindle only ever repaints a few small
// regions: a static line-art vignette drawn once, plus a short loop of tiny
// overlay frames. Everything here is generated at boot and inlined into the
// page, so the device never fetches or computes an animation frame.

const FRAME_COUNT = 8;
const FRAME_MS = 6000;

function star(x, y, r) {
  const i = r * 0.3;
  return `<path d="M${x} ${y - r}L${x + i} ${y - i}L${x + r} ${y}L${x + i} ${y + i}L${x} ${y + r}L${x - i} ${y + i}L${x - r} ${y}L${x - i} ${y - i}Z" fill="#000"/>`;
}

function staticScene() {
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
    `<path d="M230 196h20v14a6 6 0 0 1-6 6h-8a6 6 0 0 1-6-6Z" fill="#fff" stroke="#000" stroke-width="2.5" stroke-linejoin="round"/>`,
    `<path d="M250 200h5a5 5 0 0 1 0 10h-5" fill="none" stroke="#000" stroke-width="2.5"/>`
  ].join('');
}

function steamStrand(x, phase, height) {
  const dir = phase % 2 === 0 ? 1 : -1;
  const lift = (phase % 4) * 3;
  const top = 192 - height - lift;
  const bend = 7 * dir;
  return `<path d="M${x} ${192 - lift}c${bend} -9 ${-bend} -14 0 -23c${bend} -9 ${-bend} -14 0 -${Math.max(10, 192 - lift - top - 23)}" fill="none" stroke="#666" stroke-width="2" stroke-linecap="round"/>`;
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
const TWINKLE = [3, 3.6, 4.3, 4.8, 4.3, 3.6, 3, 3];

function frame(i) {
  return [
    steamStrand(234, i, 26),
    steamStrand(242, (i + 3) % FRAME_COUNT, 34),
    steamStrand(249, (i + 5) % FRAME_COUNT, 20),
    turningPage(PAGE_PROGRESS[i]),
    star(300, 64, TWINKLE[i]),
    star(76, 108, TWINKLE[(i + 4) % FRAME_COUNT] - 1)
  ].join('');
}

const FRAMES = Array.from({ length: FRAME_COUNT }, (_, i) => frame(i));
const STATIC = staticScene();

function renderScene() {
  const groups = FRAMES
    .map((markup, i) => `<g id="fr${i}" class="fr${i === 0 ? ' on' : ''}">${markup}</g>`)
    .join('');
  return `<svg id="scene" viewBox="0 0 420 300" preserveAspectRatio="xMidYMid meet" aria-hidden="true">` +
    `<g>${STATIC}</g>${groups}</svg>`;
}

module.exports = { renderScene, FRAME_COUNT, FRAME_MS };
