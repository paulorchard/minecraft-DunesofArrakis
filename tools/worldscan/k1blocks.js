#!/usr/bin/env node
// Kind 1 materials check (prompt 11): for the spires and wedges of `spire.sh named` (feature columns), read the generated blocks.
//   node k1blocks.js <regionDir> <named.tsv> <x0> <z0> <size>
// For every column of a feature's footprint the blocks from the top down to the first sandstone-family block are the feature's rock: they must be stone or
// andesite only. Reports the block counts, the andesite share per spire, the typical horizontal width of an andesite streak (runs of andesite along x and z on one y level),
// and prints the full top-to-bottom block list of the tallest spire's highest column (run-length coded) and of one wedge column on its back ramp.
const fs = require('fs'), path = require('path');
const { chunks, unpack } = require('./nbt.js');
const [dir, tsv, X0, Z0, SIZE] = process.argv.slice(2); const x0 = +X0, z0 = +Z0, S = +SIZE;
const feats = fs.readFileSync(tsv, 'utf8').split('\n').filter(l => l.trim()).map(l => { const [label, type, cx, cz, reach, h] = l.split('\t'); return { label, type, cx: +cx, cz: +cz, R: +reach, h: +h }; });
const BASE = new Set(['minecraft:sandstone', 'minecraft:smooth_sandstone', 'minecraft:cut_sandstone', 'minecraft:red_sandstone']);
const cols = new Map(); // "x,z" -> array of block names from the top of the world down (only the rock part)
const want = new Set();
for (const f of feats) for (let z = Math.floor(f.cz - f.R); z <= Math.ceil(f.cz + f.R); z++) for (let x = Math.floor(f.cx - f.R); x <= Math.ceil(f.cx + f.R); x++) if (Math.hypot(x - f.cx, z - f.cz) <= f.R) want.add(x + ',' + z);
for (const fl of fs.readdirSync(dir).filter(f => f.endsWith('.mca'))) for (const ch of chunks(path.join(dir, fl))) {
  const bx = ch.xPos * 16, bz = ch.zPos * 16; if (bx + 15 < x0 || bx >= x0 + S || bz + 15 < z0 || bz >= z0 + S || ch.Status !== 'minecraft:full') continue;
  const secs = ch.sections.filter(s => s.block_states).map(s => { const pal = s.block_states.palette.map(p => p.Name); return { y: s.Y, pal, idx: pal.length > 1 ? unpack(s.block_states.data, Math.max(4, Math.ceil(Math.log2(pal.length))), 4096) : null }; }).sort((a, b) => b.y - a.y);
  for (let i = 0; i < 256; i++) {
    const x = bx + (i & 15), z = bz + (i >> 4); if (!want.has(x + ',' + z)) continue;
    const list = []; let started = false, done = false, y0 = 0;
    for (const s of secs) { if (done) break; for (let ly = 15; ly >= 0; ly--) { const n = s.idx ? s.pal[s.idx[(ly << 8) | i]] : s.pal[0]; if (n === 'minecraft:air') continue; if (n === 'islandcraft_dunesofarrakis:dune_sand') { if (!started) { list.length = 0; } started = true; list.push([s.y * 16 + ly, n]); continue; } started = true; if (BASE.has(n)) { done = true; break; } list.push([s.y * 16 + ly, n]); } }
    cols.set(x + ',' + z, list);
  }
}
const total = {}; let spires = 0; const shares = [], widths = [];
for (const f of feats) {
  if (f.type !== 'spire') continue; spires++;
  let n = 0, a = 0; const andAt = new Map(); // y -> set of "x,z" of andesite
  for (const [k, list] of cols) { const [x, z] = k.split(',').map(Number); if (Math.hypot(x - f.cx, z - f.cz) > f.R) continue; for (const [y, name] of list) { if (name === 'islandcraft_dunesofarrakis:dune_sand') continue; total[name] = (total[name] || 0) + 1; n++; if (name === 'minecraft:andesite') { a++; if (!andAt.has(y)) andAt.set(y, new Set()); andAt.get(y).add(k); } } }
  if (n) shares.push(a / n);
  for (const [y, set] of andAt) for (const k of set) { const [x, z] = k.split(',').map(Number); if (!set.has((x - 1) + ',' + z)) { let r = 1; while (set.has((x + r) + ',' + z)) r++; widths.push(r); } if (!set.has(x + ',' + (z - 1))) { let r = 1; while (set.has(x + ',' + (z + r))) r++; widths.push(r); } }
}
const q = (v, p) => { if (!v.length) return NaN; const s = [...v].sort((a, b) => a - b); return s[Math.min(s.length - 1, Math.floor(p * s.length))]; };
console.log('blocks of the feature rock (spires; above the sandstone base, dune sand excluded):', JSON.stringify(total));
console.log(`andesite share per spire: p10 ${q(shares, .1).toFixed(2)} p50 ${q(shares, .5).toFixed(2)} p90 ${q(shares, .9).toFixed(2)} (${spires} spires); streak width along a row (blocks): p25 ${q(widths, .25)} p50 ${q(widths, .5)} p75 ${q(widths, .75)} p95 ${q(widths, .95)}`);
const other = Object.keys(total).filter(n => n !== 'minecraft:stone' && n !== 'minecraft:andesite'); console.log('blocks other than stone and andesite in the feature rock:', other.length ? other.map(n => n + ' ' + total[n]).join(', ') : 'none');
const rle = list => { const out = []; for (const [y, n] of list) { const s = n.replace('minecraft:', ''); if (out.length && out[out.length - 1][0] === s) out[out.length - 1][1]++; else out.push([s, 1]); } return out.map(([s, c]) => `${s} x${c}`).join(', '); };
const tall = feats.find(f => f.type === 'spire');
if (tall) { let best = null; for (const [k, list] of cols) { const [x, z] = k.split(',').map(Number); if (Math.hypot(x - tall.cx, z - tall.cz) > 3) continue; if (!best || list.length > best[1].length) best = [k, list]; } if (best) console.log(`full column of ${tall.label} at (${best[0]}), top y ${best[1][0][0]}, ${best[1].length} blocks of feature rock, top to bottom: ${rle(best[1])}`); }
const w = feats.find(f => f.type === 'wedge'); if (w) { let best = null; for (const [k, list] of cols) { const [x, z] = k.split(',').map(Number); if (Math.hypot(x - w.cx, z - w.cz) > w.R * 0.6) continue; if (!best || list.length > best[1].length) best = [k, list]; } if (best) console.log(`tallest ${w.label} column near its centre (${best[0]}), top y ${best[1][0][0]}, ${best[1].length} blocks, top to bottom: ${rle(best[1])}`); }
