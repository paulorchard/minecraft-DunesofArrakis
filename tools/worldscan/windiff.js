#!/usr/bin/env node
// Block-level difference between two generated worlds over a window (same seed twice, or the same window generated from two directions).
//   node windiff.js <regionDirA> <regionDirB> <x0> <z0> <size>
// Prints the number of differing blocks and of columns with any differing block, over the chunks present as full chunks in both.
const fs = require('fs'), path = require('path');
const { chunks, unpack } = require('./nbt.js');
const [dirA, dirB, X0, Z0, SIZE] = process.argv.slice(2); const x0 = +X0, z0 = +Z0, S = +SIZE;
function load(dir) {
  const m = new Map();
  for (const f of fs.readdirSync(dir).filter(f => f.endsWith('.mca'))) for (const ch of chunks(path.join(dir, f))) {
    const bx = ch.xPos * 16, bz = ch.zPos * 16; if (bx + 15 < x0 || bx >= x0 + S || bz + 15 < z0 || bz >= z0 + S || ch.Status !== 'minecraft:full') continue;
    const secs = new Map();
    for (const s of ch.sections) { const bs = s.block_states; if (!bs) continue; const pal = bs.palette.map(p => p.Name + (p.Properties ? JSON.stringify(p.Properties) : '')); secs.set(s.Y, { pal, idx: pal.length > 1 ? unpack(bs.data, Math.max(4, Math.ceil(Math.log2(pal.length))), 4096) : null }); }
    m.set(ch.xPos + ',' + ch.zPos, secs);
  }
  return m;
}
const A = load(dirA), B = load(dirB); let blocks = 0, cols = 0, chunksCmp = 0, firstAt = null;
for (const [k, sa] of A) {
  const sb = B.get(k); if (!sb) continue; chunksCmp++;
  const [cx, cz] = k.split(',').map(Number); const colDiff = new Uint8Array(256);
  const ys = new Set([...sa.keys(), ...sb.keys()]);
  for (const y of ys) {
    const a = sa.get(y), b = sb.get(y);
    for (let i = 0; i < 4096; i++) {
      const na = a ? (a.idx ? a.pal[a.idx[i]] : a.pal[0]) : 'minecraft:air', nb = b ? (b.idx ? b.pal[b.idx[i]] : b.pal[0]) : 'minecraft:air';
      if (na !== nb) { blocks++; colDiff[i & 255] = 1; if (!firstAt) firstAt = [cx * 16 + (i & 15), y * 16 + (i >> 8), cz * 16 + ((i >> 4) & 15)]; }
    }
  }
  for (let i = 0; i < 256; i++) cols += colDiff[i];
}
console.log(`chunks compared ${chunksCmp} (of ${A.size} and ${B.size}), differing blocks ${blocks}, differing columns ${cols}${firstAt ? ', first at ' + firstAt : ''}`);
