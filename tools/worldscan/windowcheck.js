#!/usr/bin/env node
// Window checker: compares generated chunks of a window with the analytic continent field.
//   node windowcheck.js <regionDir> <mask.bin> <x0> <z0> <size> <outPrefix> [--rock-top 103] [--save heights.i16] [--baseline heights.i16] [--line x1,z1,x2,z2]
// mask.bin: one byte per column (rows = z, columns = x), continent 1..5 inside, 0 outside (tools/worldscan/field.sh mask ...).
// Reports, per column: generated surface (rock = minecraft:sandstone, dune = islandcraft_dunesofarrakis:dune_sand) vs the field.
const fs = require('fs'), path = require('path');
const { chunks, unpack } = require('./nbt.js');
const args = process.argv.slice(2), pos = [];
const opt = {};
for (let i = 0; i < args.length; i++) { if (args[i].startsWith('--')) { opt[args[i].slice(2)] = args[i + 1]; i++; } else pos.push(args[i]); }
const [dir, maskFile, X0, Z0, SIZE, outPrefix] = pos;
const x0 = +X0, z0 = +Z0, S = +SIZE, ROCK_TOP = +(opt['rock-top'] || 103), SAND = 63;
if (!dir || !maskFile || !outPrefix || !S) { console.error('usage: node windowcheck.js <regionDir> <mask.bin> <x0> <z0> <size> <outPrefix> [--rock-top 103] [--save f] [--baseline f] [--line x1,z1,x2,z2]'); process.exit(1); }
const ROCK = 'minecraft:sandstone', DUNE = 'islandcraft_dunesofarrakis:dune_sand';
const mask = fs.readFileSync(maskFile); if (mask.length !== S * S) { console.error('mask size mismatch'); process.exit(1); }

const NO = -32768;
const top = new Int16Array(S * S).fill(NO);
const topKind = new Uint8Array(S * S); // 0 none, 1 dune sand, 2 sandstone, 3 other
const c = { fullChunks: 0, columns: 0, airBelowSurface: 0, bedrockAbove: 0, wrongBlocks: {}, rockColumnsWithOther: 0 };
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
      const m = mask[wz * S + wx];
      if (n === 'minecraft:air') { if (y < colTop[i]) c.airBelowSurface++; return; }
      if (n === 'minecraft:bedrock') { if (y > minY + 4) c.bedrockAbove++; return; }
      const expect = m ? ROCK : DUNE;
      if (n !== expect) { const key = n + (m ? ' in rock column' : ' in dune column'); c.wrongBlocks[key] = (c.wrongBlocks[key] || 0) + 1; wrongCols.add(wz * S + wx); }
    };
    for (const s of secs) {
      const base = s.y * 16;
      if (!s.idx) { for (let i = 0; i < 256; i++) for (let ly = 0; ly < 16; ly++) check(s.pal[0], base + ly, i); }
      else for (let k = 0; k < 4096; k++) check(s.pal[s.idx[k]], base + (k >> 8), k & 255);
    }
    for (let i = 0; i < 256; i++) {
      const wx = bx + (i & 15) - x0, wz = bz + (i >> 4) - z0; if (wx < 0 || wz < 0 || wx >= S || wz >= S) continue;
      const k = wz * S + wx; top[k] = colTop[i];
      topKind[k] = colName[i] === DUNE ? 1 : colName[i] === ROCK ? 2 : colName[i] ? 3 : 0;
      c.columns++;
    }
  }
}
if (c.columns !== S * S) console.error(`WARNING: only ${c.columns} of ${S * S} columns were generated (full chunks: ${c.fullChunks})`);

// ---- analytic vs generated
const inside = new Uint8Array(S * S); for (let i = 0; i < S * S; i++) inside[i] = mask[i] ? 1 : 0;
const edge = []; // columns with a 4-neighbour of the other state = the true coast (both sides)
for (let z = 0; z < S; z++) for (let x = 0; x < S; x++) {
  const v = inside[z * S + x];
  if ((x > 0 && inside[z * S + x - 1] !== v) || (x < S - 1 && inside[z * S + x + 1] !== v) || (z > 0 && inside[(z - 1) * S + x] !== v) || (z < S - 1 && inside[(z + 1) * S + x] !== v)) edge.push(x, z);
}
let insideCols = 0, rockTopCols = 0; for (let i = 0; i < S * S; i++) { insideCols += inside[i]; if (topKind[i] === 2) rockTopCols++; }
const mism = []; for (let i = 0; i < S * S; i++) if ((inside[i] === 1) !== (topKind[i] === 2)) mism.push(i);
let maxDist = 0, inRing = 0, onGrid = 0;
const stride = Math.max(1, Math.ceil(mism.length / 20000));
for (let m = 0; m < mism.length; m += stride) {
  const i = mism[m], x = i % S, z = (i / S) | 0; let best = 1e18;
  for (let e = 0; e < edge.length; e += 2) { const dx = edge[e] - x, dz = edge[e + 1] - z, d = dx * dx + dz * dz; if (d < best) best = d; }
  const d = edge.length ? Math.sqrt(best) : Infinity; if (d > maxDist) maxDist = d; if (d <= 1.0001) inRing++;
  const wx = (x0 + x) & 15, wz = (z0 + z) & 15; if (wx === 0 || wx === 15 || wz === 0 || wz === 15) onGrid++;
}
// rock top flatness
let rockTopWrong = 0, rockMin = 1e9, rockMax = -1e9;
for (let i = 0; i < S * S; i++) if (inside[i] && topKind[i] === 2) { if (top[i] !== ROCK_TOP) rockTopWrong++; rockMin = Math.min(rockMin, top[i]); rockMax = Math.max(rockMax, top[i]); }
// rock outside the footprint (columns with sandstone top where mask says outside) and distance to the footprint
let rockOutside = 0; for (let i = 0; i < S * S; i++) if (!inside[i] && topKind[i] === 2) rockOutside++;
// outside columns more than 8 blocks (Chebyshev) from the footprint, via an integral image
const ii = new Int32Array((S + 1) * (S + 1));
for (let z = 0; z < S; z++) { let row = 0; for (let x = 0; x < S; x++) { row += inside[z * S + x]; ii[(z + 1) * (S + 1) + x + 1] = ii[z * (S + 1) + x + 1] + row; } }
const boxCount = (x, z, r) => { const xa = Math.max(0, x - r), xb = Math.min(S, x + r + 1), za = Math.max(0, z - r), zb = Math.min(S, z + r + 1); return ii[zb * (S + 1) + xb] - ii[za * (S + 1) + xb] - ii[zb * (S + 1) + xa] + ii[za * (S + 1) + xa]; };
const far8 = new Uint8Array(S * S); let far8Count = 0;
for (let z = 0; z < S; z++) for (let x = 0; x < S; x++) if (!inside[z * S + x] && boxCount(x, z, 8) === 0) { far8[z * S + x] = 1; far8Count++; }
// dune statistics outside the rock (columns > 8 blocks away): same quantities as prompt 03
const hs = []; let atSand = 0; for (let i = 0; i < S * S; i++) if (far8[i] && top[i] !== NO) { const h = top[i] - SAND; hs.push(h); if (h === 0) atSand++; }
hs.sort((a, b) => a - b); const pct = p => hs.length ? hs[Math.min(hs.length - 1, Math.floor(p * hs.length))] : null;
const duneStats = { columns: hs.length, min: hs[0] ?? null, p5: pct(.05), p50: pct(.5), p95: pct(.95), max: hs[hs.length - 1] ?? null, fractionAtSandLevel: hs.length ? +(atSand / hs.length).toFixed(4) : null };

// ---- identity with the saved dune run
let base = null, cmp = null;
if (opt.baseline) {
  const b = fs.readFileSync(opt.baseline); base = new Int16Array(b.buffer, b.byteOffset, S * S);
  let diffAllOutside = 0, diffFar8 = 0, outsideCols = 0, firstDiff = null;
  for (let i = 0; i < S * S; i++) if (!inside[i]) { outsideCols++; if (top[i] !== base[i]) { diffAllOutside++; if (!firstDiff) firstDiff = [x0 + i % S, z0 + ((i / S) | 0), top[i], base[i]]; if (far8[i]) diffFar8++; } }
  cmp = { outsideColumns: outsideCols, differentOutsideAnywhere: diffAllOutside, differentMoreThan8Outside: diffFar8, firstDifference_x_z_new_old: firstDiff };
}
if (opt.save) fs.writeFileSync(opt.save, Buffer.from(top.buffer));

// ---- strip along a line (gap check): every column between two points is dune sand
let line = null;
if (opt.line) {
  const [ax, az, bx2, bz2] = opt.line.split(',').map(Number); const n = Math.ceil(Math.hypot(bx2 - ax, bz2 - az)); let seen = 0, notDune = 0;
  for (let s = 0; s <= n; s++) { const x = Math.round(ax + (bx2 - ax) * s / n) - x0, z = Math.round(az + (bz2 - az) * s / n) - z0; if (x < 0 || z < 0 || x >= S || z >= S) continue; seen++; if (topKind[z * S + x] !== 1 && !(s < 3 || s > n - 3)) notDune++; }
  line = { samples: seen, notDuneSandTop: notDune };
}

const r = {
  window: { x0, z0, size: S, rockTopY: ROCK_TOP },
  generated: { columns: c.columns, fullChunks: c.fullChunks },
  footprint: { analyticInsideColumns: insideCols, generatedRockTopColumns: rockTopCols, fractionInside: +(insideCols / (S * S)).toFixed(4) },
  mismatch: { columns: mism.length, fractionOfColumns: +(mism.length / (S * S)).toExponential(2), withinOneBlockOfCoast: inRing, sampledEvery: stride, largestDistanceToCoast: +maxDist.toFixed(2), onChunkBorderColumns: onGrid, coastEdgeColumns: edge.length / 2 },
  rock: { columns: rockTopCols - rockOutside, topY_min: rockMin === 1e9 ? null : rockMin, topY_max: rockMax === -1e9 ? null : rockMax, columnsWithTopNotRockTop: rockTopWrong, rockTopColumnsOutsideFootprint: rockOutside },
  blocks: { airBelowSurface: c.airBelowSurface, bedrockAboveBottom5: c.bedrockAbove, wrongBlocks: c.wrongBlocks, columnsWithWrongBlock: wrongCols.size },
  duneStatsOutsideRock8: duneStats, identityWithSavedDuneRun: cmp, line,
};
const pass = {
  noGaps: c.columns === S * S,
  mismatchBelow0_1pct: mism.length / (S * S) < 0.001,
  mismatchOnlyInOneBlockRing: mism.length === 0 || (inRing === Math.ceil(mism.length / stride) && maxDist <= 1.0001),
  rockTopFlat: rockTopWrong === 0,
  allRockIsSandstone: Object.keys(c.wrongBlocks).length === 0 && c.airBelowSurface === 0 && c.bedrockAbove === 0,
  noRockOutsideFootprint: rockOutside === 0,
  duneIdentical: cmp ? cmp.differentOutsideAnywhere === 0 : null,
};
r.pass = pass;
fs.writeFileSync(`${outPrefix}.json`, JSON.stringify(r, null, 1));
console.log(`window x ${x0}..${x0 + S - 1} z ${z0}..${z0 + S - 1}: columns ${c.columns}, footprint ${(100 * insideCols / (S * S)).toFixed(1)}% (${insideCols} columns), generated rock tops ${rockTopCols}`);
console.log(`mismatch ${mism.length} columns (${(100 * mism.length / (S * S)).toFixed(4)}%), within 1 block of coast ${inRing}, largest distance to coast ${maxDist.toFixed(1)} blocks, on chunk-border columns ${onGrid}`);
console.log(`rock top y ${r.rock.topY_min}..${r.rock.topY_max} (want ${ROCK_TOP}), rock tops not at ${ROCK_TOP}: ${rockTopWrong}; rock tops outside footprint: ${rockOutside}`);
console.log(`air below surface ${c.airBelowSurface}  bedrock above bottom 5 ${c.bedrockAbove}  wrong blocks ${JSON.stringify(c.wrongBlocks)}`);
console.log(`dune stats (outside rock, >8 blocks): ${JSON.stringify(duneStats)}`);
if (cmp) console.log(`identity with saved dune run: outside columns ${cmp.outsideColumns}, different anywhere ${cmp.differentOutsideAnywhere}, different >8 outside ${cmp.differentMoreThan8Outside} ${cmp.firstDifference_x_z_new_old ? JSON.stringify(cmp.firstDifference_x_z_new_old) : ''}`);
if (line) console.log(`strip line: ${line.samples} samples, not dune sand: ${line.notDuneSandTop}`);
console.log('pass flags ' + JSON.stringify(pass));
