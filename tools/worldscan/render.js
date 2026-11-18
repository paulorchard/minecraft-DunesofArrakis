#!/usr/bin/env node
// worldscan: read generated chunks (.mca), print terrain statistics, write stats JSON + hillshaded top-down PNG.
//
//   node render.js <regionDir> <outPrefix> <x0> <z0> <size> [--scale 2] [--sand 63] [--wind 115] [--window 256] [--crest-test]
//
//   regionDir  <world>/dimensions/<namespace>/<path>/region   (26.1 world layout)
//   outPrefix  e.g. out/dunes_seed1 -> out/dunes_seed1_x-1024_z-1024_2048_h0-45.png / .json
//   --sand     Y of the top block of the flat sand floor (default 63). "Height above sand level" = top block Y - sand.
//   --wind     direction the wind blows toward, degrees from +x toward +z (default 115). Used for windward/leeward.
//   --window   side of the windows used to list the tallest / flattest dune fields (default 256)
//
// All numbers come from the saved blocks, not from heightmaps (heightmaps are only cross-checked).
const { chunks, unpack } = require('./nbt.js');
const fs = require('fs'), zlib = require('zlib'), path = require('path');

const args = process.argv.slice(2);
function opt(name, def) { const i = args.indexOf('--' + name); if (i < 0) return def; const v = args[i + 1]; args.splice(i, 2); return isNaN(+v) ? v : +v; }
const isSand = n => n === 'minecraft:sand' || n === 'islandcraft_dunesofarrakis:dune_sand';
const SCALE = opt('scale', 2), SAND = opt('sand', 63), WIND = opt('wind', 115), WIN = opt('window', 256), GR = opt('grad', 4);
const [dir, outPrefix, X0, Z0, SIZE] = args; const x0 = +X0, z0 = +Z0, S = +SIZE;
if (!dir || isNaN(x0) || isNaN(z0) || !S) { console.error('usage: node render.js <regionDir> <outPrefix> <x0> <z0> <size> [--scale 2] [--sand 63] [--wind 115]'); process.exit(1); }

const NO = -32768;
const top = new Int16Array(S * S).fill(NO);        // highest non-air block (from block data)
const hmTop = new Int16Array(S * S).fill(NO);      // from the WORLD_SURFACE heightmap (cross-check)
const topBlock = new Array(S * S);
const counts = { chunks: 0, fullChunks: 0, airBelowSurface: 0, nonSandNonBedrock: 0, bedrockAboveBottom: 0, heightmapMismatch: 0, columns: 0, minY: 0 };
const otherBlocks = {};
let seenMinY = null;

for (const f of fs.readdirSync(dir).filter(f => f.endsWith('.mca'))) {
  for (const c of chunks(path.join(dir, f))) {
    const bx = c.xPos * 16, bz = c.zPos * 16;
    if (bx + 15 < x0 || bx >= x0 + S || bz + 15 < z0 || bz >= z0 + S) continue;
    if (c.Status !== 'minecraft:full') continue;
    counts.chunks++; counts.fullChunks++;
    const minY = c.yPos * 16; if (seenMinY === null) seenMinY = minY;
    // decode every section once: array of 4096 state names (or a single name)
    const secs = [];
    for (const s of c.sections) {
      const bs = s.block_states; if (!bs) continue;
      const pal = bs.palette.map(p => p.Name);
      let idx = null;
      if (pal.length > 1) idx = unpack(bs.data, Math.max(4, Math.ceil(Math.log2(pal.length))), 4096);
      secs.push({ y: s.Y, pal, idx });
    }
    secs.sort((a, b) => b.y - a.y);
    const hm = c.Heightmaps && c.Heightmaps.WORLD_SURFACE ? unpack(c.Heightmaps.WORLD_SURFACE, 9, 256) : null;
    // per column top
    const colTop = new Int16Array(256).fill(NO), colTopName = new Array(256);
    for (let i = 0; i < 256; i++) {
      const lx = i & 15, lz = i >> 4;
      outer: for (const s of secs) {
        if (!s.idx) { if (s.pal[0] === 'minecraft:air') continue; colTop[i] = s.y * 16 + 15; colTopName[i] = s.pal[0]; break; }
        for (let ly = 15; ly >= 0; ly--) {
          const n = s.pal[s.idx[(ly << 8) | (lz << 4) | lx]];
          if (n !== 'minecraft:air') { colTop[i] = s.y * 16 + ly; colTopName[i] = n; break outer; }
        }
      }
    }
    // block census: air below the column top, anything that is not sand/bedrock, bedrock above the bottom layers
    for (const s of secs) {
      const base = s.y * 16;
      if (!s.idx) {
        const n = s.pal[0];
        if (isSand(n)) continue;
        for (let i = 0; i < 256; i++) for (let ly = 0; ly < 16; ly++) tally(n, base + ly, colTop[i]);
      } else {
        for (let k = 0; k < 4096; k++) {
          const n = s.pal[s.idx[k]]; if (isSand(n)) continue;
          tally(n, base + (k >> 8), colTop[k & 255]);
        }
      }
    }
    function tally(n, y, ct) {
      if (n === 'minecraft:air') { if (y < ct) counts.airBelowSurface++; return; }
      if (n === 'minecraft:bedrock') { if (y > minY + 4) counts.bedrockAboveBottom++; return; }
      counts.nonSandNonBedrock++; otherBlocks[n] = (otherBlocks[n] || 0) + 1;
    }
    for (let i = 0; i < 256; i++) {
      const wx = bx + (i & 15) - x0, wz = bz + (i >> 4) - z0; if (wx < 0 || wz < 0 || wx >= S || wz >= S) continue;
      const k = wz * S + wx; top[k] = colTop[i]; topBlock[k] = colTopName[i];
      if (hm) { hmTop[k] = hm[i] + minY - 1; if (hmTop[k] !== colTop[i]) counts.heightmapMismatch++; }
      counts.columns++;
    }
  }
}
counts.minY = seenMinY;
if (!counts.columns) { console.error('no full chunks found in the requested area'); process.exit(2); }
const covered = counts.columns;

// ---- height above sand level
const H = new Float32Array(S * S).fill(NaN);
for (let i = 0; i < S * S; i++) if (top[i] !== NO) H[i] = top[i] - SAND;
const valid = []; for (let i = 0; i < S * S; i++) if (!isNaN(H[i])) valid.push(H[i]);
valid.sort((a, b) => a - b);
const pct = (a, p) => a[Math.min(a.length - 1, Math.floor(p * a.length))];
const stats = {
  area: { x0, z0, size: S, columns: covered, fullChunks: counts.fullChunks, sandLevelTopBlockY: SAND },
  heightAboveSand: { min: valid[0], p5: pct(valid, .05), p50: pct(valid, .5), p95: pct(valid, .95), max: valid[valid.length - 1], mean: +(valid.reduce((a, b) => a + b, 0) / valid.length).toFixed(2) },
};
// ---- relief per 32x32 window
const rel = [];
for (let wz = 0; wz + 32 <= S; wz += 32) for (let wx = 0; wx + 32 <= S; wx += 32) {
  let mn = 1e9, mx = -1e9, ok = true;
  for (let z = 0; z < 32 && ok; z++) for (let x = 0; x < 32; x++) { const h = H[(wz + z) * S + wx + x]; if (isNaN(h)) { ok = false; break; } if (h < mn) mn = h; if (h > mx) mx = h; }
  if (ok) rel.push(mx - mn);
}
rel.sort((a, b) => a - b);
stats.relief32 = { windows: rel.length, median: pct(rel, .5), p90: pct(rel, .9), max: rel[rel.length - 1] };
// ---- steepest neighbour step, fractions
let maxStep = 0, atSand = 0, nonSandTop = 0, n = 0;
for (let z = 0; z < S; z++) for (let x = 0; x < S; x++) {
  const i = z * S + x; if (isNaN(H[i])) continue; n++;
  if (H[i] === 0) atSand++;
  if (!isSand(topBlock[i])) nonSandTop++;
  if (x + 1 < S && !isNaN(H[i + 1])) maxStep = Math.max(maxStep, Math.abs(H[i + 1] - H[i]));
  if (z + 1 < S && !isNaN(H[i + S])) maxStep = Math.max(maxStep, Math.abs(H[i + S] - H[i]));
}
stats.steepestNeighbourStep = maxStep;
stats.fractionAtSandLevel = +(atSand / n).toFixed(4);
stats.fractionTopNotSand = +(nonSandTop / n).toFixed(6);
stats.blocks = {
  airBelowSurface: counts.airBelowSurface, nonSandNonBedrockBlocks: counts.nonSandNonBedrock, otherBlockTypes: otherBlocks,
  bedrockAboveBottomFiveLayers: counts.bedrockAboveBottom, heightmapDisagreesWithBlocks: counts.heightmapMismatch,
};
// ---- gradient, crest direction + spread
const g = (x, z) => { const c = H[z * S + x]; return isNaN(c) ? 0 : c; };
let Jxx = 0, Jzz = 0, Jxz = 0, cs = 0, sn = 0, wsum = 0;
const wu = [Math.cos(WIND * Math.PI / 180), Math.sin(WIND * Math.PI / 180)];
const slopesW = [], slopesL = [], magW = [], magL = [];
const GX = new Float32Array(S * S), GZ = new Float32Array(S * S);
// gradient over +-GR blocks: a 1-block central difference of a gentle slope only takes the values 0 / 0.5 / 1 (block quantisation) and biases the means
for (let z = GR; z < S - GR; z++) for (let x = GR; x < S - GR; x++) {
  const i = z * S + x; if (isNaN(H[i])) continue;
  const gx = (g(x + GR, z) - g(x - GR, z)) / (2 * GR), gz = (g(x, z + GR) - g(x, z - GR)) / (2 * GR); GX[i] = gx; GZ[i] = gz;
  const m2 = gx * gx + gz * gz; if (m2 < 0.0016) continue; // ignore |g| < 0.04 (flat)
  Jxx += gx * gx; Jzz += gz * gz; Jxz += gx * gz;
  const phi = Math.atan2(gz, gx); cs += m2 * Math.cos(2 * phi); sn += m2 * Math.sin(2 * phi); wsum += m2;
  if (H[i] < 0.5) continue;
  const du = gx * wu[0] + gz * wu[1], mag = Math.sqrt(m2);
  if (du > 0.04) { slopesW.push(Math.atan(du)); magW.push(Math.atan(mag)); }
  else if (du < -0.04) { slopesL.push(Math.atan(-du)); magL.push(Math.atan(mag)); }
}
const deg = r => r * 180 / Math.PI, mean = a => a.length ? a.reduce((s, v) => s + v, 0) / a.length : NaN;
const gradOrient = 0.5 * Math.atan2(2 * Jxz, Jxx - Jzz);               // dominant gradient orientation (cross-crest axis)
let crestDir = (deg(gradOrient) + 90 + 360) % 180;                        // crest direction, degrees from +x toward +z, mod 180
const Rbar = Math.sqrt(cs * cs + sn * sn) / wsum;
const orientSd = deg(Math.sqrt(-2 * Math.log(Math.max(1e-9, Rbar))) / 2);
stats.crests = {
  prevailingCrestDirectionDeg: +crestDir.toFixed(1), crossCrestAxisDeg: +((crestDir + 90) % 180).toFixed(1),
  orientationSpreadDeg_circularSd: +orientSd.toFixed(1), resultantLengthR: +Rbar.toFixed(3),
  note: 'degrees from +x (east) toward +z (south), mod 180; spread = circular standard deviation of the gradient orientation, weighted by slope^2',
};
const pctl = (a, p) => { const s = [...a].sort((x, y) => x - y); return s.length ? deg(s[Math.min(s.length - 1, Math.floor(p * s.length))]) : NaN; };
stats.slopes = {
  windDirectionTowardDeg: WIND,
  windwardAlongWind: { columns: slopesW.length, meanDeg: +deg(mean(slopesW)).toFixed(1), p90Deg: +pctl(slopesW, .9).toFixed(1) },
  leewardAlongWind: { columns: slopesL.length, meanDeg: +deg(mean(slopesL)).toFixed(1), p90Deg: +pctl(slopesL, .9).toFixed(1) },
  windwardSteepestDescent: { meanDeg: +deg(mean(magW)).toFixed(1) }, leewardSteepestDescent: { meanDeg: +deg(mean(magL)).toFixed(1) },
  note: 'gradients over +-GR blocks (default 4); columns with |slope|>0.04 and height>0.5 above sand; "along wind" = directional derivative along the wind axis (cross-crest); windward = height rising along the wind, leeward = falling',
};
// ---- crest spacing and crest elongation (autocorrelation) along / across the wind axis
{
  const ux = wu[0], uz = wu[1], vx = -uz, vz = ux, step = 4, cx = S / 2, cz = S / 2;
  const samples = [];
  for (let k = 0; k < 6000; k++) { const x = 64 + ((k * 7919) % (S - 128)), z = 64 + ((k * 104729) % (S - 128)); samples.push([x, z]); }
  const at = (x, z) => { const xi = Math.round(x), zi = Math.round(z); if (xi < 0 || zi < 0 || xi >= S || zi >= S) return NaN; return H[zi * S + xi]; };
  const corr = (dx, dz) => { let sa = 0, sb = 0, saa = 0, sbb = 0, sab = 0, m = 0; for (const [x, z] of samples) { const a = at(x, z), b = at(x + dx, z + dz); if (isNaN(a) || isNaN(b)) continue; m++; sa += a; sb += b; saa += a * a; sbb += b * b; sab += a * b; } const va = saa / m - (sa / m) ** 2, vb = sbb / m - (sb / m) ** 2; return (sab / m - sa * sb / (m * m)) / Math.sqrt(va * vb); };
  const lags = [8, 16, 32, 48, 64, 80, 96, 128, 160, 192, 256, 320, 400, 512];
  stats.autocorrelation = { alongCrests: {}, acrossCrests: {} };
  for (const L of lags) { stats.autocorrelation.alongCrests[L] = +corr(vx * L, vz * L).toFixed(2); stats.autocorrelation.acrossCrests[L] = +corr(ux * L, uz * L).toFixed(2); }
  // spacing: local maxima on transects along the wind axis
  const sp = [], ch = [];
  for (let t = 0; t < 120; t++) {
    const sx = 64 + ((t * 7907) % (S - 128)), sz = 64 + ((t * 6007) % (S - 128)); const prof = [];
    for (let s = -300; s <= 300; s += 1) prof.push(at(sx + ux * s, sz + uz * s));
    const sm = prof.map((_, i) => { let a = 0, c = 0; for (let j = -6; j <= 6; j++) { const v = prof[i + j]; if (v !== undefined && !isNaN(v)) { a += v; c++; } } return c ? a / c : NaN; });
    const peaks = [];
    for (let i = 20; i < sm.length - 20; i++) {
      if (isNaN(sm[i]) || sm[i] < 6) continue; let isMax = true; for (let j = -20; j <= 20; j++) if (j < 0 ? sm[i + j] >= sm[i] : sm[i + j] > sm[i]) { isMax = false; break; }
      if (!isMax) continue; let lo1 = sm[i], lo2 = sm[i]; for (let j = 1; j <= 150 && i - j >= 0; j++) lo1 = Math.min(lo1, sm[i - j]); for (let j = 1; j <= 150 && i + j < sm.length; j++) lo2 = Math.min(lo2, sm[i + j]);
      if (sm[i] - Math.max(lo1, lo2) >= 5) { peaks.push(i); ch.push(sm[i]); }
    }
    for (let i = 1; i < peaks.length; i++) sp.push(peaks[i] - peaks[i - 1]);
  }
  sp.sort((a, b) => a - b); ch.sort((a, b) => a - b);
  stats.crestHeightAboveSand = ch.length ? { samples: ch.length, p10: +pct(ch, .1).toFixed(1), median: +pct(ch, .5).toFixed(1), p90: +pct(ch, .9).toFixed(1), max: +ch[ch.length - 1].toFixed(1) } : null;
  stats.crestSpacingBlocks = sp.length ? { samples: sp.length, p10: pct(sp, .1), median: pct(sp, .5), p90: pct(sp, .9) } : null;
}
// ---- chunk-border seams: mean |step| across a chunk border vs inside chunks
{
  let bx = 0, bn = 0, ix = 0, inn = 0, bz = 0, bzn = 0, iz = 0, izn = 0;
  for (let z = 0; z < S - 1; z++) for (let x = 0; x < S - 1; x++) {
    const i = z * S + x; if (isNaN(H[i])) continue;
    const gxs = (x0 + x) & 15, gzs = (z0 + z) & 15;
    if (!isNaN(H[i + 1])) { const d = Math.abs(H[i + 1] - H[i]); if (gxs === 15) { bx += d; bn++; } else { ix += d; inn++; } }
    if (!isNaN(H[i + S])) { const d = Math.abs(H[i + S] - H[i]); if (gzs === 15) { bz += d; bzn++; } else { iz += d; izn++; } }
  }
  stats.chunkSeams = { meanStepAcrossXBorder: +(bx / bn).toFixed(4), meanStepInsideX: +(ix / inn).toFixed(4), meanStepAcrossZBorder: +(bz / bzn).toFixed(4), meanStepInsideZ: +(iz / izn).toFixed(4),
    ratioX: +((bx / bn) / (ix / inn)).toFixed(3), ratioZ: +((bz / bzn) / (iz / izn)).toFixed(3), note: 'ratio ~1.0 = no visible 16-block seams' };
}
// ---- tallest / flattest windows (for choosing where to look)
{
  const wins = [];
  for (let wz = 0; wz + WIN <= S; wz += WIN / 2) for (let wx = 0; wx + WIN <= S; wx += WIN / 2) {
    const a = []; for (let z = 0; z < WIN; z += 2) for (let x = 0; x < WIN; x += 2) { const h = H[(wz + z) * S + wx + x]; if (!isNaN(h)) a.push(h); }
    if (a.length < 100) continue; a.sort((p, q) => p - q);
    wins.push({ centerX: x0 + wx + WIN / 2, centerZ: z0 + wz + WIN / 2, p50: pct(a, .5), p95: pct(a, .95), max: a[a.length - 1], atSand: a.filter(v => v === 0).length / a.length });
  }
  const best = [...wins].sort((p, q) => (q.p50 + q.p95) - (p.p50 + p.p95)).slice(0, 3);
  const flat = [...wins].sort((p, q) => (p.p95 + p.p50) - (q.p95 + q.p50)).slice(0, 3);
  stats.windows = { side: WIN, tallest: best, flattest: flat };
}
// ---- file names carry the height scale used for the colours
const hmin = 0, hmax = Math.ceil(Math.max(45, stats.heightAboveSand.max) / 5) * 5;
const base = `${outPrefix}_x${x0}_z${z0}_${S}_h${hmin}-${hmax}`;
fs.mkdirSync(path.dirname(base), { recursive: true });
fs.writeFileSync(base + '.json', JSON.stringify(stats, null, 2) + '\n');

// ---- hillshaded top-down PNG: light from the north-west, sand tinted by height above the floor
const SS = S * SCALE, img = Buffer.alloc(SS * SS * 3);
const hraw = (x, z) => { x = Math.max(0, Math.min(S - 1, x)); z = Math.max(0, Math.min(S - 1, z)); const v = H[z * S + x]; return isNaN(v) ? 0 : v; };
const HB = new Float32Array(S * S); for (let z = 0; z < S; z++) for (let x = 0; x < S; x++) { let a = 0; for (let j = -2; j <= 2; j++) for (let i = -2; i <= 2; i++) a += hraw(x + i, z + j); HB[z * S + x] = a / 25; }
const hh = (x, z) => { x = Math.max(0, Math.min(S - 1, x)); z = Math.max(0, Math.min(S - 1, z)); return HB[z * S + x]; };
const L = [-0.55, -0.55, 0.63]; // light vector (x, z, up) -> from NW and above
for (let z = 0; z < S; z++) for (let x = 0; x < S; x++) {
  const h = hh(x, z); let r, gg, b;
  if (isNaN(H[z * S + x])) { r = gg = b = 20; }
  else {
    const dx = (hh(x + 1, z) - hh(x - 1, z)) / 2, dz = (hh(x, z + 1) - hh(x, z - 1)) / 2;
    const nx = -dx, nz = -dz, ny = 1, len = Math.hypot(nx, nz, ny);
    let sh = (nx * L[0] + nz * L[1] + ny * L[2]) / len; sh = Math.max(0.12, Math.min(1, sh * 1.25));
    const t = Math.max(0, Math.min(1, (h - hmin) / (hmax - hmin)));
    const cr = 228 - 30 * t, cg = 196 - 62 * t, cb = 140 - 70 * t; // pale floor -> deep orange crest
    r = cr * sh; gg = cg * sh; b = cb * sh;
  }
  for (let dz = 0; dz < SCALE; dz++) for (let dx = 0; dx < SCALE; dx++) { const o = ((z * SCALE + dz) * SS + x * SCALE + dx) * 3; img[o] = r; img[o + 1] = gg; img[o + 2] = b; }
}
const crcT = new Uint32Array(256); for (let k = 0; k < 256; k++) { let c = k; for (let j = 0; j < 8; j++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; crcT[k] = c >>> 0; }
const crc = bb => { let c = 0xffffffff; for (const v of bb) c = crcT[(c ^ v) & 255] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
const chunkPng = (t, d) => { const l = Buffer.alloc(4); l.writeUInt32BE(d.length); const td = Buffer.concat([Buffer.from(t), d]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([l, td, c]); };
const ih = Buffer.alloc(13); ih.writeUInt32BE(SS, 0); ih.writeUInt32BE(SS, 4); ih[8] = 8; ih[9] = 2;
const raw = Buffer.alloc((SS * 3 + 1) * SS); for (let z = 0; z < SS; z++) { raw[z * (SS * 3 + 1)] = 0; img.copy(raw, z * (SS * 3 + 1) + 1, z * SS * 3, (z + 1) * SS * 3); }
fs.writeFileSync(base + '.png', Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunkPng('IHDR', ih), chunkPng('IDAT', zlib.deflateSync(raw, { level: 6 })), chunkPng('IEND', Buffer.alloc(0))]));

// ---- print a readable summary
const s = stats;
console.log(`area x ${x0}..${x0 + S - 1} z ${z0}..${z0 + S - 1}  full chunks ${s.area.fullChunks}  columns ${s.area.columns}`);
console.log(`height above sand: min ${s.heightAboveSand.min}  p5 ${s.heightAboveSand.p5}  p50 ${s.heightAboveSand.p50}  p95 ${s.heightAboveSand.p95}  max ${s.heightAboveSand.max}  mean ${s.heightAboveSand.mean}`);
console.log(`relief per 32x32: median ${s.relief32.median}  p90 ${s.relief32.p90}  max ${s.relief32.max}   steepest neighbour step ${s.steepestNeighbourStep}`);
console.log(`columns at sand level ${(s.fractionAtSandLevel * 100).toFixed(1)}%   top block not sand ${(s.fractionTopNotSand * 100).toFixed(3)}%`);
console.log(`air below surface ${s.blocks.airBelowSurface}   non-sand non-bedrock blocks ${s.blocks.nonSandNonBedrockBlocks} ${JSON.stringify(s.blocks.otherBlockTypes)}   bedrock above bottom 5 layers ${s.blocks.bedrockAboveBottomFiveLayers}   heightmap/block disagreements ${s.blocks.heightmapDisagreesWithBlocks}`);
console.log(`crest direction ${s.crests.prevailingCrestDirectionDeg} deg (cross-crest axis ${s.crests.crossCrestAxisDeg}), orientation spread ${s.crests.orientationSpreadDeg_circularSd} deg (R=${s.crests.resultantLengthR})`);
console.log(`slopes along wind ${WIND} deg: windward mean ${s.slopes.windwardAlongWind.meanDeg} (p90 ${s.slopes.windwardAlongWind.p90Deg}), leeward mean ${s.slopes.leewardAlongWind.meanDeg} (p90 ${s.slopes.leewardAlongWind.p90Deg})`);
console.log(`crest spacing along wind: ${JSON.stringify(s.crestSpacingBlocks)}   crest heights: ${JSON.stringify(s.crestHeightAboveSand)}`);
console.log(`autocorrelation along crests ${JSON.stringify(s.autocorrelation.alongCrests)}`);
console.log(`autocorrelation across crests ${JSON.stringify(s.autocorrelation.acrossCrests)}`);
console.log(`chunk seams: x ratio ${s.chunkSeams.ratioX}, z ratio ${s.chunkSeams.ratioZ}`);
console.log(`tallest windows ${JSON.stringify(s.windows.tallest.map(w => [w.centerX, w.centerZ, w.p50, w.p95]))}`);
console.log(`flattest windows ${JSON.stringify(s.windows.flattest.map(w => [w.centerX, w.centerZ, w.p50, w.p95]))}`);
console.log('wrote ' + base + '.png/.json');
