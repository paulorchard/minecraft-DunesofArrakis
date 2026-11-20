#!/usr/bin/env node
// Window checker for the rock relief (prompt 06): compares generated chunks with the analytic outline and relief.
//   node windowcheck_relief.js <regionDir> <mask.bin> <rockmap.i16> <x0> <z0> <size> <outPrefix> [--save heights.i16] [--baseline heights.i16]
// mask.bin    one byte per column, continent 1..5 inside the footprint, 0 outside (coast.sh mask)
// rockmap.i16 per column the analytic top block y of the rock, -32768 outside the footprint (relief.sh rockmap)
// Surface rule: below or at the analytic rock top the block is sandstone (inside the footprint), above it dune sand.
const fs = require('fs'), path = require('path');
const { chunks, unpack } = require('./nbt.js');
const args = process.argv.slice(2), pos = [], opt = {};
for (let i = 0; i < args.length; i++) { if (args[i].startsWith('--')) { opt[args[i].slice(2)] = args[i + 1]; i++; } else pos.push(args[i]); }
const [dir, maskFile, rockFile, X0, Z0, SIZE, outPrefix] = pos;
const x0 = +X0, z0 = +Z0, S = +SIZE, SAND = 63;
if (!dir || !maskFile || !rockFile || !outPrefix || !S) { console.error('usage: node windowcheck_relief.js <regionDir> <mask.bin> <rockmap.i16> <x0> <z0> <size> <outPrefix> [--save f] [--baseline f]'); process.exit(1); }
const ROCK = 'minecraft:sandstone', DUNE = 'islandcraft_dunesofarrakis:dune_sand', SMOOTH = 'minecraft:smooth_sandstone';
// prompt 09: LAYERS=off expects the pure sandstone below the surface of prompt 08; otherwise any rock block below the surface (sandstone, tough rock, stone, blobs, deepslate)
const LAYERS = process.env.LAYERS !== 'off';
const ALLOWED = new Set([DUNE, ROCK, SMOOTH, 'minecraft:cut_sandstone', 'minecraft:air', 'minecraft:bedrock', 'minecraft:red_sandstone', 'minecraft:terracotta', 'minecraft:orange_terracotta', 'minecraft:yellow_terracotta', 'minecraft:brown_terracotta', 'minecraft:red_terracotta', 'minecraft:white_terracotta', 'minecraft:light_gray_terracotta', 'minecraft:stone', 'minecraft:deepslate', 'minecraft:andesite', 'minecraft:diorite', 'minecraft:granite', 'minecraft:tuff', 'minecraft:calcite']);
const isRockBlock = n => !!n && n !== DUNE && n !== 'minecraft:air' && n !== 'minecraft:bedrock';
const OUTLINE_Y = LAYERS ? SAND : SAND - 1; // with layers there is sandstone below the dunes at y 62, so the outline is read at y 63 (dune sand outside, rock inside)
const bandNames = [{}, {}, {}, {}, {}], wdCount = new Uint8Array(S * S);
const mask = fs.readFileSync(maskFile); if (mask.length !== S * S) { console.error('mask size mismatch'); process.exit(1); }
const rb = fs.readFileSync(rockFile); if (rb.length !== S * S * 2) { console.error('rockmap size mismatch'); process.exit(1); }
const rtop = new Int16Array(rb.buffer.slice(rb.byteOffset, rb.byteOffset + rb.length));
const NO = -32768;
const top = new Int16Array(S * S).fill(NO);
const topKind = new Uint8Array(S * S); // 0 none, 1 dune sand, 2 sandstone, 3 other
const sand62 = new Uint8Array(S * S); // block at y = 62 is sandstone
const c = { fullChunks: 0, columns: 0, airBelowSurface: 0, bedrockAbove: 0, wrongBlocks: {}, floatingSand: 0, sandstoneAboveRock: 0 };
const wrongCols = new Set();

for (const f of fs.readdirSync(dir).filter(f => f.endsWith('.mca'))) {
  for (const ch of chunks(path.join(dir, f))) {
    const bx = ch.xPos * 16, bz = ch.zPos * 16;
    if (bx + 15 < x0 || bx >= x0 + S || bz + 15 < z0 || bz >= z0 + S) continue;
    if (ch.Status !== 'minecraft:full') continue;
    c.fullChunks++;
    const minY = ch.yPos * 16;
    const secs = [];
    for (const s of ch.sections) {
      const bs = s.block_states; if (!bs) continue;
      const pal = bs.palette.map(p => p.Name);
      secs.push({ y: s.Y, pal, idx: pal.length > 1 ? unpack(bs.data, Math.max(4, Math.ceil(Math.log2(pal.length))), 4096) : null });
    }
    secs.sort((a, b) => b.y - a.y);
    const colTop = new Int16Array(256).fill(NO), colName = new Array(256);
    for (let i = 0; i < 256; i++) {
      const lx = i & 15, lz = i >> 4;
      outer: for (const s of secs) {
        if (!s.idx) { if (s.pal[0] === 'minecraft:air') continue; colTop[i] = s.y * 16 + 15; colName[i] = s.pal[0]; break; }
        for (let ly = 15; ly >= 0; ly--) { const n = s.pal[s.idx[(ly << 8) | (lz << 4) | lx]]; if (n !== 'minecraft:air') { colTop[i] = s.y * 16 + ly; colName[i] = n; break outer; } }
      }
    }
    const check = (n, y, i) => {
      const wx = bx + (i & 15) - x0, wz = bz + (i >> 4) - z0; if (wx < 0 || wz < 0 || wx >= S || wz >= S) return;
      const k = wz * S + wx, m = mask[k], rt = rtop[k];
      if (y === OUTLINE_Y) sand62[k] = (LAYERS ? isRockBlock(n) : n === ROCK) ? 1 : 0;
      if (LAYERS) { const bnd = y > 63 ? 0 : y >= 8 ? 1 : y >= 0 ? 2 : y >= minY + 5 ? 3 : 4; bandNames[bnd][n] = (bandNames[bnd][n] || 0) + 1; if (y <= 62 && (n === ROCK || n === SMOOTH)) wdCount[k]++; }
      if (n === 'minecraft:air') { if (y < colTop[i]) c.airBelowSurface++; return; }
      if (n === 'minecraft:bedrock') { if (y > minY + 4) c.bedrockAbove++; return; }
      const expect = (m && rt !== NO && y <= rt) || (LAYERS && y < SAND) ? ROCK : DUNE;
      if (LAYERS ? (expect === ROCK ? n === DUNE : n !== DUNE) : n !== expect) {
        const key = n + (expect === ROCK ? ' where sandstone is due (at or below the rock surface)' : (m ? ' above the rock surface' : ' in a dune column'));
        c.wrongBlocks[key] = (c.wrongBlocks[key] || 0) + 1; wrongCols.add(k);
        if (expect === ROCK && n === DUNE) c.floatingSand++; else if (expect === DUNE && n === ROCK) c.sandstoneAboveRock++;
      }
    };
    for (const s of secs) {
      const base = s.y * 16;
      if (!s.idx) { for (let i = 0; i < 256; i++) for (let ly = 0; ly < 16; ly++) check(s.pal[0], base + ly, i); }
      else for (let k = 0; k < 4096; k++) check(s.pal[s.idx[k]], base + (k >> 8), k & 255);
    }
    for (let i = 0; i < 256; i++) {
      const wx = bx + (i & 15) - x0, wz = bz + (i >> 4) - z0; if (wx < 0 || wz < 0 || wx >= S || wz >= S) continue;
      const k = wz * S + wx; top[k] = colTop[i];
      topKind[k] = colName[i] === DUNE ? 1 : (LAYERS ? isRockBlock(colName[i]) : colName[i] === ROCK) ? 2 : colName[i] ? 3 : 0;
      c.columns++;
    }
  }
}
if (c.columns !== S * S) console.error(`WARNING: only ${c.columns} of ${S * S} columns were generated (full chunks: ${c.fullChunks})`);

const inside = new Uint8Array(S * S); let insideCols = 0;
for (let i = 0; i < S * S; i++) { inside[i] = mask[i] ? 1 : 0; insideCols += inside[i]; }

// ---- outline: sandstone at y = 62 exists exactly in the footprint (= the outline of prompt 05)
let outlineMismatch = 0, outlineRing = 0;
const near = (x, z) => { for (let dz = -1; dz <= 1; dz++) for (let dx = -1; dx <= 1; dx++) { const xx = x + dx, zz = z + dz; if (xx >= 0 && zz >= 0 && xx < S && zz < S && inside[zz * S + xx] !== inside[z * S + x]) return true; } return false; };
for (let z = 0; z < S; z++) for (let x = 0; x < S; x++) { const k = z * S + x; if ((sand62[k] === 1) !== (inside[k] === 1)) { outlineMismatch++; if (near(x, z)) outlineRing++; } }

// ---- relief: generated top vs analytic rock top (max rule: top >= rock top; equal => sandstone, above => dune sand)
let below = 0, equalNotRock = 0, aboveRock = 0, duneAbove = 0, equalRock = 0, maxDuneOver = 0, relMismatchRing = 0, relMismatchOnGrid = 0;
const mism = [];
for (let k = 0; k < S * S; k++) {
  if (!inside[k] || rtop[k] === NO || top[k] === NO) continue;
  const t = top[k], r = rtop[k];
  if (t < r) { below++; mism.push(k); }
  else if (t === r) { if (topKind[k] === 2) equalRock++; else { equalNotRock++; mism.push(k); } }
  else { if (topKind[k] === 1) { duneAbove++; if (t - r > maxDuneOver) maxDuneOver = t - r; } else { aboveRock++; mism.push(k); } }
}
for (const k of mism) { const x = k % S, z = (k / S) | 0; if (near(x, z)) relMismatchRing++; const wx = (x0 + x) & 15, wz = (z0 + z) & 15; if (wx === 0 || wx === 15 || wz === 0 || wz === 15) relMismatchOnGrid++; }

// ---- distance of footprint columns to the coast (chamfer 3-4), banking and exposed shares
const INF = 1e9, dist = new Float32Array(S * S);
for (let k = 0; k < S * S; k++) dist[k] = inside[k] ? INF : 0;
for (let z = 0; z < S; z++) for (let x = 0; x < S; x++) { const k = z * S + x; if (!inside[k]) continue; let v = dist[k]; if (x > 0) v = Math.min(v, dist[k - 1] + 1); if (z > 0) v = Math.min(v, dist[k - S] + 1); if (x > 0 && z > 0) v = Math.min(v, dist[k - S - 1] + 1.4142); if (x < S - 1 && z > 0) v = Math.min(v, dist[k - S + 1] + 1.4142); dist[k] = v; }
for (let z = S - 1; z >= 0; z--) for (let x = S - 1; x >= 0; x--) { const k = z * S + x; if (!inside[k]) continue; let v = dist[k]; if (x < S - 1) v = Math.min(v, dist[k + 1] + 1); if (z < S - 1) v = Math.min(v, dist[k + S] + 1); if (x < S - 1 && z < S - 1) v = Math.min(v, dist[k + S + 1] + 1.4142); if (x > 0 && z < S - 1) v = Math.min(v, dist[k + S - 1] + 1.4142); dist[k] = v; }
const band = (lo, hi) => { let sandTop = 0, rockTop = 0; for (let k = 0; k < S * S; k++) if (inside[k] && dist[k] > lo && dist[k] <= hi && topKind[k] > 0) { if (topKind[k] === 1) sandTop++; else rockTop++; } const n = sandTop + rockTop; return { columns: n, duneSandTop: sandTop, sandstoneTop: rockTop, shareDuneSand: n ? +(sandTop / n).toFixed(3) : null }; };
const banking = { coast_0_30m: band(0, 30), coast_30_100m: band(30, 100), inland_over_100m: band(100, 1e9) };
// coast sectors: stretches of coast (cells of the coast band) where dunes bank over rock vs rock stands clear, in 64 x 64 blocks
let sectBank = 0, sectClear = 0;
for (let sz = 0; sz + 64 <= S; sz += 64) for (let sx = 0; sx + 64 <= S; sx += 64) {
  let n = 0, sandN = 0;
  for (let z = sz; z < sz + 64; z++) for (let x = sx; x < sx + 64; x++) { const k = z * S + x; if (inside[k] && dist[k] > 0 && dist[k] <= 30 && topKind[k] > 0) { n++; if (topKind[k] === 1) sandN++; } }
  if (n >= 100) { if (sandN / n >= 0.5) sectBank++; else sectClear++; }
}

// ---- terraces (prompt 07): in each 64 x 64 coast sector, is at least one full step visible above the dunes? A step = a monotone rise of
// 3 or more blocks over at most 5 consecutive columns (slope 0.6 or more: steeper than the smooth ramp), all of them bare rock on top (not dune sand), within 100 m of the coast.
let vsSectors = 0, vsWith = 0, steps2 = 0, stepsRock = 0;
for (let sz = 0; sz + 64 <= S; sz += 64) for (let sx = 0; sx + 64 <= S; sx += 64) {
  let n = 0, found = false;
  for (let z = sz; z < sz + 64; z++) for (let x = sx; x < sx + 64; x++) { const k = z * S + x; if (inside[k] && dist[k] > 0 && dist[k] <= 30 && topKind[k] > 0) n++; }
  if (n < 100) continue;
  vsSectors++;
  for (let z = sz; z < sz + 64 && !found; z++) for (let x = sx; x < sx + 64 && !found; x++) {
    const k = z * S + x; if (!inside[k] || topKind[k] !== 2 || dist[k] > 100) continue;
    for (const [dx, dz] of [[1, 0], [0, 1]]) {
      let ok = true, prev = top[k];
      for (let i = 1; i <= 5 && ok; i++) { const xx = x + dx * i, zz = z + dz * i; if (xx >= S || zz >= S) { ok = false; break; } const q = zz * S + xx; if (!inside[q] || topKind[q] !== 2 || top[q] < prev) ok = false; else prev = top[q]; if (ok && prev - top[k] >= 3) { found = true; break; } }
      if (found) break;
    }
  }
  if (found) vsWith++;
}
for (let z = 0; z < S; z++) for (let x = 0; x < S; x++) {
  const k = z * S + x; if (!inside[k] || topKind[k] !== 2) continue;
  for (const [dx, dz] of [[1, 0], [0, 1]]) { const xx = x + dx, zz = z + dz; if (xx >= S || zz >= S) continue; const q = zz * S + xx; if (!inside[q] || topKind[q] !== 2) continue; stepsRock++; if (Math.abs(top[k] - top[q]) === 2) steps2++; }
}

// ---- steps between neighbouring columns of the footprint
let walls3 = 0, wallsDune = 0, wallsRock = 0, maxStep = 0, maxStepRock = 0, steps = 0;
const wallAt = [];
for (let z = 0; z < S; z++) for (let x = 0; x < S; x++) {
  const k = z * S + x; if (!inside[k] || top[k] === NO) continue;
  for (const [dx, dz] of [[1, 0], [0, 1]]) {
    const xx = x + dx, zz = z + dz; if (xx >= S || zz >= S) continue; const q = zz * S + xx; if (!inside[q] || top[q] === NO) continue;
    const d = Math.abs(top[k] - top[q]); steps++; if (d > maxStep) maxStep = d;
    const duneInvolved = topKind[k] === 1 || topKind[q] === 1;
    if (!duneInvolved && d > maxStepRock) maxStepRock = d;
    if (d > 3) { walls3++; if (duneInvolved) wallsDune++; else { wallsRock++; if (wallAt.length < 5) wallAt.push([x0 + x, z0 + z, d]); } }
  }
}
// ---- slope within 100 m of the coast and overall, rock-only columns: gradient over a +-4 block baseline (a 1-block rounding step over 2 blocks would read as 27 degrees)
let slopeN = 0, slope20 = 0, n100 = 0, slope25 = 0, slope35 = 0, slopeMax = 0;
const B = 4;
for (let z = B; z < S - B; z++) for (let x = B; x < S - B; x++) {
  const k = z * S + x; if (!inside[k] || topKind[k] !== 2) continue;
  if (topKind[k - B] !== 2 || topKind[k + B] !== 2 || topKind[k - B * S] !== 2 || topKind[k + B * S] !== 2) continue;
  const g = Math.hypot((top[k + B] - top[k - B]) / (2 * B), (top[k + B * S] - top[k - B * S]) / (2 * B)), deg = Math.atan(g) * 180 / Math.PI;
  slopeN++; if (deg > 20) slope20++; if (deg > 35) slope35++; if (g > slopeMax) slopeMax = g; if (dist[k] <= 100) { n100++; if (deg > 25) slope25++; }
}
// ---- rock surface heights (above sand) of rock-top columns, and dune mountains next to the coast
const hs = []; for (let k = 0; k < S * S; k++) if (inside[k] && topKind[k] === 2) hs.push(top[k] - SAND);
hs.sort((a, b) => a - b); const pct = p => hs.length ? hs[Math.min(hs.length - 1, Math.floor(p * hs.length))] : null;
const rockHeights = { columns: hs.length, p5: pct(.05), p50: pct(.5), p95: pct(.95), max: hs[hs.length - 1] ?? null };
// dune mountain over rock: footprint columns whose generated top is dune sand more than 10 blocks above the rock surface
let duneOver10 = 0, duneAsTallAsRockBeside = 0;
for (let k = 0; k < S * S; k++) if (inside[k] && topKind[k] === 1 && rtop[k] !== NO && top[k] - rtop[k] > 10) duneOver10++;
// outside columns within 30 m of the footprint: dune height above sand ("sand wall beside the rock")
const distOut = new Float32Array(S * S).fill(INF);
for (let k = 0; k < S * S; k++) if (inside[k]) distOut[k] = 0;
for (let z = 0; z < S; z++) for (let x = 0; x < S; x++) { const k = z * S + x; let v = distOut[k]; if (x > 0) v = Math.min(v, distOut[k - 1] + 1); if (z > 0) v = Math.min(v, distOut[k - S] + 1); distOut[k] = v; }
for (let z = S - 1; z >= 0; z--) for (let x = S - 1; x >= 0; x--) { const k = z * S + x; let v = distOut[k]; if (x < S - 1) v = Math.min(v, distOut[k + 1] + 1); if (z < S - 1) v = Math.min(v, distOut[k + S] + 1); distOut[k] = v; }
let outBand = 0, outBandTall = 0, outBandTall40 = 0; const outHs = [];
for (let k = 0; k < S * S; k++) if (!inside[k] && distOut[k] <= 30 && top[k] !== NO) { outBand++; const h = top[k] - SAND; outHs.push(h); if (h >= 40) outBandTall40++; if (h >= 80) outBandTall++; }
outHs.sort((a, b) => a - b);
const duneBesideCoast = { columns: outBand, p50: outHs.length ? outHs[outHs.length >> 1] : null, p95: outHs.length ? outHs[Math.floor(outHs.length * .95)] : null, max: outHs.length ? outHs[outHs.length - 1] : null, shareAtLeast40: outBand ? +(outBandTall40 / outBand).toFixed(3) : null, shareAtLeast80: outBand ? +(outBandTall / outBand).toFixed(3) : null };

// ---- identity with the saved prompt 05 run outside the footprint
let cmp = null;
if (opt.baseline) {
  const b = fs.readFileSync(opt.baseline); const base = new Int16Array(b.buffer.slice(b.byteOffset, b.byteOffset + b.length));
  let diff = 0, outsideCols = 0, first = null;
  for (let i = 0; i < S * S; i++) if (!inside[i]) { outsideCols++; if (top[i] !== base[i]) { diff++; if (!first) first = [x0 + i % S, z0 + ((i / S) | 0), top[i], base[i]]; } }
  cmp = { outsideColumns: outsideCols, differentColumns: diff, firstDifference_x_z_new_old: first };
}
if (opt.save) fs.writeFileSync(opt.save, Buffer.from(top.buffer));

// ---- layers (prompt 09): block shares per vertical band, thickness of the weathered sandstone below y 63 (inside the footprint and under the dunes outside it)
const layerOut = { mode: LAYERS ? 'on' : 'off' };
if (LAYERS) {
  const names = new Set(); for (const b of bandNames) for (const n of Object.keys(b)) names.add(n);
  layerOut.unexpectedBlocks = [...names].filter(n => !ALLOWED.has(n));
  layerOut.bandShares = bandNames.map((b, i) => { const tot = Object.values(b).reduce((a, v) => a + v, 0); const sh = {}; for (const [n, v] of Object.entries(b).sort((a, c) => c[1] - a[1])) sh[n.replace('minecraft:', '').replace('islandcraft_dunesofarrakis:', '')] = +(v / tot).toFixed(4); return { band: ['above 63', '8..63', '0..7 (gradient)', '-59..-1', '-64..-60'][i], blocks: tot, shares: sh }; });
  const pc = (arr, q) => arr.length ? arr[Math.min(arr.length - 1, Math.floor(q * arr.length))] : null;
  const wi = [], wo = []; for (let k = 0; k < S * S; k++) if (top[k] !== NO) (inside[k] ? wi : wo).push(wdCount[k]);
  wi.sort((a, b) => a - b); wo.sort((a, b) => a - b);
  layerOut.sandstoneThicknessBelow63 = { inside: { columns: wi.length, p5: pc(wi, .05), p50: pc(wi, .5), p95: pc(wi, .95) }, outside: { columns: wo.length, p5: pc(wo, .05), p50: pc(wo, .5), p95: pc(wo, .95) } };
}
const reliefMism = mism.length;
const r = {
  window: { x0, z0, size: S },
  generated: { columns: c.columns, fullChunks: c.fullChunks },
  footprint: { analyticInsideColumns: insideCols, fractionInside: +(insideCols / (S * S)).toFixed(4) },
  outline: { differentRockSandColumns: outlineMismatch, withinOneBlockOfCoast: outlineRing },
  relief: { checkedColumns: below + equalRock + equalNotRock + aboveRock + duneAbove, topBelowAnalytic: below, topAtAnalyticButNotSandstone: equalNotRock, sandstoneAboveAnalytic: aboveRock, topAtAnalyticSandstone: equalRock, duneSandAboveRock: duneAbove, largestDuneOverRock: maxDuneOver, mismatchColumns: reliefMism, mismatchFraction: +(reliefMism / (S * S)).toExponential(2), mismatchWithinOneBlockOfCoast: relMismatchRing, mismatchOnChunkBorder: relMismatchOnGrid },
  blocks: { airBelowSurface: c.airBelowSurface, bedrockAboveBottom5: c.bedrockAbove, floatingSandInRock: c.floatingSand, sandstoneAboveRockSurface: c.sandstoneAboveRock, wrongBlocks: c.wrongBlocks, columnsWithWrongBlock: wrongCols.size },
  rockSurfaceHeightAboveSand: rockHeights,
  terraces: { coastSectors: vsSectors, withFullStepVisible: vsWith, rockRockPairs: stepsRock, stepsOfExactly2: steps2 },
  banking, coastSectors64: { dunesBankOver: sectBank, rockStandsClear: sectClear },
  steps: { neighbourPairs: steps, over3: walls3, over3WithDune: wallsDune, over3RockOnly: wallsRock, largest: maxStep, largestRockOnly: maxStepRock, rockOnlyExamples_x_z_step: wallAt },
  slope: { baselineBlocks: 2 * B, rockColumns: slopeN, share_over_20deg: slopeN ? +(slope20 / slopeN).toFixed(4) : null, share_over_35deg: slopeN ? +(slope35 / slopeN).toFixed(4) : null, steepestGradient: +slopeMax.toFixed(2), within100mOfCoast: n100, share_over_25deg_within100m: n100 ? +(slope25 / n100).toFixed(4) : null },
  duneMountains: { footprintColumnsWithDuneMoreThan10AboveRock: duneOver10, footprintShare: insideCols ? +(duneOver10 / insideCols).toFixed(4) : null, outsideWithin30mOfCoast: duneBesideCoast },
  identityWithPrompt05Outside: cmp,
  layers: layerOut,
};
r.pass = {
  noGaps: c.columns === S * S,
  outlineIdentical: outlineMismatch === 0,
  reliefMismatchBelow0_1pct: reliefMism / (S * S) < 0.001,
  noAirBelowSurface: c.airBelowSurface === 0 && c.bedrockAbove === 0,
  noFloatingSand: c.floatingSand === 0 && c.sandstoneAboveRock === 0 && Object.keys(c.wrongBlocks).length === 0,
  noRockWalls: wallsRock === 0,
  noUnexpectedBlocks: !LAYERS || layerOut.unexpectedBlocks.length === 0,
  duneIdentical: cmp ? cmp.differentColumns === 0 : null,
};
fs.writeFileSync(`${outPrefix}.json`, JSON.stringify(r, null, 1));
console.log(`window x ${x0}..${x0 + S - 1} z ${z0}..${z0 + S - 1}: footprint ${(100 * insideCols / (S * S)).toFixed(1)}% (${insideCols} columns), full chunks ${c.fullChunks}`);
if (LAYERS) console.log(`layers: ${JSON.stringify(layerOut.sandstoneThicknessBelow63)} unexpected blocks ${JSON.stringify(layerOut.unexpectedBlocks)}`);
console.log(`outline (rock at y=${OUTLINE_Y} vs footprint): ${outlineMismatch} differing columns (${outlineRing} within 1 block of the coast)`);
console.log(`relief vs analytic: ${reliefMism} mismatching columns (${(100 * reliefMism / (S * S)).toExponential(2)}%): top below ${below}, top at analytic but not sandstone ${equalNotRock}, sandstone above ${aboveRock}; within 1 block of coast ${relMismatchRing}, on chunk borders ${relMismatchOnGrid}; dune over rock ${duneAbove} (largest ${maxDuneOver})`);
console.log(`blocks: air below surface ${c.airBelowSurface}, bedrock above bottom 5 ${c.bedrockAbove}, floating sand in rock ${c.floatingSand}, sandstone above rock ${c.sandstoneAboveRock}, wrong ${JSON.stringify(c.wrongBlocks)}`);
console.log(`rock surface above sand: ${JSON.stringify(rockHeights)}`);
console.log(`banking (share of columns with dune sand on top): coast 0-30 m ${banking.coast_0_30m.shareDuneSand}, 30-100 m ${banking.coast_30_100m.shareDuneSand}, inland ${banking.inland_over_100m.shareDuneSand}; 64 m coast sectors: dunes bank over rock ${sectBank}, rock clear ${sectClear}`);
console.log(`steps over 3 blocks: ${walls3} (with dune ${wallsDune}, rock only ${wallsRock}), largest ${maxStep}, largest rock-only ${maxStepRock}; slope > 20 deg ${r.slope.share_over_20deg}, > 25 deg within 100 m of coast ${r.slope.share_over_25deg_within100m}`);
console.log(`dune mountains: footprint columns with dune > 10 above rock ${duneOver10}; outside within 30 m of coast: ${JSON.stringify(duneBesideCoast)}`);
if (cmp) console.log(`identity with prompt 05 outside the footprint: ${cmp.outsideColumns} columns, different ${cmp.differentColumns} ${cmp.firstDifference_x_z_new_old ? JSON.stringify(cmp.firstDifference_x_z_new_old) : ''}`);
console.log('pass flags ' + JSON.stringify(r.pass));
