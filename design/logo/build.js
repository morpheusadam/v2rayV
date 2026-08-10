const fs = require('fs');
const path = require('path');
const sharp = require('sharp');

const OUT = 'C:/Users/morph/Projects/V2ray/v2rayNG/design/logo';

// ---------------------------------------------------------------- palette
const C = {
  violet: '#8B6BFF',
  indigo: '#6A4BF5',
  teal:   '#35D9A8',
  green:  '#2EE59D',
  ink:    '#221B47',
};

// ---------------------------------------------------------------- geometry
// Canonical hand is drawn in a 1024x1024 space; bbox below includes the
// outline pass (stroke-width 34 -> 17px bleed on every side).
const rr = (x, y, w, h, r) =>
  `M ${x + r},${y} H ${x + w - r} A ${r},${r} 0 0 1 ${x + w},${y + r} ` +
  `V ${y + h - r} A ${r},${r} 0 0 1 ${x + w - r},${y + h} ` +
  `H ${x + r} A ${r},${r} 0 0 1 ${x},${y + h - r} ` +
  `V ${y + r} A ${r},${r} 0 0 1 ${x + r},${y} Z`;

const P = {
  palm:   rr(305, 480, 340, 330, 165),
  ring:   rr(572, 452, 108, 200, 54),
  pinky:  rr(652, 496, 100, 172, 50),
  index:  'M 332,222 L 450,566',
  middle: 'M 612,196 L 522,566',
  thumb:  'M 356,676 L 520,706',
  crease: 'M 372,644 C 448,592 548,608 568,692',
};
const ROT = { ring: [8, 626, 552], pinky: [14, 702, 582] };

const FINGER_W = 118;   // index / middle
const THUMB_W  = 128;
const RIM      = 34;    // dark outline thickness (total)
const DETAIL   = 18;    // internal crease thickness

const BBOX = { x: 256, y: 120, w: 521, h: 707 };

// place the hand inside `size`, occupying `frac` of the height
const fit = (size, frac) => {
  const s = (size * frac) / BBOX.h;
  const tx = size / 2 - (BBOX.x + BBOX.w / 2) * s;
  const ty = size / 2 - (BBOX.y + BBOX.h / 2) * s;
  return `translate(${tx.toFixed(2)},${ty.toFixed(2)}) scale(${s.toFixed(5)})`;
};

// ---------------------------------------------------------------- hand
// Three passes: dark outline (union of solid shapes, no seams), gradient
// fill (userSpaceOnUse gradient so overlaps blend), then detail lines.
function hand({ grad = 'url(#handGrad)', mono = null } = {}) {
  const g = (a) => `transform="rotate(${ROT[a][0]} ${ROT[a][1]} ${ROT[a][2]})"`;
  const silhouette = (attrs, extra = 0) => `
    <path d="${P.palm}" ${attrs} stroke-width="${extra}"/>
    <path d="${P.thumb}" ${attrs} stroke-width="${THUMB_W + extra}"/>
    <path d="${P.ring}" ${g('ring')} ${attrs} stroke-width="${extra}"/>
    <path d="${P.pinky}" ${g('pinky')} ${attrs} stroke-width="${extra}"/>
    <path d="${P.index}" ${attrs} stroke-width="${FINGER_W + extra}"/>
    <path d="${P.middle}" ${attrs} stroke-width="${FINGER_W + extra}"/>`;

  if (mono) {
    return `<g fill="${mono}" stroke="${mono}" stroke-linecap="round" stroke-linejoin="round">
      ${silhouette(`fill="${mono}" stroke="${mono}"`, 0)}
    </g>`;
  }

  return `<g stroke-linecap="round" stroke-linejoin="round">
    <g fill="${C.ink}" stroke="${C.ink}">
      ${silhouette(`fill="${C.ink}" stroke="${C.ink}"`, RIM)}
    </g>
    <g fill="${grad}" stroke="${grad}">
      ${silhouette(`fill="${grad}" stroke="${grad}"`, 0)}
    </g>
    <g fill="none" stroke="${C.ink}" stroke-width="${DETAIL}">
      <path d="${P.ring}" ${g('ring')}/>
      <path d="${P.pinky}" ${g('pinky')}/>
      <path d="${P.crease}"/>
    </g>
  </g>`;
}

const handGrad = (y0, y1) => `
  <linearGradient id="handGrad" gradientUnits="userSpaceOnUse"
                  x1="380" y1="${y0}" x2="620" y2="${y1}">
    <stop offset="0"    stop-color="${C.violet}"/>
    <stop offset="0.30" stop-color="${C.indigo}"/>
    <stop offset="0.72" stop-color="${C.teal}"/>
    <stop offset="1"    stop-color="${C.green}"/>
  </linearGradient>`;

// ---------------------------------------------------------------- documents
const svg = (size, body, defs = '') =>
  `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" ` +
  `viewBox="0 0 ${size} ${size}">\n<defs>${defs}</defs>\n${body}\n</svg>\n`;

// 1. glass card + hand, fully transparent background
function glass(size = 1024) {
  const k = size / 1024;
  const card = rr(88 * k, 88 * k, 848 * k, 848 * k, 208 * k);
  const inner = rr(98 * k, 98 * k, 828 * k, 828 * k, 198 * k);
  const defs = `
    ${handGrad(170, 600)}
    <linearGradient id="glassFill" x1="0.05" y1="0" x2="0.85" y2="1">
      <stop offset="0"    stop-color="#FFFFFF" stop-opacity="0.26"/>
      <stop offset="0.40" stop-color="#FFFFFF" stop-opacity="0.10"/>
      <stop offset="0.75" stop-color="${C.teal}" stop-opacity="0.10"/>
      <stop offset="1"    stop-color="${C.indigo}" stop-opacity="0.16"/>
    </linearGradient>
    <linearGradient id="sweep" x1="0" y1="0" x2="0.72" y2="1">
      <stop offset="0"    stop-color="#FFFFFF" stop-opacity="0.30"/>
      <stop offset="0.22" stop-color="#FFFFFF" stop-opacity="0.12"/>
      <stop offset="0.52" stop-color="#FFFFFF" stop-opacity="0"/>
      <stop offset="1"    stop-color="#FFFFFF" stop-opacity="0"/>
    </linearGradient>
    <linearGradient id="rimGrad" x1="0.1" y1="0" x2="0.9" y2="1">
      <stop offset="0"    stop-color="#FFFFFF" stop-opacity="0.92"/>
      <stop offset="0.30" stop-color="#FFFFFF" stop-opacity="0.28"/>
      <stop offset="0.62" stop-color="${C.green}" stop-opacity="0.45"/>
      <stop offset="1"    stop-color="${C.violet}" stop-opacity="0.70"/>
    </linearGradient>
    <linearGradient id="innerRim" x1="0.15" y1="0" x2="0.85" y2="1">
      <stop offset="0"    stop-color="#FFFFFF" stop-opacity="0.55"/>
      <stop offset="0.35" stop-color="#FFFFFF" stop-opacity="0.05"/>
      <stop offset="1"    stop-color="#FFFFFF" stop-opacity="0"/>
    </linearGradient>
    <clipPath id="cardClip"><path d="${card}"/></clipPath>
    <filter id="glow" x="-50%" y="-50%" width="200%" height="200%">
      <feGaussianBlur stdDeviation="${40 * k}"/>
    </filter>
    <filter id="drop" x="-30%" y="-30%" width="160%" height="160%">
      <feDropShadow dx="0" dy="${10 * k}" stdDeviation="${16 * k}"
                    flood-color="#050B08" flood-opacity="0.45"/>
    </filter>`;

  const body = `
  <g>
    <path d="${card}" fill="url(#glassFill)"/>
    <g clip-path="url(#cardClip)">
      <ellipse cx="${512 * k}" cy="${620 * k}" rx="${270 * k}" ry="${230 * k}"
               fill="${C.green}" opacity="0.28" filter="url(#glow)"/>
      <ellipse cx="${452 * k}" cy="${300 * k}" rx="${210 * k}" ry="${165 * k}"
               fill="${C.violet}" opacity="0.24" filter="url(#glow)"/>
      <rect width="${size}" height="${size}" fill="url(#sweep)"/>
      <path d="${inner}" fill="none" stroke="url(#innerRim)" stroke-width="${3 * k}"/>
    </g>
    <path d="${card}" fill="none" stroke="url(#rimGrad)" stroke-width="${5 * k}"/>
  </g>
  <g filter="url(#drop)"><g transform="scale(${k})">
    <g transform="${fit(1024, 0.46)}">${hand()}</g>
  </g></g>`;

  return svg(size, body, defs);
}

// 2. bare mark, transparent
const mark = (size = 1024, frac = 0.92) =>
  svg(size, `<g transform="${fit(size, frac)}">${hand()}</g>`, handGrad(170, 600));

// 3. monochrome (themed icons / notification / tinting)
const monoMark = (size = 1024, color = '#FFFFFF', frac = 0.92) =>
  svg(size, `<g transform="${fit(size, frac)}">${hand({ mono: color })}</g>`);

// 4. adaptive-icon layers (108dp grid, art inside the 66dp safe zone)
const adaptiveFg = (size = 432) =>
  svg(size, `<g transform="${fit(size, 0.52)}">${hand()}</g>`, handGrad(170, 600));

const adaptiveBg = (size = 432) => svg(size, `
  <rect width="${size}" height="${size}" fill="url(#bg)"/>
  <ellipse cx="${size * 0.5}" cy="${size * 0.62}" rx="${size * 0.46}" ry="${size * 0.4}"
           fill="${C.green}" opacity="0.16"/>
  <ellipse cx="${size * 0.36}" cy="${size * 0.3}" rx="${size * 0.4}" ry="${size * 0.33}"
           fill="${C.indigo}" opacity="0.18"/>`, `
  <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
    <stop offset="0" stop-color="#1C1547"/>
    <stop offset="1" stop-color="#07241D"/>
  </linearGradient>`);

// 5. opaque square icon (stores, docs, anywhere a flat square is needed)
const solid = (size = 1024) => {
  const inner = glass(size);
  return inner.replace('<defs>', `<defs><linearGradient id="solidBg" x1="0" y1="0" x2="1" y2="1">
    <stop offset="0" stop-color="#1C1547"/><stop offset="1" stop-color="#07241D"/></linearGradient>`)
    .replace(/(<\/defs>\n)/, `$1<rect width="${size}" height="${size}" fill="url(#solidBg)"/>\n`);
};

// ---------------------------------------------------------------- android vector
function vectorDrawable({ dp = 24, mono = false, frac = 0.92 } = {}) {
  const grad = `<aapt:attr name="android:fillColor">
        <gradient android:type="linear"
            android:startX="380" android:startY="170"
            android:endX="620" android:endY="600">
          <item android:offset="0"    android:color="${C.violet}"/>
          <item android:offset="0.30" android:color="${C.indigo}"/>
          <item android:offset="0.72" android:color="${C.teal}"/>
          <item android:offset="1"    android:color="${C.green}"/>
        </gradient>
      </aapt:attr>`;
  const gradStroke = grad.replace('android:fillColor', 'android:strokeColor');

  // in VectorDrawable a path carries at most one fill and one stroke, so the
  // capsules (stroked lines) and the plates (filled outlines) are separate.
  const pass = (fillXml, strokeXml, fillAttr, strokeAttr, extra) => `
    <path android:pathData="${P.palm}" ${fillAttr}>${fillXml}</path>
    <path android:pathData="${P.thumb}" ${strokeAttr}
        android:strokeWidth="${THUMB_W + extra}"
        android:strokeLineCap="round" android:strokeLineJoin="round">${strokeXml}</path>
    <group android:rotation="${ROT.ring[0]}" android:pivotX="${ROT.ring[1]}" android:pivotY="${ROT.ring[2]}">
      <path android:pathData="${P.ring}" ${fillAttr}>${fillXml}</path>
    </group>
    <group android:rotation="${ROT.pinky[0]}" android:pivotX="${ROT.pinky[1]}" android:pivotY="${ROT.pinky[2]}">
      <path android:pathData="${P.pinky}" ${fillAttr}>${fillXml}</path>
    </group>
    <path android:pathData="${P.index}" ${strokeAttr}
        android:strokeWidth="${FINGER_W + extra}"
        android:strokeLineCap="round" android:strokeLineJoin="round">${strokeXml}</path>
    <path android:pathData="${P.middle}" ${strokeAttr}
        android:strokeWidth="${FINGER_W + extra}"
        android:strokeLineCap="round" android:strokeLineJoin="round">${strokeXml}</path>`;

  // the outline pass grows the plates by stroking them in the same ink colour
  const outlinePlate = (d, rot) => {
    const body = `<path android:pathData="${d}" android:fillColor="${C.ink}"
        android:strokeColor="${C.ink}" android:strokeWidth="${RIM}"
        android:strokeLineJoin="round"/>`;
    return rot
      ? `<group android:rotation="${rot[0]}" android:pivotX="${rot[1]}" android:pivotY="${rot[2]}">${body}</group>`
      : body;
  };

  const body = mono ? `
    <path android:pathData="${P.palm}" android:fillColor="#FFFFFF"/>
    <path android:pathData="${P.thumb}" android:strokeColor="#FFFFFF"
        android:strokeWidth="${THUMB_W}" android:strokeLineCap="round"/>
    <group android:rotation="${ROT.ring[0]}" android:pivotX="${ROT.ring[1]}" android:pivotY="${ROT.ring[2]}">
      <path android:pathData="${P.ring}" android:fillColor="#FFFFFF"/>
    </group>
    <group android:rotation="${ROT.pinky[0]}" android:pivotX="${ROT.pinky[1]}" android:pivotY="${ROT.pinky[2]}">
      <path android:pathData="${P.pinky}" android:fillColor="#FFFFFF"/>
    </group>
    <path android:pathData="${P.index}" android:strokeColor="#FFFFFF"
        android:strokeWidth="${FINGER_W}" android:strokeLineCap="round"/>
    <path android:pathData="${P.middle}" android:strokeColor="#FFFFFF"
        android:strokeWidth="${FINGER_W}" android:strokeLineCap="round"/>` : `
    <!-- pass 1: dark outline -->
    ${outlinePlate(P.palm)}
    <path android:pathData="${P.thumb}" android:strokeColor="${C.ink}"
        android:strokeWidth="${THUMB_W + RIM}" android:strokeLineCap="round"/>
    ${outlinePlate(P.ring, ROT.ring)}
    ${outlinePlate(P.pinky, ROT.pinky)}
    <path android:pathData="${P.index}" android:strokeColor="${C.ink}"
        android:strokeWidth="${FINGER_W + RIM}" android:strokeLineCap="round"/>
    <path android:pathData="${P.middle}" android:strokeColor="${C.ink}"
        android:strokeWidth="${FINGER_W + RIM}" android:strokeLineCap="round"/>
    <!-- pass 2: gradient fill -->
    ${pass(grad, gradStroke, '', '', 0)}
    <!-- pass 3: detail lines -->
    <group android:rotation="${ROT.ring[0]}" android:pivotX="${ROT.ring[1]}" android:pivotY="${ROT.ring[2]}">
      <path android:pathData="${P.ring}" android:strokeColor="${C.ink}" android:strokeWidth="${DETAIL}"/>
    </group>
    <group android:rotation="${ROT.pinky[0]}" android:pivotX="${ROT.pinky[1]}" android:pivotY="${ROT.pinky[2]}">
      <path android:pathData="${P.pinky}" android:strokeColor="${C.ink}" android:strokeWidth="${DETAIL}"/>
    </group>
    <path android:pathData="${P.crease}" android:strokeColor="${C.ink}"
        android:strokeWidth="${DETAIL}" android:strokeLineCap="round"/>`;

  return `<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="${dp}dp"
    android:height="${dp}dp"
    android:viewportWidth="1024"
    android:viewportHeight="1024">
  <group android:translateX="${(1024 / 2 - (BBOX.x + BBOX.w / 2) * (1024 * frac / BBOX.h)).toFixed(2)}"
         android:translateY="${(1024 / 2 - (BBOX.y + BBOX.h / 2) * (1024 * frac / BBOX.h)).toFixed(2)}"
         android:scaleX="${(1024 * frac / BBOX.h).toFixed(5)}"
         android:scaleY="${(1024 * frac / BBOX.h).toFixed(5)}">
${body}
  </group>
</vector>
`;
}

// ---------------------------------------------------------------- android xml
const ADAPTIVE_ICON = (round) => `<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_logo_mono" />
</adaptive-icon>
`;

const BACKGROUND_VECTOR = `<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
  <path android:pathData="M0,0h108v108h-108z">
    <aapt:attr name="android:fillColor">
      <gradient android:type="linear"
          android:startX="0" android:startY="0" android:endX="108" android:endY="108">
        <item android:offset="0" android:color="#1C1547"/>
        <item android:offset="1" android:color="#07241D"/>
      </gradient>
    </aapt:attr>
  </path>
  <path android:pathData="M0,0h108v108h-108z">
    <aapt:attr name="android:fillColor">
      <gradient android:type="radial"
          android:centerX="54" android:centerY="66" android:gradientRadius="54">
        <item android:offset="0" android:color="#4D2EE59D"/>
        <item android:offset="1" android:color="#002EE59D"/>
      </gradient>
    </aapt:attr>
  </path>
  <path android:pathData="M0,0h108v108h-108z">
    <aapt:attr name="android:fillColor">
      <gradient android:type="radial"
          android:centerX="40" android:centerY="34" android:gradientRadius="46">
        <item android:offset="0" android:color="#4D8B6BFF"/>
        <item android:offset="1" android:color="#008B6BFF"/>
      </gradient>
    </aapt:attr>
  </path>
</vector>
`;

const COLORS_XML = `<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="logo_violet">${C.violet}</color>
    <color name="logo_indigo">${C.indigo}</color>
    <color name="logo_teal">${C.teal}</color>
    <color name="logo_green">${C.green}</color>
    <color name="logo_ink">${C.ink}</color>
</resources>
`;

const README = `# v2rayNG logo

همه چیز از \`build.js\` ساخته می‌شود؛ فایل‌ها را دستی ویرایش نکنید، اسکریپت را دوباره اجرا کنید.

## svg/  — منبع وکتور
| فایل | کاربرد |
|---|---|
| \`logo-glass.svg\` | لوگوی اصلی: کارت شیشه‌ای + دست، پس‌زمینه کاملاً شفاف |
| \`logo-mark.svg\` | فقط علامت دست، بدون کارت، شفاف — برای داخل برنامه |
| \`logo-mono-white.svg\` | تک‌رنگ سفید — نوار وضعیت، نوتیفیکیشن، themed icon |
| \`logo-mono-black.svg\` | تک‌رنگ مشکی — چاپ و پس‌زمینهٔ روشن |
| \`logo-solid.svg\` | همان لوگوی شیشه‌ای روی پس‌زمینهٔ مات — استور و مستندات |
| \`adaptive-foreground.svg\` | لایهٔ جلوی adaptive icon (محتوا داخل safe zone) |
| \`adaptive-background.svg\` | لایهٔ پشت adaptive icon |

## android/  — آمادهٔ کپی در \`app/src/main/res\`
- \`drawable/ic_logo.xml\` — vector drawable با گرادیان، ۲۴dp، برای استفاده در layout و منو
- \`drawable/ic_logo_large.xml\` — همان، ۹۶dp، برای splash و صفحهٔ About
- \`drawable/ic_logo_mono.xml\` — تک‌رنگ و tint‌پذیر (\`android:tint\`)
- \`drawable/ic_launcher_foreground.xml\` + \`ic_launcher_background.xml\` — لایه‌های وکتور adaptive icon
- \`mipmap-anydpi-v26/ic_launcher.xml\`, \`ic_launcher_round.xml\` — تعریف adaptive icon
- \`mipmap-*/ic_launcher*.png\` — fallback رستری برای اندروید < ۸
- \`drawable-*/ic_logo.png\` — fallback رستری علامت، ۲۴dp در هر تراکم
- \`values/logo_colors.xml\` — پالت رنگ

نصب:
\`\`\`powershell
$src = "design/logo/android"
$dst = "V2rayNG/app/src/main/res"
Copy-Item "$src/*" $dst -Recurse -Force
\`\`\`
\`logo_colors.xml\` عمداً فقط رنگ‌های \`logo_*\` را دارد و \`ic_launcher_background\` را تعریف نمی‌کند،
چون آن نام در \`values/colors.xml\` پروژه از قبل هست و تعریف دوباره‌اش build را می‌شکند.

نکته: \`AndroidManifest.xml\` فعلاً به \`@mipmap/ic_launcher\` اشاره می‌کند و همان درست است — فایل‌های بالا جای قبلی‌ها را می‌گیرند.

## png/  — رستر شفاف
- \`png/icon/logo-glass-{16..1024}.png\`
- \`png/mark/logo-mark-{16..1024}.png\`
- \`png/mono/logo-mono-{24,48,96,192,512}.png\`

## store/
- \`play-store-512.png\` — آیکون Google Play (۵۱۲×۵۱۲ مات)
- \`logo-1024.png\` — نسخهٔ شفاف بزرگ

## پالت
| نقش | کد |
|---|---|
| بنفش (نوک انگشت‌ها) | \`${C.violet}\` |
| نیلی | \`${C.indigo}\` |
| فیروزه‌ای | \`${C.teal}\` |
| سبز (کف دست) | \`${C.green}\` |
| خط دور | \`${C.ink}\` |
`;

// ---------------------------------------------------------------- emit
const write = (rel, data) => {
  const p = path.join(OUT, rel);
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, data);
};
const png = (rel, source, size) => {
  const p = path.join(OUT, rel);
  fs.mkdirSync(path.dirname(p), { recursive: true });
  return sharp(Buffer.from(source), { density: 288 })
    .resize(size, size, { fit: 'contain', background: { r: 0, g: 0, b: 0, alpha: 0 } })
    .png({ compressionLevel: 9 })
    .toFile(p);
};

(async () => {
  // --- vectors
  write('svg/logo-glass.svg', glass(1024));
  write('svg/logo-mark.svg', mark(1024));
  write('svg/logo-mono-white.svg', monoMark(1024, '#FFFFFF'));
  write('svg/logo-mono-black.svg', monoMark(1024, '#000000'));
  write('svg/logo-solid.svg', solid(1024));
  write('svg/adaptive-foreground.svg', adaptiveFg(1024));
  write('svg/adaptive-background.svg', adaptiveBg(1024));

  write('android/drawable/ic_logo.xml', vectorDrawable({ dp: 24 }));
  write('android/drawable/ic_logo_large.xml', vectorDrawable({ dp: 96 }));
  write('android/drawable/ic_logo_mono.xml', vectorDrawable({ dp: 108, mono: true, frac: 0.52 }));
  write('android/drawable/ic_launcher_foreground.xml', vectorDrawable({ dp: 108, frac: 0.52 }));
  write('android/drawable/ic_launcher_background.xml', BACKGROUND_VECTOR);
  write('android/mipmap-anydpi-v26/ic_launcher.xml', ADAPTIVE_ICON(false));
  write('android/mipmap-anydpi-v26/ic_launcher_round.xml', ADAPTIVE_ICON(true));
  write('android/values/logo_colors.xml', COLORS_XML);
  write('README.md', README);

  // --- rasters
  const GLASS = glass(1024), MARK = mark(1024), MONO = monoMark(1024, '#FFFFFF');
  const SOLID = solid(1024), FG = adaptiveFg(1024), BG = adaptiveBg(1024);

  const jobs = [];
  for (const s of [16, 24, 32, 48, 64, 96, 128, 144, 192, 256, 384, 512, 1024]) {
    jobs.push(png(`png/icon/logo-glass-${s}.png`, GLASS, s));
    jobs.push(png(`png/mark/logo-mark-${s}.png`, MARK, s));
  }
  for (const s of [24, 48, 96, 192, 512]) {
    jobs.push(png(`png/mono/logo-mono-${s}.png`, MONO, s));
  }

  // launcher icons, per density (48dp)
  const LAUNCHER = { mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 };
  for (const [d, s] of Object.entries(LAUNCHER)) {
    jobs.push(png(`android/mipmap-${d}/ic_launcher.png`, SOLID, s));
    jobs.push(png(`android/mipmap-${d}/ic_launcher_round.png`, SOLID, s));
  }
  // adaptive layers, per density (108dp)
  const ADAPTIVE = { mdpi: 108, hdpi: 162, xhdpi: 216, xxhdpi: 324, xxxhdpi: 432 };
  for (const [d, s] of Object.entries(ADAPTIVE)) {
    jobs.push(png(`android/mipmap-${d}/ic_launcher_foreground.png`, FG, s));
    jobs.push(png(`android/mipmap-${d}/ic_launcher_background.png`, BG, s));
  }
  // in-app drawable, per density (24dp base)
  const DRAWABLE = { mdpi: 24, hdpi: 36, xhdpi: 48, xxhdpi: 72, xxxhdpi: 96 };
  for (const [d, s] of Object.entries(DRAWABLE)) {
    jobs.push(png(`android/drawable-${d}/ic_logo.png`, MARK, s));
  }

  jobs.push(png('store/play-store-512.png', SOLID, 512));
  jobs.push(png('store/logo-1024.png', GLASS, 1024));

  await Promise.all(jobs);
  write('build.js', fs.readFileSync(__filename));  // keep the source next to the output
  console.log('done ->', OUT);
})();
