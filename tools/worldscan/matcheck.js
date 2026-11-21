#!/usr/bin/env node
// Material check (prompt 12): blocks of a generated window against the field maps.
//   node matcheck.js <regionDir> <mask.bin> <rockmap.i16> <maps_prefix> <x0> <z0> <size>
// (maps_prefix: ReliefScan `maps` output, <prefix>_base.i16 and <prefix>_kind.bin.)  Env SKIN: sandstone skin thickness (default 3).
// Reports: block census by vertical band inside and outside the footprint; forbidden blocks inside the footprint (anything outside
// {sand, sandstone, stone, andesite, diorite, granite, tuff, calcite, deepslate, bedrock}); the sandstone skin of the base rock (the rock top is the
// base top: sandstone exactly SKIN blocks, then stone or a blob); the foot fade of the tough rock (sandstone share by block above the base top, the height
// where it drops below 50% and 5%, per-column foot height); exposed stone on the base surface; air below the surface; one full face column per kind.
const fs = require('fs'), path = require('path');
const { chunks, unpack } = require('./nbt.js');
const [dir, maskF, rockF, prefix, X0, Z0, SIZE] = process.argv.slice(2);
const x0 = +X0, z0 = +Z0, S = +SIZE, SKIN = +(process.env.SKIN || 3), NO = -32768;
const mask = fs.readFileSync(maskF);
const rd = f => { const b = fs.readFileSync(f); return new Int16Array(b.buffer.slice(b.byteOffset, b.byteOffset + b.length)); };
const rock = rd(rockF), base = rd(prefix + '_base.i16'), kind = fs.readFileSync(prefix + '_kind.bin');
const DUNE = 'islandcraft_dunesofarrakis:dune_sand';
const OK = new Set(['minecraft:sandstone', 'minecraft:stone', 'minecraft:andesite', 'minecraft:diorite', 'minecraft:granite', 'minecraft:tuff', 'minecraft:calcite', 'minecraft:deepslate', 'minecraft:bedrock', DUNE, 'minecraft:air']);
const census = { in: {}, out: {} }, forbidden = {};
let air = 0, cols = 0, skinOk = 0, skinBad = 0, exposedStone = 0, baseCols = 0, toughCols = 0;
const skinBadEx = [];
const fade = { 1: [], 2: [], 3: [] }, fadeCols = { 1: [], 2: [], 3: [] }, faceCols = { 1: null, 2: null, 3: null };
for (const f of fs.readdirSync(dir).filter(f => f.endsWith('.mca'))) for (const ch of chunks(path.join(dir, f))) {
  const bx = ch.xPos * 16, bz = ch.zPos * 16;
  if (bx + 15 < x0 || bx >= x0 + S || bz + 15 < z0 || bz >= z0 + S || ch.Status !== 'minecraft:full') continue;
  const secs = ch.sections.filter(s => s.block_states).map(s => {
    const pal = s.block_states.palette.map(p => p.Name);
    return { y: s.Y, pal, idx: pal.length > 1 ? unpack(s.block_states.data, Math.max(4, Math.ceil(Math.log2(pal.length))), 4096) : null };
  }).sort((a, b) => b.y - a.y);
  for (let i = 0; i < 256; i++) {
    const wx = bx + (i & 15) - x0, wz = bz + (i >> 4) - z0;
    if (wx < 0 || wz < 0 || wx >= S || wz >= S) continue;
    const k = wz * S + wx, inside = mask[k] !== 0;
    cols++;
    const colBlocks = []; // top to bottom
    for (const s of secs) for (let ly = 15; ly >= 0; ly--) colBlocks.push([s.y * 16 + ly, s.idx ? s.pal[s.idx[(ly << 8) | i]] : s.pal[0]]);
    const byY = new Map(colBlocks);
    const nameAt = y => byY.get(y) || 'minecraft:air';
    let topY = null;
    for (const [y, n] of colBlocks) if (n !== 'minecraft:air') { topY = y; break; }
    for (const [y, n] of colBlocks) {
      if (n === 'minecraft:air') { if (topY !== null && y < topY) air++; continue; }
      const band = y > 63 ? 'above63' : y >= 8 ? '8..63' : y >= 0 ? '0..7' : 'below0';
      const c = inside ? census.in : census.out;
      const b = c[band] = c[band] || {};
      b[n] = (b[n] || 0) + 1;
      if (inside && !OK.has(n)) { forbidden[n] = (forbidden[n] || 0) + 1; if (!forbidden._first) forbidden._first = {}; if (!forbidden._first[n]) forbidden._first[n] = [x0 + wx, y, z0 + wz, mask[k]]; }
    }
    if (!inside || base[k] === NO) continue;
    const kd = kind[k], rt = rock[k], bt = base[k];
    if (kd === 0 || rt === bt) { // base rock column
      baseCols++;
      const top = nameAt(bt);
      if (top === DUNE) continue;
      const sk = [];
      for (let d = 0; d <= SKIN; d++) sk.push(nameAt(bt - d));
      const ok = sk.slice(0, SKIN).every(n => n === 'minecraft:sandstone') && sk[SKIN] !== 'minecraft:sandstone' && sk[SKIN] !== 'minecraft:air';
      if (ok) skinOk++; else { skinBad++; if (skinBadEx.length < 5) skinBadEx.push(`(${x0 + wx},${z0 + wz}) ${sk.join(' ')}`); }
      if (top === 'minecraft:stone') exposedStone++;
    } else if (rt > bt && kd >= 1 && kd <= 3) { // tough rock column
      toughCols++;
      for (let h = 1; h <= 12; h++) {
        if (bt + h > rt) break;
        const nm = nameAt(bt + h);
        if (nm === DUNE) break;
        const r = fade[kd][h] = fade[kd][h] || [0, 0];
        r[1]++;
        if (nm === 'minecraft:sandstone') r[0]++;
      }
      let h0 = 1;
      while (h0 <= 14 && bt + h0 <= rt && nameAt(bt + h0) === 'minecraft:sandstone') h0++;
      if (rt - bt > 14 && nameAt(rt) !== DUNE) fadeCols[kd].push(h0 - 1);
      if (!faceCols[kd] && rt - bt > 30 && nameAt(rt) !== DUNE) faceCols[kd] = [x0 + wx, z0 + wz, colBlocks.filter(([y]) => y <= rt && y > bt - SKIN - 3).map(([, n]) => n)];
    }
  }
}
console.log(`columns ${cols}, air below the surface ${air}; base columns ${baseCols}, tough-rock columns ${toughCols}`);
console.log('forbidden blocks inside the footprint:', Object.keys(forbidden).length ? JSON.stringify(forbidden) : 'none');
for (const side of ['in', 'out']) {
  console.log(`census ${side === 'in' ? 'inside' : 'outside'} the footprint by band:`);
  for (const [b, v] of Object.entries(census[side])) {
    const t = Object.values(v).reduce((a, c) => a + c, 0);
    console.log('  ' + b.padEnd(8) + Object.entries(v).sort((a, c) => c[1] - a[1]).slice(0, 8).map(([n, c]) => `${n.replace('minecraft:', '')} ${(100 * c / t).toFixed(1)}%`).join(', '));
  }
}
console.log(`base rock columns with the rock surface exposed: skin of ${SKIN} sandstone then stone or blob: ${skinOk} ok, ${skinBad} not${skinBadEx.length ? '; first: ' + skinBadEx.join(' | ') : ''}; exposed stone on the base surface: ${exposedStone}`);
for (const kd of [1, 2, 3]) {
  const r = fade[kd];
  if (!r.length) continue;
  const shares = r.map(v => (v ? v[0] / v[1] : null));
  const first = lim => { for (let h = 1; h < shares.length; h++) if (shares[h] !== null && shares[h] < lim) return h; return '>12'; };
  const fc = fadeCols[kd].sort((a, b) => a - b), q = p => fc[Math.min(fc.length - 1, Math.floor(fc.length * p))];
  console.log(`kind ${kd} tough rock (${r[1] ? r[1][1] : 0} foot columns): sandstone share by block above the base top (h 1..12, %): ${shares.slice(1).map(v => (v === null ? '-' : Math.round(100 * v))).join(' ')}; below 50% at h ${first(.5)}, below 5% at h ${first(.05)}; unbroken sandstone foot height per tall column p10/p50/p90: ${fc.length ? q(.1) + '/' + q(.5) + '/' + q(.9) : '-'}`);
  if (faceCols[kd]) {
    const o = [];
    for (const n of faceCols[kd][2]) { const s = n.replace('minecraft:', ''); if (o.length && o[o.length - 1][0] === s) o[o.length - 1][1]++; else o.push([s, 1]); }
    console.log(`  one face column of kind ${kd} at (${faceCols[kd][0]}, ${faceCols[kd][1]}), top down to ${SKIN + 2} blocks under the base top: ${o.map(([s, c]) => `${s} x${c}`).join(', ')}`);
  }
}
