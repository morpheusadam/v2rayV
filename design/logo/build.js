const fs = require('fs');
const path = require('path');
const sharp = require('sharp');

const OUT = path.resolve(__dirname);

// ---------------------------------------------------------------- palette
// Sampled from the source artwork: a navy mark on brushed silver.
const C = {
  blueLit:  '#4C8AC9',
  blue:     '#2A62A8',
  blueMid:  '#123E7E',
  blueDeep: '#08275F',
  ink:      '#041D50',
  silver:   '#F4F6FA',
  silverMid:'#DCE2EC',
  silverLow:'#B3BFD3',
  steel:    '#8494AE',
};

// ---------------------------------------------------------------- geometry
// The mark is a straight-edged fox/wolf head that also reads as an "M".
// Vertices were traced from the source render and fitted back to it
// (IoU 0.95); everything is mirrored around the axis below, so the shape is
// exactly symmetric. Outer ring is wound clockwise and every hole
// counter-clockwise, which makes the default non-zero fill rule punch the
// holes out — no `evenOdd` needed, so the path works in any renderer.
const AX = 511.5;
const mx = (x) => 2 * AX - x;
const mirror = (p) => p.map(([x, y]) => [mx(x), y]).reverse();

const OUTER = [[310, 219], [AX, 436], [713, 219], [713, 463],
               [782, 563], [AX, 802], [241, 563], [310, 463]];
const H_TOP  = [[368, 317], [AX, 634], [655, 317], [AX, 469]];
const H_SIDE = [[334, 303], [334, 468], [266, 560], [440, 714], [337, 558], [407, 461]];
const H_EYE  = [[418, 484], [364, 558], [486, 631]];
const H_BOT  = [[391, 600], [AX, 779], [632, 600], [AX, 669]];
const HOLES  = [H_TOP, H_SIDE, mirror(H_SIDE), H_EYE, mirror(H_EYE), H_BOT];

const ART = { x: 241, y: 219, w: 541, h: 583 };   // bbox of OUTER

const isCW = (p) =>
  p.reduce((s, [x, y], i) => {
    const [x2, y2] = p[(i + 1) % p.length];
    return s + (x2 - x) * (y2 + y);
  }, 0) > 0;
const wind = (p, cw) => (isCW(p) === cw ? p : [...p].reverse());

// mark scaled to height `h`, centred on (cx, cy)
function markPath(cx, cy, h, prec = 2) {
  const s = h / ART.h;
  const ox = ART.x + ART.w / 2;
  const oy = ART.y + ART.h / 2;
  const sub = (p) =>
    'M' + p.map(([x, y]) =>
      `${(cx + (x - ox) * s).toFixed(prec)},${(cy + (y - oy) * s).toFixed(prec)}`
    ).join('L') + 'Z';
  return [wind(OUTER, true), ...HOLES.map((p) => wind(p, false))].map(sub).join(' ');
}
const markW = (h) => (h * ART.w) / ART.h;

// squircle (superellipse) — the card silhouette
function squircle(cx, cy, r, n = 4.3, steps = 192) {
  const pts = [];
  for (let i = 0; i < steps; i++) {
    const t = (2 * Math.PI * i) / steps;
    const ct = Math.cos(t), st = Math.sin(t);
    pts.push([
      cx + Math.sign(ct) * Math.pow(Math.abs(ct), 2 / n) * r,
      cy + Math.sign(st) * Math.pow(Math.abs(st), 2 / n) * r,
    ]);
  }
  return 'M' + pts.map(([x, y]) => `${x.toFixed(2)},${y.toFixed(2)}`).join('L') + 'Z';
}

function circle(cx, cy, r, steps = 192) {
  const pts = [];
  for (let i = 0; i < steps; i++) {
    const t = (2 * Math.PI * i) / steps;
    pts.push([cx + Math.cos(t) * r, cy + Math.sin(t) * r]);
  }
  return 'M' + pts.map(([x, y]) => `${x.toFixed(2)},${y.toFixed(2)}`).join('L') + 'Z';
}

// ---------------------------------------------------------------- glass card
// Frosted pane: body tint, top-left sheen, cool tint pooling bottom-right,
// a lit rim and a thin inner edge. Nothing here is opaque, so the card keeps
// reading as glass over whatever is behind it.
function cardDefs(id, shape, b) {
  const { x, y, w, h } = b;
  return `
  <linearGradient id="${id}Body" gradientUnits="userSpaceOnUse"
                  x1="${x + w * 0.12}" y1="${y}" x2="${x + w * 0.88}" y2="${y + h}">
    <stop offset="0"    stop-color="#FFFFFF" stop-opacity="0.90"/>
    <stop offset="0.34" stop-color="#EEF2F8" stop-opacity="0.74"/>
    <stop offset="0.68" stop-color="#C2CFE4" stop-opacity="0.64"/>
    <stop offset="1"    stop-color="#93A7C7" stop-opacity="0.70"/>
  </linearGradient>
  <radialGradient id="${id}Sheen" gradientUnits="userSpaceOnUse"
                  cx="${x + w * 0.30}" cy="${y + h * 0.18}" r="${w * 0.80}">
    <stop offset="0"    stop-color="#FFFFFF" stop-opacity="0.62"/>
    <stop offset="0.42" stop-color="#FFFFFF" stop-opacity="0.16"/>
    <stop offset="1"    stop-color="#FFFFFF" stop-opacity="0"/>
  </radialGradient>
  <radialGradient id="${id}Tint" gradientUnits="userSpaceOnUse"
                  cx="${x + w * 0.90}" cy="${y + h * 0.94}" r="${w * 0.72}">
    <stop offset="0"   stop-color="#2C63A8" stop-opacity="0.30"/>
    <stop offset="1"   stop-color="#2C63A8" stop-opacity="0"/>
  </radialGradient>
  <linearGradient id="${id}Sweep" gradientUnits="userSpaceOnUse"
                  x1="${x}" y1="${y}" x2="${x + w * 0.80}" y2="${y + h * 0.80}">
    <stop offset="0"    stop-color="#FFFFFF" stop-opacity="0.50"/>
    <stop offset="0.26" stop-color="#FFFFFF" stop-opacity="0.10"/>
    <stop offset="0.55" stop-color="#FFFFFF" stop-opacity="0"/>
  </linearGradient>
  <linearGradient id="${id}Rim" gradientUnits="userSpaceOnUse"
                  x1="${x}" y1="${y}" x2="${x + w}" y2="${y + h}">
    <stop offset="0"    stop-color="#FFFFFF" stop-opacity="0.98"/>
    <stop offset="0.26" stop-color="#FFFFFF" stop-opacity="0.38"/>
    <stop offset="0.60" stop-color="#8DA2C2" stop-opacity="0.38"/>
    <stop offset="1"    stop-color="#FFFFFF" stop-opacity="0.88"/>
  </linearGradient>
  <clipPath id="${id}Clip"><path d="${shape}"/></clipPath>`;
}

function cardBody(id, shape, inner, b) {
  const rim = b.w * 0.014;
  return `
  <path d="${shape}" fill="url(#${id}Body)"/>
  <g clip-path="url(#${id}Clip)">
    <path d="${shape}" fill="url(#${id}Sheen)"/>
    <path d="${shape}" fill="url(#${id}Tint)"/>
    <path d="${shape}" fill="url(#${id}Sweep)"/>
    <path d="${shape}" fill="none" stroke="url(#${id}Rim)" stroke-width="${(rim * 2).toFixed(2)}"/>
  </g>
  <path d="${inner}" fill="none" stroke="#FFFFFF" stroke-opacity="0.34"
        stroke-width="${(rim * 0.42).toFixed(2)}"/>`;
}

// ---------------------------------------------------------------- mark
// Same three passes as the card: gradient body, gloss over the top half,
// and a lit inner bevel clipped to the silhouette so the outline stays crisp.
// `deep` sits on the glass card, which is always light. `lit` is the standalone
// mark: the same hue lifted so the lower half still reads on a dark surface.
const TONE = {
  deep: [C.blueLit, C.blue, C.blueMid, C.blueDeep, C.ink],
  lit:  ['#6FA9E2', '#3E7CC0', '#235A9E', '#17457F', '#133C72'],
};

function markDefs(id, cx, cy, h, tone = 'deep') {
  const w = markW(h);
  const t = cy - h / 2;
  const bt = cy + h / 2;
  const [c0, c1, c2, c3, c4] = TONE[tone];
  return `
  <linearGradient id="${id}Fill" gradientUnits="userSpaceOnUse"
                  x1="${(cx - w * 0.42).toFixed(1)}" y1="${t.toFixed(1)}"
                  x2="${(cx + w * 0.46).toFixed(1)}" y2="${bt.toFixed(1)}">
    <stop offset="0"    stop-color="${c0}"/>
    <stop offset="0.22" stop-color="${c1}"/>
    <stop offset="0.55" stop-color="${c2}"/>
    <stop offset="0.82" stop-color="${c3}"/>
    <stop offset="1"    stop-color="${c4}"/>
  </linearGradient>
  <linearGradient id="${id}Gloss" gradientUnits="userSpaceOnUse"
                  x1="${cx}" y1="${t.toFixed(1)}" x2="${cx}" y2="${(t + h * 0.66).toFixed(1)}">
    <stop offset="0"    stop-color="#FFFFFF" stop-opacity="0.26"/>
    <stop offset="0.45" stop-color="#FFFFFF" stop-opacity="0.07"/>
    <stop offset="1"    stop-color="#FFFFFF" stop-opacity="0"/>
  </linearGradient>
  <linearGradient id="${id}Edge" gradientUnits="userSpaceOnUse"
                  x1="${(cx - w * 0.40).toFixed(1)}" y1="${t.toFixed(1)}"
                  x2="${(cx + w * 0.40).toFixed(1)}" y2="${bt.toFixed(1)}">
    <stop offset="0"    stop-color="#DCEAFF" stop-opacity="0.80"/>
    <stop offset="0.40" stop-color="#FFFFFF" stop-opacity="0.14"/>
    <stop offset="1"    stop-color="#7FB2FF" stop-opacity="0.34"/>
  </linearGradient>
  <clipPath id="${id}Clip"><path d="${markPath(cx, cy, h)}"/></clipPath>
  <filter id="${id}Drop" x="-25%" y="-25%" width="150%" height="150%">
    <feGaussianBlur stdDeviation="${(h * 0.017).toFixed(2)}"/>
  </filter>`;
}

function markBody(id, cx, cy, h, { shadow = true } = {}) {
  const d = markPath(cx, cy, h);
  const drop = shadow
    ? `<g filter="url(#${id}Drop)" opacity="0.34">
    <path d="${d}" transform="translate(0,${(h * 0.013).toFixed(2)})" fill="#04173C"/>
  </g>`
    : '';
  return `${drop}
  <path d="${d}" fill="url(#${id}Fill)"/>
  <g clip-path="url(#${id}Clip)">
    <path d="${d}" fill="url(#${id}Gloss)"/>
    <path d="${d}" fill="none" stroke="url(#${id}Edge)"
          stroke-width="${(h * 0.0062).toFixed(2)}" stroke-linejoin="round"/>
  </g>`;
}

// ---------------------------------------------------------------- documents
const CANVAS = 1024;
const CARD = { cx: 512, cy: 512, r: 424 };
const CARD_BOX = { x: CARD.cx - CARD.r, y: CARD.cy - CARD.r, w: CARD.r * 2, h: CARD.r * 2 };
const MARK_ON_CARD = 583;          // same proportion as the source artwork
const DISC = { cx: 512, cy: 512, r: 500 };
const DISC_BOX = { x: 12, y: 12, w: 1000, h: 1000 };

const svg = (px, defs, body) =>
  `<svg xmlns="http://www.w3.org/2000/svg" width="${px}" height="${px}" ` +
  `viewBox="0 0 ${CANVAS} ${CANVAS}">\n<defs>${defs}\n</defs>\n${body}\n</svg>\n`;

const svgBox = (w, h, scale, defs, body) =>
  `<svg xmlns="http://www.w3.org/2000/svg" width="${w * scale}" height="${h * scale}" ` +
  `viewBox="0 0 ${w} ${h}">\n<defs>${defs}\n</defs>\n${body}\n</svg>\n`;

// 1. the icon: glass card + mark, fully transparent outside the card
function glass(px = CANVAS) {
  const shape = squircle(CARD.cx, CARD.cy, CARD.r);
  const inner = squircle(CARD.cx, CARD.cy, CARD.r - CARD.r * 0.028);
  return svg(px,
    cardDefs('c', shape, CARD_BOX) + markDefs('m', 512, 512, MARK_ON_CARD),
    cardBody('c', shape, inner, CARD_BOX) + markBody('m', 512, 512, MARK_ON_CARD));
}

// 2. round variant, for launchers that ask for a circular icon
function glassRound(px = CANVAS) {
  const shape = circle(DISC.cx, DISC.cy, DISC.r);
  const inner = circle(DISC.cx, DISC.cy, DISC.r - DISC.r * 0.028);
  const h = 620;
  return svg(px,
    cardDefs('c', shape, DISC_BOX) + markDefs('m', 512, 512, h),
    cardBody('c', shape, inner, DISC_BOX) + markBody('m', 512, 512, h));
}

// 3. the bare mark — inside the app, where a card would be noise
function markOnly(px = CANVAS, h = 920, shadow = false) {
  return svg(px, markDefs('m', 512, 512, h, 'lit'),
    markBody('m', 512, 512, h, { shadow }));
}

// 4. single colour, for the status bar / themed icon / print
function mono(px, color, h = 920) {
  return svg(px, '', `<path d="${markPath(512, 512, h)}" fill="${color}"/>`);
}

// 5. opaque, for stores that reject transparency
function solid(px = CANVAS) {
  const shape = squircle(CARD.cx, CARD.cy, CARD.r);
  const inner = squircle(CARD.cx, CARD.cy, CARD.r - CARD.r * 0.028);
  const defs = `
  <linearGradient id="bg" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="1024" y2="1024">
    <stop offset="0"   stop-color="#F7F9FC"/>
    <stop offset="0.55" stop-color="#E4E9F1"/>
    <stop offset="1"   stop-color="#CBD4E2"/>
  </linearGradient>
  <filter id="cast" x="-30%" y="-30%" width="160%" height="160%">
    <feGaussianBlur stdDeviation="26"/>
  </filter>` + cardDefs('c', shape, CARD_BOX) + markDefs('m', 512, 512, MARK_ON_CARD);
  const body = `<rect width="1024" height="1024" fill="url(#bg)"/>
  <g filter="url(#cast)" opacity="0.30">
    <path d="${shape}" transform="translate(0,22)" fill="#4B5A73"/>
  </g>
  ` + cardBody('c', shape, inner, CARD_BOX) + markBody('m', 512, 512, MARK_ON_CARD);
  return svg(px, defs, body);
}

// 6/7. adaptive icon layers. 1024 viewport == 108dp; the mark is kept at
// 50dp so it survives every launcher mask, and the background is full bleed.
const DP = CANVAS / 108;
const ADAPTIVE_MARK = Math.round(50 * DP);

function adaptiveBackground(px = CANVAS) {
  const defs = `
  <linearGradient id="ab" gradientUnits="userSpaceOnUse" x1="120" y1="0" x2="900" y2="1024">
    <stop offset="0"    stop-color="#FFFFFF"/>
    <stop offset="0.34" stop-color="#EAEFF7"/>
    <stop offset="0.70" stop-color="#C4D0E3"/>
    <stop offset="1"    stop-color="#9FB1CC"/>
  </linearGradient>
  <radialGradient id="as" gradientUnits="userSpaceOnUse" cx="300" cy="190" r="820">
    <stop offset="0"    stop-color="#FFFFFF" stop-opacity="0.85"/>
    <stop offset="0.45" stop-color="#FFFFFF" stop-opacity="0.20"/>
    <stop offset="1"    stop-color="#FFFFFF" stop-opacity="0"/>
  </radialGradient>
  <radialGradient id="at" gradientUnits="userSpaceOnUse" cx="920" cy="960" r="740">
    <stop offset="0"   stop-color="#2C63A8" stop-opacity="0.28"/>
    <stop offset="1"   stop-color="#2C63A8" stop-opacity="0"/>
  </radialGradient>`;
  const body = `<rect width="1024" height="1024" fill="url(#ab)"/>
  <rect width="1024" height="1024" fill="url(#as)"/>
  <rect width="1024" height="1024" fill="url(#at)"/>`;
  return svg(px, defs, body);
}

function adaptiveForeground(px = CANVAS) {
  return svg(px, markDefs('m', 512, 512, ADAPTIVE_MARK),
    markBody('m', 512, 512, ADAPTIVE_MARK));
}

// 8. Android TV banner, 320x180dp. The adaptive form insets the foreground by
// 10%, so the transparent layer carries a bigger mark than the flat fallback.
const BANNER = { w: 320, h: 180 };
const BANNER_BG = '#E4E9F1';

function bannerForeground(scale = 1) {
  const { w, h } = BANNER;
  const mh = 150;
  const cx = w / 2, cy = h / 2;
  const s = mh / CANVAS;
  const inner = (cx0) => `<g transform="translate(${cx0},${cy}) scale(${s}) translate(-512,-512)">`;
  return svgBox(w, h, scale, markDefs('m', 512, 512, 940),
    `${inner(cx)}${markBody('m', 512, 512, 940, { shadow: false })}</g>`);
}

function bannerFlat(scale = 1) {
  const { w, h } = BANNER;
  const mh = 120;
  const s = mh / CANVAS;
  const defs = `
  <linearGradient id="bb" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="${w}" y2="${h}">
    <stop offset="0"    stop-color="#F7F9FC"/>
    <stop offset="0.55" stop-color="${BANNER_BG}"/>
    <stop offset="1"    stop-color="#C6D0E1"/>
  </linearGradient>
  <radialGradient id="bs" gradientUnits="userSpaceOnUse" cx="${w * 0.28}" cy="${h * 0.16}" r="${w * 0.62}">
    <stop offset="0"   stop-color="#FFFFFF" stop-opacity="0.85"/>
    <stop offset="1"   stop-color="#FFFFFF" stop-opacity="0"/>
  </radialGradient>` + markDefs('m', 512, 512, 940);
  const body = `<rect width="${w}" height="${h}" fill="url(#bb)"/>
  <rect width="${w}" height="${h}" fill="url(#bs)"/>
  <g transform="translate(${w / 2},${h / 2}) scale(${s}) translate(-512,-512)">
    ${markBody('m', 512, 512, 940, { shadow: false })}
  </g>`;
  return svgBox(w, h, scale, defs, body);
}

const bannerColorXml = () => `<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- The glass card's silver, so the TV banner and the app icon read as the
         same mark rather than two different ones. -->
    <color name="ic_banner_background">${BANNER_BG}</color>
</resources>
`;

// ---------------------------------------------------------------- android xml
const vecHead = (dp, extra = '') =>
  `<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="${dp}dp"
    android:height="${dp}dp"
    android:viewportWidth="1024"
    android:viewportHeight="1024"${extra}>`;

const stops = (list) =>
  list.map(([o, c]) => `        <item android:offset="${o}" android:color="${c}"/>`).join('\n');

const linearFill = (x1, y1, x2, y2, list) => `
    <aapt:attr name="android:fillColor">
      <gradient android:type="linear"
          android:startX="${x1}" android:startY="${y1}"
          android:endX="${x2}" android:endY="${y2}">
${stops(list)}
      </gradient>
    </aapt:attr>`;

const radialFill = (cx, cy, r, list) => `
    <aapt:attr name="android:fillColor">
      <gradient android:type="radial"
          android:centerX="${cx}" android:centerY="${cy}" android:gradientRadius="${r}">
${stops(list)}
      </gradient>
    </aapt:attr>`;

// VectorDrawable has no filters, so the vector mark is body gradient + gloss
// clipped to the silhouette. Same read, no blur.
function vectorMark(dp, h, tone = 'deep') {
  const d = markPath(512, 512, h);
  const w = markW(h);
  const t = 512 - h / 2;
  const off = ['0', '0.22', '0.55', '0.82', '1'];
  const fill = linearFill(
    (512 - w * 0.42).toFixed(1), t.toFixed(1),
    (512 + w * 0.46).toFixed(1), (512 + h / 2).toFixed(1),
    TONE[tone].map((c, i) => [off[i], '#FF' + c.slice(1)]));
  const gloss = linearFill(512, t.toFixed(1), 512, (t + h * 0.66).toFixed(1),
    [['0', '#42FFFFFF'], ['0.45', '#12FFFFFF'], ['1', '#00FFFFFF']]);
  return `${vecHead(dp)}
  <path android:pathData="${d}">${fill}
  </path>
  <path android:pathData="${d}">${gloss}
  </path>
</vector>
`;
}

function vectorLauncherForeground() {
  return vectorMark(108, ADAPTIVE_MARK);
}

function vectorLauncherBackground() {
  const full = 'M0,0 L1024,0 L1024,1024 L0,1024 Z';
  return `${vecHead(108)}
  <path android:pathData="${full}">${linearFill(120, 0, 900, 1024,
    [['0', '#FFFFFFFF'], ['0.34', '#FFEAEFF7'], ['0.70', '#FFC4D0E3'], ['1', '#FF9FB1CC']])}
  </path>
  <path android:pathData="${full}">${radialFill(300, 190, 820,
    [['0', '#D9FFFFFF'], ['0.45', '#33FFFFFF'], ['1', '#00FFFFFF']])}
  </path>
  <path android:pathData="${full}">${radialFill(920, 960, 740,
    [['0', '#472C63A8'], ['1', '#002C63A8']])}
  </path>
</vector>
`;
}

// The monochrome layer of an adaptive icon: solid white, no tint of its own —
// the launcher recolours it from the system theme.
function vectorMono() {
  return `${vecHead(108)}
  <path android:pathData="${markPath(512, 512, ADAPTIVE_MARK)}" android:fillColor="#FFFFFFFF"/>
</vector>
`;
}

const adaptiveXml = () => `<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_logo_mono" />
</adaptive-icon>
`;

const bannerXml = () => `<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_banner_background" />
    <foreground>
        <inset
            android:drawable="@mipmap/ic_banner_foreground"
            android:inset="10%" />
    </foreground>
</adaptive-icon>
`;

const colorsXml = () => `<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="logo_blue_lit">${C.blueLit}</color>
    <color name="logo_blue">${C.blue}</color>
    <color name="logo_blue_mid">${C.blueMid}</color>
    <color name="logo_blue_deep">${C.blueDeep}</color>
    <color name="logo_ink">${C.ink}</color>
    <color name="logo_silver">${C.silver}</color>
    <color name="logo_silver_mid">${C.silverMid}</color>
    <color name="logo_silver_low">${C.silverLow}</color>
    <color name="logo_steel">${C.steel}</color>
</resources>
`;

// ---------------------------------------------------------------- output
const mk = (p) => (fs.mkdirSync(path.join(OUT, p), { recursive: true }), path.join(OUT, p));
const write = (rel, text) => {
  const f = path.join(OUT, rel);
  fs.mkdirSync(path.dirname(f), { recursive: true });
  fs.writeFileSync(f, text);
};

// render at 4x then box down, so hairlines and sharp tips stay clean at 16px
async function png(make, size, rel) {
  const ss = Math.min(Math.max(size * 4, 1024), 4096);
  const buf = await sharp(Buffer.from(make(ss)))
    .resize(size, size, { kernel: sharp.kernel.lanczos3 })
    .png({ compressionLevel: 9 })
    .toBuffer();
  const f = path.join(OUT, rel);
  fs.mkdirSync(path.dirname(f), { recursive: true });
  fs.writeFileSync(f, buf);
}

async function pngBox(make, w, h, rel) {
  const buf = await sharp(Buffer.from(make(4)))
    .resize(w, h, { kernel: sharp.kernel.lanczos3 })
    .png({ compressionLevel: 9 })
    .toBuffer();
  const f = path.join(OUT, rel);
  fs.mkdirSync(path.dirname(f), { recursive: true });
  fs.writeFileSync(f, buf);
}

const ICON_SIZES = [16, 24, 32, 48, 64, 96, 128, 144, 192, 256, 384, 512, 1024];
const MONO_SIZES = [24, 48, 96, 192, 512];
const DENSITY = { mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 };

// A single sheet to eyeball the set: the icon on light and on dark, the bare
// mark, the round fallback, the TV banner, and the real launcher sizes.
async function preview() {
  const svgBuf = (make, px) =>
    sharp(Buffer.from(make(px * 4))).resize(px, px, { kernel: sharp.kernel.lanczos3 }).png().toBuffer();
  const tile = (w, h, color) =>
    sharp({ create: { width: w, height: h, channels: 4, background: color } }).png().toBuffer();

  const [glassBig, glassDark, markBig, roundBig] = await Promise.all(
    [glass, glass, (px) => markOnly(px, 980), glassRound].map((f) => svgBuf(f, 260)));
  const banner = await sharp(Buffer.from(bannerFlat(4)))
    .resize(320, 180, { kernel: sharp.kernel.lanczos3 }).png().toBuffer();
  const stripSizes = [48, 72, 96, 144, 192];
  const strip = await Promise.all(stripSizes.map((s) => svgBuf(glass, s)));

  const W = 1180, H = 700;
  const layers = [
    { input: await tile(300, 300, '#1B1D23'), left: 320, top: 40 },
    { input: glassBig, left: 340, top: 60 },
    { input: glassDark, left: 40, top: 60 },
    { input: markBig, left: 640, top: 60 },
    { input: roundBig, left: 910, top: 60 },
    { input: banner, left: 40, top: 400 },
    { input: await tile(1100, 2, '#D3D8E2'), left: 40, top: 360 },
  ];
  let x = 420;
  strip.forEach((buf, i) => {
    const s = stripSizes[i];
    layers.push({ input: buf, left: x, top: 400 + (192 - s) });
    x += s + 34;
  });

  const out = await sharp({ create: { width: W, height: H, channels: 4, background: '#F5F6FA' } })
    .composite(layers).png({ compressionLevel: 9 }).toBuffer();
  fs.writeFileSync(path.join(OUT, 'preview.png'), out);
}

async function main() {
  // --- svg sources
  write('svg/logo-glass.svg', glass());
  write('svg/logo-glass-round.svg', glassRound());
  write('svg/logo-mark.svg', markOnly());
  write('svg/logo-mono-white.svg', mono(CANVAS, '#FFFFFF'));
  write('svg/logo-mono-black.svg', mono(CANVAS, '#000000'));
  write('svg/logo-solid.svg', solid());
  write('svg/adaptive-foreground.svg', adaptiveForeground());
  write('svg/adaptive-background.svg', adaptiveBackground());
  write('svg/tv-banner.svg', bannerFlat());

  // --- android xml
  write('android/drawable/ic_launcher_foreground.xml', vectorLauncherForeground());
  write('android/drawable/ic_launcher_background.xml', vectorLauncherBackground());
  write('android/drawable/ic_logo.xml', vectorMark(24, 940, 'lit'));
  write('android/drawable/ic_logo_large.xml', vectorMark(96, 940, 'lit'));
  write('android/drawable/ic_logo_mono.xml', vectorMono());
  write('android/mipmap-anydpi-v26/ic_launcher.xml', adaptiveXml());
  write('android/mipmap-anydpi-v26/ic_launcher_round.xml', adaptiveXml());
  write('android/mipmap-anydpi-v26/ic_banner.xml', bannerXml());
  write('android/values/logo_colors.xml', colorsXml());
  write('android/values/ic_banner_background.xml', bannerColorXml());

  // --- android tv banner (xhdpi only, the density the manifest ships)
  await pngBox(bannerFlat, BANNER.w, BANNER.h, 'android/mipmap-xhdpi/ic_banner.png');
  await pngBox(bannerForeground, BANNER.w, BANNER.h, 'android/mipmap-xhdpi/ic_banner_foreground.png');

  // --- android raster
  for (const [d, k] of Object.entries(DENSITY)) {
    await png(glass, Math.round(48 * k), `android/mipmap-${d}/ic_launcher.png`);
    await png(glassRound, Math.round(48 * k), `android/mipmap-${d}/ic_launcher_round.png`);
    await png(adaptiveBackground, Math.round(108 * k), `android/mipmap-${d}/ic_launcher_background.png`);
    await png(adaptiveForeground, Math.round(108 * k), `android/mipmap-${d}/ic_launcher_foreground.png`);
    await png((px) => markOnly(px, 980), Math.round(24 * k), `android/drawable-${d}/ic_logo.png`);
  }

  // --- generic raster
  for (const s of ICON_SIZES) {
    await png(glass, s, `png/icon/logo-glass-${s}.png`);
    await png((px) => markOnly(px, 980), s, `png/mark/logo-mark-${s}.png`);
  }
  for (const s of MONO_SIZES) {
    await png((px) => mono(px, '#FFFFFF', 980), s, `png/mono/logo-mono-${s}.png`);
  }

  // --- store
  await png(solid, 512, 'store/play-store-512.png');
  await png(glass, 1024, 'store/logo-1024.png');

  await preview();

  console.log('logo assets rebuilt in', OUT);
}

main().catch((e) => { console.error(e); process.exit(1); });
