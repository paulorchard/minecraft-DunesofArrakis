#!/usr/bin/env node
// Visibility of land masses against the dunes (prompt 12, check 12).
//   node visibility.js <regionDir> <massmap.bin> <x0> <z0> <size>  [more triples ...]
// massmap.bin (+ .tsv) from `scatter.sh massmap`: per column the land mass index (-1 sand); the .tsv lists index, level, across, kind, parent, cx, cz.
// For every mass whose land columns lie wholly inside the window: land columns, share whose top block is dune sand (buried), whether any column shows rock
// (visible), the peak above y 63. Per level: counts, masses more than 80% dune-covered, masses not visible at all, the smallest visible mass and its peak.
const fs = require('fs'), path = require('path');
const { chunks, unpack } = require('./nbt.js');
const LV = { 4: 'large', 5: 'medium', 6: 'small', 7: 'satellite', 8: 'spawn', 9: 'islet' };
const DUNE = 'islandcraft_dunesofarrakis:dune_sand', SAND = 63;
const args = process.argv.slice(2), results = [];
for (let a = 0; a + 4 < args.length + 0; a += 5) {
  const [dir, mapF, X0, Z0, SIZE] = args.slice(a, a + 5); const x0 = +X0, z0 = +Z0, S = +SIZE;
  const mb = fs.readFileSync(mapF), mm = new Int32Array(mb.buffer.slice(mb.byteOffset, mb.byteOffset + mb.length));
  const info = new Map(); for (const l of fs.readFileSync(mapF + '.tsv', 'utf8').split('\n').filter(x => x.trim())) { const t = l.split('\t'); info.set(+t[0], { level: +t[1], across: +t[2], kind: +t[3], parent: +t[4] }); }
  const st = new Map(); // mass -> {cols, buried, peak, edge}
  for (const f of fs.readdirSync(dir).filter(f => f.endsWith('.mca'))) for (const ch of chunks(path.join(dir, f))) {
    const bx = ch.xPos * 16, bz = ch.zPos * 16;
    if (bx + 15 < x0 || bx >= x0 + S || bz + 15 < z0 || bz >= z0 + S || ch.Status !== 'minecraft:full') continue;
    const hm = ch.Heightmaps && ch.Heightmaps.WORLD_SURFACE; if (!hm) continue;
    const v = unpack(hm, 9, 256), minY = ch.yPos * 16;
    const secs = new Map(); for (const s of ch.sections) { if (!s.block_states) continue; const pal = s.block_states.palette.map(p => p.Name); secs.set(s.Y, { pal, idx: pal.length > 1 ? unpack(s.block_states.data, Math.max(4, Math.ceil(Math.log2(pal.length))), 4096) : null }); }
    for (let i = 0; i < 256; i++) {
      const wx = bx + (i & 15) - x0, wz = bz + (i >> 4) - z0; if (wx < 0 || wz < 0 || wx >= S || wz >= S) continue;
      const m = mm[wz * S + wx]; if (m < 0) continue;
      const y = minY + v[i] - 1, sec = secs.get(Math.floor(y / 16)); if (!sec) continue;
      const ly = ((y % 16) + 16) % 16, name = sec.idx ? sec.pal[sec.idx[(ly << 8) | i]] : sec.pal[0];
      let r = st.get(m); if (!r) st.set(m, r = { cols: 0, buried: 0, peak: -999, edge: false });
      r.cols++; if (name === DUNE) r.buried++; else r.peak = Math.max(r.peak, y - SAND);
      if (wx === 0 || wz === 0 || wx === S - 1 || wz === S - 1) r.edge = true;
    }
  }
  for (const [m, r] of st) if (!r.edge || process.env.PARTIAL) results.push({ m, ...r, ...info.get(m) });
}
const out = {};
for (const r of results) { const o = out[r.level] = out[r.level] || { n: 0, over80: 0, invisible: 0, minVisible: null, minVisAcross: 1e9, buriedShare: [] }; o.n++; const share = r.buried / r.cols; o.buriedShare.push(share); if (share > .8) o.over80++; if (r.peak === -999) o.invisible++; else if (r.across < o.minVisAcross) { o.minVisAcross = r.across; o.minVisible = r; } }
console.log(`masses wholly inside the windows: ${results.length}`);
for (const [lv, o] of Object.entries(out)) { o.buriedShare.sort((a, b) => a - b); console.log(`${(LV[lv] || lv).padEnd(9)} ${String(o.n).padStart(4)} masses; more than 80% dune-covered: ${o.over80}; not visible at all (no rock on the surface): ${o.invisible}; median dune-covered share ${(100 * o.buriedShare[Math.floor(o.n / 2)]).toFixed(0)}%${o.minVisible ? `; smallest visible: ${o.minVisAcross} m across, peak ${o.minVisible.peak} m above the sand` : ''}`); }
const thin = results.filter(r => r.peak !== -999).sort((a, b) => a.across - b.across)[0];
if (thin) console.log(`smallest visible island overall: ${thin.across} m across (${LV[thin.level]}), peak ${thin.peak} m above y 63`);
