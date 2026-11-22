#!/usr/bin/env node
// Model numbers without a server: ocean share, landmass count and size, basin depth, from build/model/s<seed>.bin (written by Climate dump) and vanilla's own offset spline.
//   node tools/model/analyze.js <bias> [ocean.json]      (extract-vanilla.sh first; java Climate dump <seed> 20000 256 build/model/s<seed>.bin)
const fs = require('fs'), path = require('path');
const ROOT = path.resolve(__dirname, '..', '..');
const VD = path.join(ROOT, 'build/vanilla/data/minecraft/worldgen/density_function/overworld');
const find = n => { if (n && n.type === 'minecraft:spline') return n.spline; if (n && typeof n === 'object') for (const k in n) { const r = find(n[k]); if (r) return r; } };
const vanillaSpline = find(JSON.parse(fs.readFileSync(path.join(VD, 'offset.json'), 'utf8')));
function ev(s, inp) {   // vanilla cubic spline: Hermite between points, linear extrapolation with the end derivatives
  if (typeof s === 'number') return s;
  const x = inp[s.coordinate.split('/').pop()], P = s.points, val = p => ev(p.value, inp);
  if (x <= P[0].location) return val(P[0]) + P[0].derivative * (x - P[0].location);
  const L = P[P.length - 1]; if (x >= L.location) return val(L) + L.derivative * (x - L.location);
  let i = 0; while (x >= P[i + 1].location) i++;
  const a = P[i], b = P[i + 1], h = b.location - a.location, t = (x - a.location) / h, ya = val(a), yb = val(b), da = a.derivative * h, db = b.derivative * h;
  return (2 * t ** 3 - 3 * t ** 2 + 1) * ya + (t ** 3 - 2 * t ** 2 + t) * da + (-2 * t ** 3 + 3 * t ** 2) * yb + (t ** 3 - t ** 2) * db;
}
const foldedOf = w => -3 * (-1 / 3 + Math.abs(-2 / 3 + Math.abs(w)));
const surfaceY = (sp, c, e, w) => 128 * (-0.50375 + ev(sp, { continents: c, erosion: e, ridges: w, ridges_folded: foldedOf(w) })) + 128;   // depth = gradient(1.5 at -64, -1.5 at 320) + offset
module.exports = { ev, surfaceY, vanillaSpline, foldedOf, find };
if (require.main === module) {
  const oc = process.argv[3] ? JSON.parse(fs.readFileSync(process.argv[3], "utf8")) : null, bias = oc ? oc.ocean_bias : (+process.argv[2] || 0);
  const sp = oc ? JSON.parse(fs.readFileSync(path.join(ROOT, 'build/model/spline.json'), 'utf8')) : vanillaSpline;
  const N = 157, SEA = 63; let total = 0, ocean = 0; const lands = [], depths = [];
  for (const seed of [1, 2, 3]) {
    const b = fs.readFileSync(path.join(ROOT, `build/model/s${seed}.bin`)), d = new DataView(b.buffer, b.byteOffset, b.byteLength);
    const grid = new Uint8Array(N * N); let lo = 1e9, hi = -1e9;
    for (let i = 0; i < N * N; i++) {
      const c = d.getFloat64(i * 24) + bias, e = d.getFloat64(i * 24 + 8), w = d.getFloat64(i * 24 + 16), y = surfaceY(sp, c, e, w);
      grid[i] = y >= SEA ? 1 : 0; total++; if (!grid[i]) { ocean++; depths.push(SEA - y); }
    }
    // landmasses: 4-connected components of land cells at 256 blocks; "per 10,000 x 10,000" = per 39^2 cells
    const seen = new Uint8Array(N * N);
    for (let i = 0; i < N * N; i++) if (grid[i] && !seen[i]) {
      let n = 0, st = [i]; seen[i] = 1;
      while (st.length) { const k = st.pop(); n++; const x = k % N, z = (k / N) | 0; for (const [dx, dz] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) { const X = x + dx, Z = z + dz; if (X >= 0 && X < N && Z >= 0 && Z < N && grid[Z * N + X] && !seen[Z * N + X]) { seen[Z * N + X] = 1; st.push(Z * N + X); } } }
      lands.push(n);
    }
  }
  lands.sort((a, b) => b - a); depths.sort((a, b) => a - b);
  const km2 = n => (n * 256 * 256 / 1e6).toFixed(1);
  const big = lands.filter(n => n >= 4);   // ignore specks under 4 cells (512 x 512 blocks)
  console.log(`bias ${bias}: ocean share ${(100 * ocean / total).toFixed(1)}% (nominal ground below sea level ${SEA}, 3 seeds, 40000 x 40000 window, 256-block samples)`);
  console.log(`landmasses of >= 4 cells: ${(big.length / 3 / (157 * 157 / 39 / 39)).toFixed(1)} per 10,000 x 10,000 (all sizes: ${(lands.length / 3 / (157 * 157 / 39 / 39)).toFixed(1)}); largest ${km2(lands[0])} km2, 3 largest per seed avg ${km2(lands.slice(0, 3).reduce((a, b) => a + b, 0) / 3)} km2, median (>=4 cells) ${km2(big[big.length >> 1] || 0)} km2`);
  console.log(`ocean depth below sea level (blocks): p10 ${depths[depths.length * .1 | 0].toFixed(0)} p50 ${depths[depths.length >> 1].toFixed(0)} p90 ${depths[depths.length * .9 | 0].toFixed(0)} max ${depths[depths.length - 1].toFixed(0)}`);
}
