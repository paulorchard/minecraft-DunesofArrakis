#!/usr/bin/env node
// blockat: print the blocks around a point (a 7 x 7 slice in x for the given z, y +-4) from a saved region folder.
//   node tools/worldscan/blockat.js <regionDir> <x> <y> <z> [radius 3]
const { chunks, unpack } = require('./nbt.js'); const fs = require('fs'), path = require('path');
const [dir, X, Y, Z, R] = process.argv.slice(2); const x = +X, y = +Y, z = +Z, r = +(R || 3);
const cx = Math.floor(x / 16), cz = Math.floor(z / 16);
const rx = Math.floor(cx / 32), rz = Math.floor(cz / 32), file = path.join(dir, `r.${rx}.${rz}.mca`);
function blockIn(c, bx, by, bz) {
  const s = c.sections.find(s => s.Y === Math.floor(by / 16)); if (!s || !s.block_states) return 'minecraft:air';
  const pal = s.block_states.palette.map(p => p.Name); if (pal.length === 1) return pal[0];
  const idx = unpack(s.block_states.data, Math.max(4, Math.ceil(Math.log2(pal.length))), 4096);
  return pal[idx[((by & 15) << 8) | ((bz & 15) << 4) | (bx & 15)]];
}
const want = new Map();
for (const c of chunks(file)) want.set(c.xPos + ',' + c.zPos, c);
const ab = n => n.replace('minecraft:', '').slice(0, 9).padEnd(9);
for (let yy = y + r; yy >= y - r; yy--) {
  let line = String(yy).padStart(4) + ' ';
  for (let xx = x - r; xx <= x + r; xx++) { const c = want.get(Math.floor(xx / 16) + ',' + Math.floor(z / 16)); line += (c ? ab(blockIn(c, xx, yy, z)) : '?        ') + ' '; }
  console.log(line);
}
