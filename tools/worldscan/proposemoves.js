#!/usr/bin/env node
// proposemoves: for each circle, the smallest centre shift (dx, dz) after which an estimated rock share reaches the target, from a dune-only scan.
//   node tools/worldscan/proposemoves.js <duneOnlyScanPrefix> [targetPct 50] [maxShift 120]
// Estimate: rock top = 63 + taper * (min_rise + max_rise)/2 (taper as in grid.json), rock column where rock top > dune + 0.5.
const fs = require('fs'), path = require('path');
const [duneP, TARGET = 50, MAXS = 120] = process.argv.slice(2);
const grid = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'rewrite', 'grid.json'), 'utf8')), OC = grid.outcrop;
const h = JSON.parse(fs.readFileSync(duneP + '.json')), n = h.width * h.height, b = fs.readFileSync(duneP + '.bin'), top = new Int16Array(b.buffer.slice(b.byteOffset, b.byteOffset + n * 2));
const R = grid.diameter_x / 2, smooth = s => s * s * (3 - 2 * s);
const taper = r => { const c = OC.core_fraction; return r <= c ? 1 : r >= 1 ? 0 : smooth(1 - (r - c) / (1 - c)); };
const share = (cx, cz, rise) => { let t = 0, k = 0; for (let z = cz - R; z <= cz + R; z++) for (let x = cx - R; x <= cx + R; x++) { const r = Math.hypot(x - cx, z - cz) / R; if (r >= 1) continue; t++; if (63 + taper(r) * rise > top[(z - h.z0) * h.width + x - h.x0] + 0.5) k++; } return 100 * k / t; };
for (const p of grid.islands) {
  const cx = grid.origin_x + grid.pitch * p.col, cz = grid.origin_z + grid.pitch * p.row, rise = (OC.min_rise + p.max_rise) / 2;
  const base = share(cx, cz, rise); let best = null, bestAxis = null;
  for (let dx = -MAXS; dx <= MAXS; dx += 5) for (let dz = -MAXS; dz <= MAXS; dz += 5) {
    const d = Math.hypot(dx, dz); if (best && d >= best.d) continue; const s = share(cx + dx, cz + dz, rise);
    if (s >= TARGET) { best = { dx, dz, d: +d.toFixed(0), share: +s.toFixed(0) }; if (dx === 0 || dz === 0) bestAxis = best; }
  }
  let ax = null; for (const [dx, dz] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) for (let m = 5; m <= MAXS; m += 5) { const s = share(cx + dx * m, cz + dz * m, rise); if (s >= TARGET) { if (!ax || m < ax.d) ax = { dx: dx * m, dz: dz * m, d: m, share: +s.toFixed(0) }; break; } }
  console.log(p.biome.padEnd(26), 'estimated rock share now', base.toFixed(0) + '%', ' smallest move:', best ? JSON.stringify(best) : 'none within ' + MAXS, ' single-axis:', ax ? JSON.stringify(ax) : 'none');
}
