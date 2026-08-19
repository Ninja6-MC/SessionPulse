#!/usr/bin/env node
// Regenerates every SessionPulse icon variant from docs/assets/icon-master.svg.
//
//   node scripts/export-icons.mjs
//
// The master holds geometry only. This script is where colour and plates live, so
// the two never duplicate: one geometry, one variant table, N outputs. See
// ICON_PLAN.md sections 3, 5, 7 and 8 in the private `brand` repository.
//
// Rasterizer is @resvg/resvg-js, pinned below. It is fetched into build/ on first
// run (gitignored) rather than committed as a package.json dependency - this is a
// Gradle repo and the icon export is the only thing here that wants Node.
//
// resvg has no equivalent of "rsvg-convert --id", so variants cannot be selected
// out of the master at render time. Instead each variant is composed into a
// standalone SVG here and rasterized from that. Those composed SVGs are committed
// (platforms and the README reference them directly) but are generated output -
// edit the master, never them.
//
// TWO INVARIANTS ARE ENFORCED, and both must fail the run rather than warn:
//
//   1. Safe zone. Artwork must fit inside radius 460 of centre on the 1024 canvas,
//      or circular avatar crops eat its corners.
//   2. Palette. A themed variant must contain its own colours and none of the other
//      theme's.
//
// A gate that silently passes is worse than no gate, because it still prints "ok".
// This is a port of the hardened script in SpiralGenesis, which replaced an earlier
// regex-based one - the same earlier one this repository was carrying. That version
// had bypasses that reported "ok" while measuring nothing: an added attribute on the
// geo group made its match fail and the whole gate vanish, and a second polyline
// inside the group was never looked at at all. The parsing below refuses anything it
// does not fully understand instead of skipping it.

import { execSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const RESVG = '@resvg/resvg-js@2.6.2';
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const ASSETS = join(ROOT, 'docs', 'assets');
const TOOLS = join(ROOT, 'build', 'icon-tools');

const CANVAS = 1024;
const CENTRE = CANVAS / 2;
const SAFE_RADIUS = 460; // ICON_PLAN section 2

// ---------------------------------------------------------------- palette

const PLATE = '#12161A';
const LAVENDER = '#B794F4';      // structural stroke, dark contexts (7.44:1 on #12161A)
const VIOLET_DARK = '#5B21B6';   // structural stroke, light contexts (8.15:1 on #F2F4F7)
const AMBER = '#FFAC1C';         // family accent, dark contexts (9.25:1 on #12161A)
const AMBER_DARK = '#B8730A';    // family accent, light contexts (3.47:1 on #F2F4F7)
const SILVER = '#E8EDF2';

// Colours that must never appear in a variant of the opposite theme. Every variant
// carries a theme, including the opaque one - it is the avatar most people see, so
// leaving it unchecked would be the least defensible gap of all.
const THEME_INK = { dark: [LAVENDER, AMBER], light: [VIOLET_DARK, AMBER_DARK] };

const VARIANTS = [
  // v1 - primary, opaque. Platform avatars: Modrinth, Hangar, GitHub, CurseForge.
  { file: 'icon', geo: 'geo', plate: PLATE, stroke: LAVENDER, dot: AMBER, theme: 'dark',
    sizes: [64, 128, 180, 256, 400, 512, 1024] },

  // v1r - rounded plate. Apple touch icon, docs headers.
  { file: 'icon-rounded', geo: 'geo', plate: PLATE, rx: 225, stroke: LAVENDER, dot: AMBER,
    theme: 'dark', sizes: [180, 256, 512, 1024] },

  // v2/v3 - transparent, one per theme. A single transparent cut cannot serve both:
  // lavender is far too low-contrast on a light ground, which is why the light cut
  // swaps to violet and the amber darkens with it.
  { file: 'icon-transparent-dark', geo: 'geo', stroke: LAVENDER, dot: AMBER,
    theme: 'dark', sizes: [64, 128, 256, 512] },
  { file: 'icon-transparent-light', geo: 'geo', stroke: VIOLET_DARK, dot: AMBER_DARK,
    theme: 'light', sizes: [64, 128, 256, 512] },

  // v4 - simplified small cut, <=24px. Uses #geo-small, which carries the heavier
  // stroke and drops the dot for clear aperture, so no dot is supplied here.
  { file: 'icon-small-dark', geo: 'geo-small', stroke: LAVENDER, theme: 'dark',
    sizes: [16, 24, 32, 48, 64] },
  { file: 'icon-small-light', geo: 'geo-small', stroke: VIOLET_DARK, theme: 'light',
    sizes: [16, 24, 32, 48, 64] },
  { file: 'icon-small-plate', geo: 'geo-small', plate: PLATE, stroke: LAVENDER,
    theme: 'dark', sizes: [64, 128] },

  // v5 - monochrome. Single colour, no raster ladder.
  { file: 'icon-mono-dark', geo: 'geo-small', stroke: SILVER, theme: 'dark', sizes: [] },
  { file: 'icon-mono-light', geo: 'geo-small', stroke: PLATE, theme: 'light', sizes: [] },
];

// Outputs this script used to produce and no longer does. Deleted after a successful
// run, because a renamed variant otherwise leaves the old file behind committed and
// nothing ever notices. Empty today; add to it when a variant is renamed or dropped.
const STALE = [];

// ------------------------------------------------------------- svg parsing

// A small, strict reader for the subset the master is allowed to use. Quote-aware,
// so an attribute value containing ">" cannot end an element early; attribute order
// and whitespace are irrelevant because attributes land in a map.
function parseElements(xml) {
  const els = [];
  let i = 0;
  while (i < xml.length) {
    const lt = xml.indexOf('<', i);
    if (lt < 0) break;
    if (xml.startsWith('<!--', lt)) {
      const end = xml.indexOf('-->', lt);
      i = end < 0 ? xml.length : end + 3;
      continue;
    }
    if (xml.startsWith('<?', lt) || xml.startsWith('<!', lt)) {
      const end = xml.indexOf('>', lt);
      i = end < 0 ? xml.length : end + 1;
      continue;
    }
    let j = lt + 1;
    let quote = null;
    while (j < xml.length) {
      const c = xml[j];
      if (quote) { if (c === quote) quote = null; }
      else if (c === '"' || c === "'") quote = c;
      else if (c === '>') break;
      j++;
    }
    const raw = xml.slice(lt + 1, j);
    i = j + 1;
    if (raw.startsWith('/')) { els.push({ tag: raw.slice(1).trim(), close: true, attrs: {} }); continue; }
    const name = raw.match(/^([a-zA-Z][\w:-]*)/);
    if (!name) continue;
    const attrs = {};
    const attrRe = /([a-zA-Z_:][\w:.-]*)\s*=\s*("([^"]*)"|'([^']*)')/g;
    let a;
    while ((a = attrRe.exec(raw))) attrs[a[1]] = a[3] !== undefined ? a[3] : a[4];
    els.push({ tag: name[1], attrs, selfClose: /\/\s*$/.test(raw) });
  }
  return els;
}

const master = readFileSync(join(ASSETS, 'icon-master.svg'), 'utf8');
const masterEls = parseElements(master);

{
  const svg = masterEls.find((e) => e.tag === 'svg');
  const want = `0 0 ${CANVAS} ${CANVAS}`;
  if (!svg || svg.attrs.viewBox !== want) {
    throw new Error(`icon-master.svg viewBox must be "${want}"; every measurement `
      + 'and the composed output below assume it');
  }
}

// Children of <g id="...">, with real nesting tracking. Comments are skipped by the
// parser, so a comment containing "</g>" cannot truncate the block. Looking the group
// up by its parsed id attribute rather than by matching `<g id="geo">` as text is what
// stops an unrelated added attribute from making the whole gate disappear.
function geometryOf(id) {
  const open = masterEls.findIndex((e) => e.tag === 'g' && !e.close && e.attrs.id === id);
  if (open < 0) throw new Error(`icon-master.svg has no <g id="${id}">`);
  const out = [];
  for (let k = open + 1; k < masterEls.length; k++) {
    const e = masterEls[k];
    if (e.tag === 'g' && !e.close && !e.selfClose) {
      throw new Error(`<g id="${id}"> contains a nested <g>; flatten it, or teach `
        + 'this script to compose transforms');
    }
    if (e.tag === 'g' && e.close) return out;
    out.push(e);
  }
  throw new Error(`<g id="${id}"> is never closed`);
}

// ---------------------------------------------------- safe-zone assertion

const num = (v, what) => {
  const n = Number(v);
  if (v === undefined || v === '' || !Number.isFinite(n)) {
    throw new Error(`${what}: expected a number, got ${JSON.stringify(v)}`);
  }
  return n;
};

// Worst-case distance from centre to any inked point. Only axis-aligned polylines
// with square caps and miter joins, plus circles, are understood - and anything else
// is an error, never a skip. Every element in the group is measured, so a second
// polyline can no longer ride along unlooked-at.
function worstRadius(els) {
  const cand = [];

  for (const el of els) {
    if (el.attrs.transform) {
      throw new Error(`transform= on <${el.tag}>: coordinates must be baked in. A `
        + 'scale() would also silently rescale stroke-width.');
    }

    if (el.tag === 'polyline') {
      const hw = num(el.attrs['stroke-width'], 'polyline stroke-width') / 2;
      const pts = String(el.attrs.points ?? '').trim().split(/\s+/).filter(Boolean)
        .map((p) => {
          if (!/^-?[\d.]+,-?[\d.]+$/.test(p)) {
            throw new Error(`polyline point ${JSON.stringify(p)} is not "x,y". SVG `
              + 'allows space-separated pairs; this script does not, because a '
              + 'half-parsed pair measures as NaN and vanishes from the gate.');
          }
          return p.split(',').map(Number);
        });
      if (pts.length < 2) throw new Error('polyline needs at least two points');

      for (let k = 1; k < pts.length; k++) {
        if (pts[k][0] !== pts[k - 1][0] && pts[k][1] !== pts[k - 1][1]) {
          throw new Error(`polyline segment ${pts[k - 1]} -> ${pts[k]} is diagonal. `
            + 'The miter maths here assumes right angles (hw*sqrt2); a sharper '
            + 'vertex projects hw/sin(theta/2) and would be under-measured.');
        }
      }

      // square caps at both ends
      for (const [i, j] of [[0, 1], [pts.length - 1, pts.length - 2]]) {
        const [x, y] = pts[i];
        const [xn, yn] = pts[j];
        const len = Math.hypot(x - xn, y - yn);
        if (len === 0) throw new Error('polyline has a zero-length end segment');
        const ux = (x - xn) / len;
        const uy = (y - yn) / len;
        const ex = x + ux * hw;
        const ey = y + uy * hw;
        cand.push([ex - uy * hw, ey + ux * hw], [ex + uy * hw, ey - ux * hw]);
      }
      // miter joins: the outer corner is one vertex of the half-width square around
      // the vertex, and taking all four is conservative in the right direction
      for (let k = 1; k < pts.length - 1; k++) {
        const [x, y] = pts[k];
        for (const a of [-1, 1]) for (const b of [-1, 1]) cand.push([x + a * hw, y + b * hw]);
      }
    } else if (el.tag === 'circle') {
      const cx = num(el.attrs.cx, 'circle cx');
      const cy = num(el.attrs.cy, 'circle cy');
      const r = num(el.attrs.r, 'circle r');
      cand.push([cx + r, cy], [cx - r, cy], [cx, cy + r], [cx, cy - r],
        [cx + r * 0.7071, cy + r * 0.7071], [cx - r * 0.7071, cy - r * 0.7071],
        [cx + r * 0.7071, cy - r * 0.7071], [cx - r * 0.7071, cy + r * 0.7071]);
    } else {
      throw new Error(`worstRadius cannot measure <${el.tag}>; teach it, or the `
        + 'safe-zone gate will pass artwork it never looked at');
    }
  }

  let worst = 0;
  let at = null;
  for (const [x, y] of cand) {
    const r = Math.hypot(x - CENTRE, y - CENTRE);
    if (!Number.isFinite(r)) throw new Error('non-finite candidate point');
    if (r > worst) { worst = r; at = [x, y]; }
  }
  if (!at) throw new Error('no geometry to measure');
  return { worst, at };
}

// ---------------------------------------------------------------- compose

// Serialised from the parsed elements rather than copied as text, so the output can
// never contain something the measurer did not look at. This also keeps <defs> and
// <use> out of the shipped SVGs, which some platform sanitizers mangle.
function compose(v) {
  const els = geometryOf(v.geo);

  const body = els.map((e) => {
    const attrs = Object.entries(e.attrs).map(([k, val]) => `${k}="${val}"`).join(' ');
    return `    <${e.tag} ${attrs} />`;
  }).join('\n');

  const paint = [`stroke="${v.stroke}"`, v.dot ? `fill="${v.dot}"` : null]
    .filter(Boolean).join(' ');
  const rx = v.rx ? ` rx="${v.rx}"` : '';
  const plate = v.plate
    ? `\n  <rect width="${CANVAS}" height="${CANVAS}"${rx} fill="${v.plate}" />`
    : '';

  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${CANVAS} ${CANVAS}" width="${CANVAS}" height="${CANVAS}">
  <!-- GENERATED by scripts/export-icons.mjs from icon-master.svg. Do not edit.
       Colour and plate are applied here; the master carries geometry only. -->${plate}
  <g ${paint}>
${body}
  </g>
</svg>
`;
}

// ---------------------------------------------------------------- rasterize

async function loadResvg() {
  const entry = join(TOOLS, 'node_modules', '@resvg', 'resvg-js', 'index.js');
  const attempt = () => import(pathToFileURL(entry).href);
  if (existsSync(entry)) {
    // A present index.js does not mean a working install: resvg loads its native
    // code from a per-platform optionalDependency, which an interrupted or
    // --no-optional install can leave out. Retry once rather than failing forever
    // with a raw MODULE_NOT_FOUND that gives no hint to clear build/.
    try { return await attempt(); } catch {
      console.log('  cached resvg is incomplete, reinstalling ...');
      rmSync(TOOLS, { recursive: true, force: true });
    }
  }
  console.log(`  fetching ${RESVG} into build/icon-tools ...`);
  mkdirSync(TOOLS, { recursive: true });
  writeFileSync(join(TOOLS, 'package.json'), '{"private":true}\n');
  // execSync (string form) rather than execFileSync: npm is a .cmd on Windows and
  // needs a shell, but shell + an argv array trips DEP0190.
  //
  // --ignore-scripts: resvg-js ships prebuilt native binaries as per-platform
  // optionalDependencies and needs no install hooks, so nothing here has a reason
  // to execute arbitrary code at install time.
  execSync(`npm install --no-save --ignore-scripts --prefix "${TOOLS}" ${RESVG}`,
    { stdio: 'inherit' });
  return attempt();
}

const { Resvg } = await loadResvg();

function hexAt(px, i) {
  return '#' + [px[i], px[i + 1], px[i + 2]]
    .map((c) => c.toString(16).padStart(2, '0')).join('').toUpperCase();
}

// ---------------------------------------------------------------- run

const problems = [];

console.log(`safe zone (limit ${SAFE_RADIUS}):`);
for (const id of [...new Set(VARIANTS.map((v) => v.geo))]) {
  const { worst, at } = worstRadius(geometryOf(id));
  const ok = worst <= SAFE_RADIUS;
  if (!ok) problems.push(`${id} breaches the safe zone at r ${worst.toFixed(1)}`);
  console.log(`  ${id.padEnd(10)}  r = ${worst.toFixed(1)}`
    + ` at (${at.map((n) => n.toFixed(0)).join(',')})  ${ok ? 'ok' : 'BREACH'}`);
}
if (problems.length) {
  console.error('\nrefusing to export: artwork would be clipped by a circular crop.');
  process.exit(1);
}

// Everything is composed, checked and buffered before a single byte is written, so a
// failure cannot leave half-updated artwork on disk.
console.log('\nvariants:');
const pending = [];

for (const v of VARIANTS) {
  const svg = compose(v);
  const label = [];

  // Source-level palette check. Covers every variant, including the mono cuts, which
  // have no raster ladder to sample, and is exact rather than sampled. The previous
  // version checked only the two variants flagged assertLight - seven of nine went
  // unchecked, so ink leaking into a dark variant passed silently.
  const forbidden = THEME_INK[v.theme === 'light' ? 'dark' : 'light'];
  if (!svg.includes(v.stroke)) problems.push(`${v.file}: stroke ${v.stroke} missing`);
  for (const bad of forbidden) {
    if (svg.includes(bad)) {
      problems.push(`${v.file}: ${v.theme === 'light' ? 'dark' : 'light'}-theme `
        + `${bad} present in a ${v.theme} variant`);
    }
  }
  pending.push([join(ASSETS, `${v.file}.svg`), Buffer.from(svg, 'utf8')]);

  for (const size of v.sizes) {
    const img = new Resvg(svg, {
      fitTo: { mode: 'width', value: size },
      font: { loadSystemFonts: false }
    }).render();

    // Pixel check, only where a fully opaque pixel is guaranteed. Below 64px the
    // stroke is thin enough that antialiasing can leave no pixel at full coverage,
    // so asserting presence there fails spuriously - which is why the source check
    // above is the one that carries every size.
    if (size >= 64) {
      const px = img.pixels;
      let seen = false;
      for (let i = 0; i < px.length && !seen; i += 4) {
        if (px[i + 3] >= 250 && hexAt(px, i) === v.stroke.toUpperCase()) seen = true;
      }
      if (!seen) problems.push(`${v.file}@${size}: stroke ${v.stroke} did not render`);
    }

    pending.push([join(ASSETS, `${v.file}-${size}.png`), img.asPng()]);
    label.push(String(size));
  }
  console.log(`  ${v.file.padEnd(24)}  ${label.join(' ') || '(svg only)'}`);
}

if (problems.length) {
  console.error(`\n${problems.length} problem(s); nothing was written:`);
  for (const p of problems) console.error(`  ${p}`);
  process.exit(1);
}

for (const [path, buf] of pending) writeFileSync(path, buf);

for (const f of STALE) {
  const p = join(ASSETS, f);
  if (existsSync(p)) { rmSync(p); console.log(`  removed stale ${f}`); }
}

console.log(`\ndone. ${pending.length} files written.`);
