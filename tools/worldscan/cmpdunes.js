#!/usr/bin/env node
// cmpdunes <scanA> <scanB>: counts columns whose top height differs between two scans of the same window.
const fs = require('fs');
const [a, b] = process.argv.slice(2);
const L = p => { const h = JSON.parse(fs.readFileSync(p + '.json')), n = h.width * h.height, bin = fs.readFileSync(p + '.bin'); return { h, n, top: new Int16Array(bin.buffer.slice(bin.byteOffset, bin.byteOffset + n * 2)) }; };
const A = L(a), B = L(b);
if (A.n !== B.n || A.h.x0 !== B.h.x0 || A.h.z0 !== B.h.z0) throw new Error('windows differ');
let diff = 0, max = 0, lo = 1e9, hi = -1e9;
for (let i = 0; i < A.n; i++) { const d = Math.abs(A.top[i] - B.top[i]); if (d) { diff++; if (d > max) max = d; } if (B.top[i] < lo) lo = B.top[i]; if (B.top[i] > hi) hi = B.top[i]; }
console.log(JSON.stringify({ columns: A.n, differing_columns: diff, max_height_difference: max, new_min_y: lo, new_max_y: hi }));
