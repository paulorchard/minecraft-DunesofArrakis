#!/usr/bin/env node
// Columns that differ between two saved height maps (same window, two runs) and lie outside every listed feature footprint (a `spire.sh named ... any` list).
//   node outside.js <heightsA.i16> <heightsB.i16> <features.tsv> <x0> <z0> <size>
const fs = require('fs');
const [fa, fb, tsv, X0, Z0, SIZE] = process.argv.slice(2); const x0 = +X0, z0 = +Z0, S = +SIZE;
const rd = f => { const b = fs.readFileSync(f); return new Int16Array(b.buffer.slice(b.byteOffset, b.byteOffset + b.length)); };
const A = rd(fa), B = rd(fb);
const feats = fs.readFileSync(tsv, 'utf8').split('\n').filter(l => l.trim()).map(l => { const t = l.split('\t'); return { x: +t[2], z: +t[3], r: +t[4] }; });
const cell = 256, grid = new Map();
for (const f of feats) for (let i = Math.floor((f.x - f.r) / cell); i <= Math.floor((f.x + f.r) / cell); i++) for (let j = Math.floor((f.z - f.r) / cell); j <= Math.floor((f.z + f.r) / cell); j++) { const k = i + ',' + j; if (!grid.has(k)) grid.set(k, []); grid.get(k).push(f); }
let diff = 0, outside = 0, first = null;
for (let k = 0; k < S * S; k++) if (A[k] !== B[k]) {
  diff++; const x = x0 + k % S, z = z0 + ((k / S) | 0); const l = grid.get(Math.floor(x / cell) + ',' + Math.floor(z / cell)) || [];
  if (!l.some(f => Math.hypot(x - f.x, z - f.z) <= f.r + 1)) { outside++; if (!first) first = [x, z]; }
}
console.log(`differing columns ${diff}, outside every feature footprint ${outside}${first ? ', first at ' + first : ''}`);
