// Copyright (c) 2026-present devtank42 GmbH
// SPDX-License-Identifier: AGPL-3.0-only
//
// Regenerates the qnop brand assets from scratch:
//   - qnop-core/src/main/resources/branding/defaults/{logo-light,logo-dark,logomark}.svg
//   - qnop-ui/public/{favicon.svg,favicon-32.png,apple-touch-icon.png,og-image.png}
//
// Usage (from docs/branding/):
//   1. Download the Manrope variable font (SIL OFL 1.1) next to this script:
//      curl -sfL -o "Manrope[wght].ttf" \
//        "https://raw.githubusercontent.com/google/fonts/main/ofl/manrope/Manrope%5Bwght%5D.ttf"
//   2. npm install
//   3. npm run generate
//
// See README.md for the design rationale (issue #153).

import * as fontkit from 'fontkit';
import sharp from 'sharp';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.resolve(HERE, '..', '..');
const DEFAULTS = path.join(REPO, 'qnop-core/src/main/resources/branding/defaults');
const PUBLIC = path.join(REPO, 'qnop-ui/public');

const NAVY = '#012142';
const BLUE = '#1292EE';
const WHITE = '#FFFFFF';
// The mark itself is the capital "Q" of the wordmark; only "nop" is set in
// type, scaled so its x-height is 22 units — two thirds of the 33-unit mark
// circle, the classic x-height : cap-height ratio.
const X_HEIGHT = 22;
const WORDMARK_REST = 'nop';

// --- speech-bubble "q" logomark -------------------------------------------
// viewBox-64 coordinates: bubble circle c=(31,29) r=22 with a pointed tail
// towards the bottom right (gap between 15deg and 65deg, tip at (56,53)),
// transparent counter (r=12) punched via fill-rule, annotation dot r=6.6
// (55% of the counter diameter, so it survives 16x16 favicon rendering).
const MARK_D =
  'M52.25 34.69A22 22 0 1 0 40.3 48.94L56 53ZM43 29A12 12 0 1 0 19 29A12 12 0 1 0 43 29Z';
const mark = (fill) =>
  `<path fill-rule="evenodd" fill="${fill}" d="${MARK_D}"/><circle cx="31" cy="29" r="6.6" fill="${BLUE}"/>`;

// --- wordmark rest ("nop"): Manrope Bold outlines --------------------------
const fontFile = path.join(HERE, 'Manrope[wght].ttf');
if (!fs.existsSync(fontFile)) {
  console.error(`Missing ${fontFile} — see the usage notes at the top of this script.`);
  process.exit(1);
}
const font = fontkit.openSync(fontFile).getVariation({ wght: 700 });
const run = font.layout(WORDMARK_REST);
const scale = X_HEIGHT / font.xHeight;
let x = 0;
let textD = '';
run.glyphs.forEach((glyph, i) => {
  const pos = run.positions[i];
  // flip y (font coords are y-up, SVG is y-down); baseline at y=0
  textD += glyph.path.scale(scale, -scale).translate((x + pos.xOffset) * scale, 0).toSVG();
  x += pos.xAdvance;
});

const BRAND_HEADER = `<!-- qnop brand asset. Copyright (c) 2026-present devtank42 GmbH. -->`;
const WORDMARK_HEADER = `<!-- qnop brand asset. Copyright (c) 2026-present devtank42 GmbH.
     Wordmark set in Manrope (SIL Open Font License 1.1), embedded as outlines. -->`;

// --- compose SVGs -----------------------------------------------------------
// Lockup "Qnop": the mark IS the capital Q — sized to cap height (circle
// bottom overshoots the baseline by ~1 unit like a round capital, tail
// dipping below like a Q's swash), with "nop" joined tightly at word
// distance. Baseline y=41.5; mark translate-y 4.25 puts the circle center
// at y=26 (top 9.5, bottom 42.5); "nop" starts at x=47.
const textWidth = x * scale;
const LOCKUP_W = Math.ceil(47 + textWidth + 3);
const lockup = (fill) => `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${LOCKUP_W} 64" fill="none" role="img" aria-label="qnop">
${WORDMARK_HEADER}
  <g transform="translate(2 4.25) scale(0.75)">${mark(fill)}</g>
  <path fill="${fill}" transform="translate(47 41.5)" d="${textD}"/>
</svg>
`;
const tile = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" fill="none" role="img" aria-label="qnop">
${BRAND_HEADER}
  <rect width="64" height="64" rx="14" fill="${NAVY}"/>
  <g transform="translate(7.5 8) scale(0.76)">${mark(WHITE)}</g>
</svg>
`;

fs.writeFileSync(path.join(DEFAULTS, 'logo-light.svg'), lockup(NAVY));
fs.writeFileSync(path.join(DEFAULTS, 'logo-dark.svg'), lockup(WHITE));
fs.writeFileSync(path.join(DEFAULTS, 'logomark.svg'), tile);
fs.writeFileSync(path.join(PUBLIC, 'favicon.svg'), tile);

// --- PNG derivatives --------------------------------------------------------
const tileBuf = Buffer.from(tile);
await sharp(tileBuf, { density: (72 * 32) / 64 })
  .resize(32, 32)
  .png()
  .toFile(path.join(PUBLIC, 'favicon-32.png'));

// apple-touch: opaque full-bleed navy, no rounded corners (iOS masks itself)
const apple = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" fill="${NAVY}"/><g transform="translate(9.5 10) scale(0.7)">${mark(WHITE)}</g></svg>`;
await sharp(Buffer.from(apple), { density: (72 * 180) / 64 })
  .resize(180, 180)
  .png()
  .toFile(path.join(PUBLIC, 'apple-touch-icon.png'));

// OG/social image: dark lockup on black (the dark-mode base color)
const og = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1200 630">
  <rect width="1200" height="630" fill="#000000"/>
  <g transform="translate(${(1200 - LOCKUP_W * 3) / 2} 234) scale(3)">
    <g transform="translate(2 4.25) scale(0.75)">${mark(WHITE)}</g>
    <path fill="${WHITE}" transform="translate(47 41.5)" d="${textD}"/>
  </g>
  <text x="600" y="460" text-anchor="middle" font-family="Helvetica, Arial, sans-serif" font-size="30" fill="#9AA7B4">Qualified Notes on Papers</text>
</svg>`;
await sharp(Buffer.from(og), { density: 72 })
  .resize(1200, 630)
  .png()
  .toFile(path.join(PUBLIC, 'og-image.png'));

console.log('Regenerated brand assets in:');
console.log('  ' + DEFAULTS);
console.log('  ' + PUBLIC);
