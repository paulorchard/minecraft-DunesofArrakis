#!/usr/bin/env node
// Column profile of generated chunks (prompt 09): the blocks of one column from the top down, as runs, and the block shares of a window per vertical band.
//   node profile.js <regionDir> column <x> <z>
//   node profile.js <regionDir> bands <x0> <z0> <size>        shares of each block per band: above 63, 8..63, 0..7, -59..-1, -64..-60
const fs = require('fs'), path = require('path');
const { chunks, unpack } = require('./nbt.js');
const [dir, cmd, A, B, C, D] = process.argv.slice(2);
if (!cmd) { console.error('usage: node profile.js <regionDir> column <x> <z> | bands <x0> <z0> <size>'); process.exit(1); }
const short = n => n.replace('minecraft:', '').replace('islandcraft_dunesofarrakis:', '');
function load(x0, z0, x1, z1) {
  const out = new Map();
  for (let rx = x0 >> 9; rx <= x1 >> 9; rx++) for (let rz = z0 >> 9; rz <= z1 >> 9; rz++) {
    const f = path.join(dir, `r.${rx}.${rz}.mca`); if (!fs.existsSync(f)) continue;
    for (const ch of chunks(f)) {
      if (ch.Status !== 'minecraft:full') continue;
      const bx = ch.xPos * 16, bz = ch.zPos * 16; if (bx + 15 < x0 || bx > x1 || bz + 15 < z0 || bz > z1) continue;
      const secs = [];
      for (const s of ch.sections) { const bs = s.block_states; if (!bs) continue; const pal = bs.palette.map(p => p.Name); secs.push({ y: s.Y, pal, idx: pal.length > 1 ? unpack(bs.data, Math.max(4, Math.ceil(Math.log2(pal.length))), 4096) : null }); }
      out.set(`${ch.xPos},${ch.zPos}`, secs);
    }
  }
  return out;
}
const at = (m, x, y, z) => { const l = m.get(`${x >> 4},${z >> 4}`); if (!l) return null; const s = l.find(q => q.y === (y >> 4)); if (!s) return 'minecraft:air'; return s.idx ? s.pal[s.idx[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)]] : s.pal[0]; };
if (cmd === 'column') {
  const x = +A, z = +B, m = load(x, z, x, z);
  let run = null, from = null;
  console.log(`column (${x}, ${z}), top to bottom (blocks above the sand level are y > 63):`);
  for (let y = 319; y >= -64; y--) {
    const b = at(m, x, y, z); if (b === null) { console.log('chunk not generated'); break; }
    if (b !== run) { if (run && run !== 'minecraft:air') console.log(`  y ${from} .. ${y + 1}  ${short(run)}  (${from - y} blocks)`); run = b; from = y; }
  }
  if (run && run !== 'minecraft:air') console.log(`  y ${from} .. -64  ${short(run)}`);
} else if (cmd === 'bands') {
  const x0 = +A, z0 = +B, S = +C, m = load(x0, z0, x0 + S - 1, z0 + S - 1);
  const bands = [{}, {}, {}, {}, {}], names = ['above 63', '8..63', '0..7', '-59..-1', '-64..-60'];
  for (let z = z0; z < z0 + S; z++) for (let x = x0; x < x0 + S; x++) for (let y = -64; y < 320; y++) {
    const b = at(m, x, y, z); if (b === null) break; if (b === 'minecraft:air') continue;
    const k = y > 63 ? 0 : y >= 8 ? 1 : y >= 0 ? 2 : y >= -59 ? 3 : 4; bands[k][b] = (bands[k][b] || 0) + 1;
  }
  bands.forEach((b, i) => { const t = Object.values(b).reduce((a, v) => a + v, 0); console.log(`band ${names[i]}: ${t} blocks: ` + Object.entries(b).sort((a, c) => c[1] - a[1]).map(([n, v]) => `${short(n)} ${(100 * v / t).toFixed(2)}%`).join(', ')); });
}
