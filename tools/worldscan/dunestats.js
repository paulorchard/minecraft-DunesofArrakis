#!/usr/bin/env node
// Dune statistics of an open-sand window from the saved WORLD_SURFACE heightmaps (prompt 12, part C).
//   node dunestats.js <regionDir> <x0> <z0> <size> [--save heights.i16]
//   node dunestats.js --compare <heightsA.i16> <heightsB.i16> <x0> <z0> <size>     crest displacement and flat share between two scales
// Height above the sand level (y 63) of the top block, per column (only columns of full chunks): p50, p95, max, the share above 20 m, the share within 1 of 63.
// Dune wavelength (median distance between crests along the wind direction, from transects) and wavelength-to-height ratio of the crests (prominence >= 4).
const fs = require('fs'), path = require('path');
const { chunks, unpack } = require('./nbt.js');
const SAND = 63, NO = -32768;
const args = process.argv.slice(2);
function stats(h, S, label) {
  const v = []; for (let i = 0; i < h.length; i++) if (h[i] !== NO) v.push(h[i] - SAND);
  v.sort((a, b) => a - b);
  const q = p => v[Math.min(v.length - 1, Math.floor(v.length * p))];
  const above20 = v.filter(x => x > 20).length / v.length, flat = v.filter(x => Math.abs(x) <= 1).length / v.length;
  // crests along x for 64 rows (the wind blows toward 115 degrees, so x-rows cross the ridges at about 65 degrees)
  const crests = [], ws = [], hs = [];
  for (let r = 0; r < 64; r++) {
    const z = Math.floor((r + 0.5) * S / 64), row = [];
    for (let x = 0; x < S; x++) row.push(h[z * S + x]);
    let last = null;
    for (let x = 12; x < S - 12; x++) {
      if (row[x] === NO) continue;
      let mx = true; for (let d = -12; d <= 12; d++) if (row[x + d] > row[x]) { mx = false; break; }
      if (!mx || !(row[x - 1] < row[x])) continue;
      let lo = 1e9; for (let d = -90; d <= 90; d++) if (x + d >= 0 && x + d < S) lo = Math.min(lo, row[x + d]);
      if (row[x] - lo < 4) continue;
      crests.push([x, z, row[x] - SAND]); hs.push(row[x] - lo);
      if (last !== null) ws.push(x - last); last = x;
    }
  }
  ws.sort((a, b) => a - b); hs.sort((a, b) => a - b);
  const wl = ws.length ? ws[Math.floor(ws.length / 2)] : NaN, hh = hs.length ? hs[Math.floor(hs.length / 2)] : NaN;
  console.log(`${label}: columns ${v.length}; dune height above y 63: p50 ${q(.5)} p95 ${q(.95)} max ${v[v.length - 1]}; share above 20 m ${(100 * above20).toFixed(1)}%; share within +-1 of y 63 ${(100 * flat).toFixed(1)}%; crests ${crests.length}, spacing along x median ${wl} m (along the wind about ${(wl * Math.sin(Math.PI * 65 / 180)).toFixed(0)} m), crest prominence median ${hh}, wavelength / height ${(wl * Math.sin(Math.PI * 65 / 180) / hh).toFixed(1)}`);
  return crests;
}
function rd(f) { const b = fs.readFileSync(f); return new Int16Array(b.buffer.slice(b.byteOffset, b.byteOffset + b.length)); }
if (args[0] === '--compare') {
  const [, fa, fb, , , SIZE] = args; const S = +SIZE;
  const A = rd(fa), B = rd(fb);
  const ca = stats(A, S, 'A'), cb = stats(B, S, 'B');
  // displacement of the crests of A (prominent ones) to the nearest crest of B on the same row
  const byRow = new Map(); for (const c of cb) { if (!byRow.has(c[1])) byRow.set(c[1], []); byRow.get(c[1]).push(c[0]); }
  const disp = []; for (const c of ca) { if (c[2] < 12) continue; const l = byRow.get(c[1]) || []; let b = 1e9; for (const x of l) b = Math.min(b, Math.abs(x - c[0])); if (b < 1e9) disp.push(b); }
  disp.sort((a, b) => a - b);
  console.log(`crest displacement A to B (crests of A at least 12 high, nearest crest of B on the row): n ${disp.length}, median ${disp[Math.floor(disp.length / 2)]}, p90 ${disp[Math.floor(disp.length * .9)]}, max ${disp[disp.length - 1]}, share within 4 blocks ${(100 * disp.filter(x => x <= 4).length / disp.length).toFixed(1)}%`);
  process.exit(0);
}
const pos = [], opt = {};
for (let i = 0; i < args.length; i++) { if (args[i].startsWith('--')) { opt[args[i].slice(2)] = args[i + 1]; i++; } else pos.push(args[i]); }
const [dir, X0, Z0, SIZE] = pos; const x0 = +X0, z0 = +Z0, S = +SIZE;
const h = new Int16Array(S * S).fill(NO);
for (const f of fs.readdirSync(dir).filter(f => f.endsWith('.mca'))) for (const ch of chunks(path.join(dir, f))) {
  const bx = ch.xPos * 16, bz = ch.zPos * 16;
  if (bx + 15 < x0 || bx >= x0 + S || bz + 15 < z0 || bz >= z0 + S || ch.Status !== 'minecraft:full') continue;
  const hm = ch.Heightmaps && ch.Heightmaps.WORLD_SURFACE; if (!hm) continue;
  const v = unpack(hm, 9, 256), minY = ch.yPos * 16;
  for (let i = 0; i < 256; i++) {
    const wx = bx + (i & 15) - x0, wz = bz + (i >> 4) - z0; if (wx < 0 || wz < 0 || wx >= S || wz >= S) continue;
    h[wz * S + wx] = minY + v[i] - 1;
  }
}
stats(h, S, path.basename(path.dirname(path.dirname(path.dirname(dir)))));
if (opt.save) fs.writeFileSync(opt.save, Buffer.from(h.buffer));
