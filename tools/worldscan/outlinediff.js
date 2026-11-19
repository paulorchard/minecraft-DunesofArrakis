#!/usr/bin/env node
// Outline identity between two generated worlds (prompt 06 vs prompt 05): the rock footprint is the set of columns with sandstone
// at y = 62 (below the sand level, where the sandstone body is vertical-sided). Prints the number of columns that differ.
//   node outlinediff.js <regionDirA> <regionDirB> <x0> <z0> <size>
const fs = require('fs'), path = require('path');
const { chunks, unpack } = require('./nbt.js');
const [dirA, dirB, X0, Z0, SIZE] = process.argv.slice(2); const x0 = +X0, z0 = +Z0, S = +SIZE, Y = 62;
function footprint(dir) {
  const m = new Uint8Array(S * S).fill(2); // 2 = chunk missing
  for (const f of fs.readdirSync(dir).filter(f => f.endsWith('.mca'))) for (const ch of chunks(path.join(dir, f))) {
    const bx = ch.xPos * 16, bz = ch.zPos * 16; if (bx + 15 < x0 || bx >= x0 + S || bz + 15 < z0 || bz >= z0 + S || ch.Status !== 'minecraft:full') continue;
    const s = ch.sections.find(q => q.Y === (Y >> 4)); if (!s || !s.block_states) continue;
    const pal = s.block_states.palette.map(p => p.Name), idx = pal.length > 1 ? unpack(s.block_states.data, Math.max(4, Math.ceil(Math.log2(pal.length))), 4096) : null;
    for (let i = 0; i < 256; i++) { const wx = bx + (i & 15) - x0, wz = bz + (i >> 4) - z0; if (wx < 0 || wz < 0 || wx >= S || wz >= S) continue; const n = idx ? pal[idx[((Y & 15) << 8) | i]] : pal[0]; m[wz * S + wx] = n === 'minecraft:sandstone' ? 1 : 0; }
  }
  return m;
}
const a = footprint(dirA), b = footprint(dirB); let diff = 0, rockA = 0, missing = 0, first = null;
for (let k = 0; k < S * S; k++) { if (a[k] === 2 || b[k] === 2) { missing++; continue; } if (a[k] === 1) rockA++; if (a[k] !== b[k]) { diff++; if (!first) first = [x0 + k % S, z0 + ((k / S) | 0)]; } }
console.log(`footprint columns ${rockA}, differing ${diff}, missing ${missing}${first ? ', first at ' + first : ''}`);
