#!/usr/bin/env node
// Pick a slice line through the foot of a tough-rock cliff (prompt 12): node facepick.js <rockmap.i16> <maps_prefix> <x0> <z0> <size> [minHeight 25] [maxHeight 60]
// Prints "x1 z1 x2 z2": a 130 m line that starts on bare base rock 50 m before the foot, crosses the foot and climbs the face; and the first base-coast line.
const fs = require('fs');
const [rockF, prefix, X0, Z0, SIZE, MINH, MAXH] = process.argv.slice(2); const x0 = +X0, z0 = +Z0, S = +SIZE, minH = +(MINH || 25), maxH = +(MAXH || 60), NO = -32768;
const rd = f => { const b = fs.readFileSync(f); return new Int16Array(b.buffer.slice(b.byteOffset, b.byteOffset + b.length)); };
const rock = rd(rockF), base = rd(prefix + '_base.i16');
const at = (x, z) => (x < 0 || z < 0 || x >= S || z >= S ? NO : rock[z * S + x]);
const th = (x, z) => { const r = at(x, z), b = (x < 0 || z < 0 || x >= S || z >= S) ? NO : base[z * S + x]; return r === NO || b === NO ? -1 : r - b; };
let best = null;
for (let z = 80; z < S - 80; z += 3) for (let x = 80; x < S - 80; x += 3) {
  const h = th(x, z); if (h < minH || h > maxH) continue;
  for (let a = 0; a < 16; a++) {
    const dx = Math.cos(a * Math.PI / 8), dz = Math.sin(a * Math.PI / 8);
    // walk away from the face top: the foot is where the height first drops to 0 within 60 m
    let foot = -1; for (let d = 1; d <= 60; d++) if (th(Math.round(x + dx * d), Math.round(z + dz * d)) === 0) { foot = d; break; }
    if (foot < 0 || foot < 6) continue;
    // 50 m beyond the foot on bare base rock
    let ok = true; for (let d = foot; d <= foot + 50; d += 2) if (th(Math.round(x + dx * d), Math.round(z + dz * d)) !== 0) { ok = false; break; }
    if (!ok) continue;
    const L = foot + 50, sx = x0 + x - dx * 0, sz = z0 + z - dz * 0;
    const x1 = Math.round(x0 + x + dx * (foot + 50)), z1 = Math.round(z0 + z + dz * (foot + 50)), x2 = Math.round(x0 + x - dx * 20), z2 = Math.round(z0 + z - dz * 20);
    if (!best || Math.abs(h - 40) < Math.abs(best.h - 40)) best = { h, line: [x1, z1, x2, z2], foot, top: [x0 + x, z0 + z] };
    break;
  }
}
if (best) console.log(`${best.line.join(' ')}   # cliff ${best.h} m high at (${best.top}), foot ${best.foot} m away`); else console.log('none');
