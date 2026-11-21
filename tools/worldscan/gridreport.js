#!/usr/bin/env node
// gridreport: checks and pictures for the biome grid, from a gridscan.js dump.
//   node tools/worldscan/gridreport.js <scanPrefix> <outPrefix> [--mpp 4]
// Writes <outPrefix>_biomes.png (top-down biome map with legend), <outPrefix>_height.png (hillshaded surface height),
// <outPrefix>_report.json and prints the same numbers as text. The layout comes from tools/rewrite/grid.json.
const fs = require('fs'), zlib = require('zlib'), path = require('path');
const args = process.argv.slice(2);
const mppI = args.indexOf('--mpp'); const MPP = mppI >= 0 ? +args[mppI + 1] : 4; if (mppI >= 0) args.splice(mppI, 2);
const [scanPrefix, outPrefix] = args;
const grid = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'rewrite', 'grid.json'), 'utf8'));
const NS = grid.namespace, SIZE = grid.patch_size, RING = grid.blend, SEA = 63;
const hdr = JSON.parse(fs.readFileSync(scanPrefix + '.json', 'utf8'));
const { x0, z0, width: W, height: H } = hdr, n = W * H;
const bin = fs.readFileSync(scanPrefix + '.bin');
const top = new Int16Array(bin.buffer.slice(bin.byteOffset, bin.byteOffset + n * 2));
const plane = k => bin.subarray(n * (1 + k), n * (2 + k));      // k=1 biome, 2 surf, 3 cover, 4 void, 5 plant
const bio = plane(1), surf = plane(2), cover = plane(3), vd = plane(4), pl = plane(5);
const bname = i => hdr.biomes[i].replace(NS + ':', '').replace('minecraft:', 'minecraft:');
const patches = grid.patches.map(p => {
  const px0 = grid.origin_x + (SIZE + grid.gap_x) * p.col, pz0 = grid.origin_z + (SIZE + grid.gap_z) * p.row;
  return { name: p.biome, x0: px0, z0: pz0, x1: px0 + SIZE, z1: pz0 + SIZE };
});
const inSq = (p, x, z, m) => x >= p.x0 - m && x < p.x1 + m && z >= p.z0 - m && z < p.z1 + m;

// per column region: -1 outside everything, otherwise patch index inner (>=0, value i) or ring (value 100+i)
const region = new Int16Array(n).fill(-1);
for (let i = 0; i < patches.length; i++) {
  const p = patches[i];
  for (let z = Math.max(z0, p.z0 - RING); z < Math.min(z0 + H, p.z1 + RING); z++)
    for (let x = Math.max(x0, p.x0 - RING); x < Math.min(x0 + W, p.x1 + RING); x++)
      region[(z - z0) * W + (x - x0)] = inSq(p, x, z, 0) ? i : 100 + i;
}
const pct = (a, b) => b ? +(100 * a / b).toFixed(2) : 0;
const quant = (arr, qs) => { const a = Float64Array.from(arr).sort(); return qs.map(q => a.length ? a[Math.min(a.length - 1, Math.floor(q * a.length))] : null); };
const R = { scan: scanPrefix, window: { x0, z0, width: W, height: H }, chunks: hdr.chunks, patches: {}, outside: {}, caves: {}, census: {} };

// ---- layout: share of target biome inside each patch; desert outside
let worstInner = 100;
for (let i = 0; i < patches.length; i++) {
  const p = patches[i], target = NS + ':' + p.name, ti = hdr.biomes.indexOf(target);
  let cnt = 0, tot = 0; const ringH = {}, innerH = {}, heights = [], surfH = {};
  for (let z = p.z0; z < p.z1; z++) for (let x = p.x0; x < p.x1; x++) {
    const k = (z - z0) * W + (x - x0); tot++;
    if (bio[k] === ti) cnt++; else innerH[bname(bio[k])] = (innerH[bname(bio[k])] || 0) + 1;
    heights.push(top[k]); const sn = hdr.blocks[surf[k]]; surfH[sn] = (surfH[sn] || 0) + 1;
  }
  let rtot = 0;
  for (let k = 0; k < n; k++) if (region[k] === 100 + i) { rtot++; const b = bname(bio[k]); ringH[b] = (ringH[b] || 0) + 1; }
  const q = quant(heights, [0.05, 0.5, 0.95, 0.9999]);
  const rec = { square: [p.x0, p.z0, p.x1, p.z1], inner_target_share_pct: pct(cnt, tot), inner_other_biomes: innerH,
    ring_columns: rtot, ring_biomes_pct: Object.fromEntries(Object.entries(ringH).map(([b, c]) => [b, pct(c, rtot)])),
    height: { p5: q[0], p50: q[1], p95: q[2], max: Math.max(...heights.slice(0, 1), heights.reduce((a, b) => a > b ? a : b, -999)) },
    top_blocks_pct: Object.fromEntries(Object.entries(surfH).sort((a, b) => b[1] - a[1]).slice(0, 5).map(([b, c]) => [b.replace('minecraft:', ''), pct(c, tot)])) };
  if (p.name === 'deep_frozen_ocean') {
    const floor = heights.map(h => SEA - h); const fq = quant(floor, [0.05, 0.5, 0.95, 0.9999]);
    let wet = 0, iced = 0;
    for (let z = p.z0; z < p.z1; z++) for (let x = p.x0; x < p.x1; x++) {
      const k = (z - z0) * W + (x - x0); if (top[k] < SEA) { wet++; if (/ice|snow/.test(hdr.blocks[cover[k]])) iced++; }
    }
    rec.ocean = { floor_depth_below_sea_p5_p50_p95_max: fq, water_columns_pct: pct(wet, tot), ice_or_snow_cover_of_water_columns_pct: pct(iced, wet) };
  }
  R.patches[p.name] = rec; worstInner = Math.min(worstInner, rec.inner_target_share_pct);
}
{ // outside everything: desert share, strays, desert terrain
  let tot = 0, des = 0; const other = {}, hs = [];
  const di = hdr.biomes.indexOf(NS + ':desert');
  for (let k = 0; k < n; k++) if (region[k] === -1) { tot++; hs.push(top[k]); if (bio[k] === di) des++; else { const b = bname(bio[k]); other[b] = (other[b] || 0) + 1; } }
  const q = quant(hs, [0.05, 0.5, 0.95]);
  R.outside = { columns: tot, desert_share_pct: pct(des, tot), other_biomes_columns: other, height_p5_p50_p95_max: [...q, hs.reduce((a, b) => a > b ? a : b, -999)] };
}
// ---- no caves: voids below the top terrain block, split into open (overhangs, connected to the outside) and sealed
{
  const v = new Int32Array(fs.readFileSync(scanPrefix + '.vox').buffer.slice(0)), N = v.length / 3;
  const key = (x, y, z) => (y + 64) * n + z * W + x;
  const set = new Map(); for (let i = 0; i < N; i++) set.set(key(v[i * 3], v[i * 3 + 1], v[i * 3 + 2]), i);
  const open = new Uint8Array(N), stack = [];
  const D = [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]];
  const outside = (x, y, z) => x < 0 || z < 0 || x >= W || z >= H || y > top[z * W + x];
  for (let i = 0; i < N; i++) for (const d of D) if (outside(v[i * 3] + d[0], v[i * 3 + 1] + d[1], v[i * 3 + 2] + d[2])) { open[i] = 1; stack.push(i); break; }
  while (stack.length) {
    const i = stack.pop();
    for (const d of D) { const j = set.get(key(v[i * 3] + d[0], v[i * 3 + 1] + d[1], v[i * 3 + 2] + d[2])); if (j !== undefined && !open[j]) { open[j] = 1; stack.push(j); } }
  }
  let sealed = 0; const sealedAt = [];
  for (let i = 0; i < N; i++) if (!open[i]) { sealed++; if (sealedAt.length < 12) sealedAt.push([v[i * 3] + x0, v[i * 3 + 1], v[i * 3 + 2] + z0]); }
  // sizes of the sealed pockets (connected groups of sealed void blocks)
  const seen = new Uint8Array(N), sizes = [];
  for (let i = 0; i < N; i++) if (!open[i] && !seen[i]) { let c = 0; const st = [i]; seen[i] = 1; while (st.length) { const a = st.pop(); c++; for (const d of D) { const j = set.get(key(v[a * 3] + d[0], v[a * 3 + 1] + d[1], v[a * 3 + 2] + d[2])); if (j !== undefined && !open[j] && !seen[j]) { seen[j] = 1; st.push(j); } } } sizes.push(c); }
  sizes.sort((a, b) => a - b);
  let vcols = 0, pcols = 0; for (let k = 0; k < n; k++) { if (vd[k]) vcols++; if (pl[k]) pcols++; }
  // overhang columns per patch / outside
  const perPatch = {}; for (let k = 0; k < n; k++) if (vd[k]) { const r = region[k]; const nm = r === -1 ? 'outside' : patches[r % 100].name + (r >= 100 ? '_ring' : ''); perPatch[nm] = (perPatch[nm] || 0) + 1; }
  R.caves = { columns_with_void_below_top: vcols, void_blocks: N, void_blocks_open_to_outside_overhangs: N - sealed, void_blocks_sealed: sealed, sealed_pockets: sizes.length, sealed_pocket_size_median_max: [sizes[sizes.length >> 1] || 0, sizes[sizes.length - 1] || 0], sealed_pockets_larger_than_20_blocks: sizes.filter(z => z > 20).length, sealed_examples: sealedAt, columns_with_non_terrain_plants_below_top: pcols, void_columns_by_area: perPatch };
}
// ---- census of notable blocks
{
  const c = hdr.census || {}, sum = f => Object.entries(c).filter(([k]) => f(k)).reduce((a, [, v]) => a + v, 0);
  const pick = re => Object.fromEntries(Object.entries(c).filter(([k]) => re.test(k)).map(([k, v]) => [k.replace('minecraft:', ''), v]));
  R.census = { ores_total: sum(k => /_ore$/.test(k)), ores: pick(/_ore$/), monster_room_or_geode_blocks: pick(/spawner|mossy_cobblestone|amethyst|calcite|smooth_basalt|dripstone/), fluids: pick(/water|lava/), chests: pick(/chest/) };
}
// ---- pictures
const COL = { desert: [230, 213, 138], plains: [92, 184, 92], windswept_hills: [79, 111, 82], windswept_gravelly_hills: [143, 143, 143], ice_spikes: [159, 230, 255],
  jagged_peaks: [255, 255, 255], stony_peaks: [106, 106, 138], windswept_savanna: [200, 162, 50], badlands: [196, 85, 45], eroded_badlands: [138, 58, 42], deep_frozen_ocean: [28, 63, 154] };
const GLY = { A: '010101111101101', B: '110101110101110', C: '011100100100011', D: '110101101101110', E: '111100110100111', F: '111100110100100', G: '011100101101011', H: '101101111101101', I: '111010010010111', J: '001001001101010', K: '101101110101101', L: '100100100100111', M: '101111111101101', N: '110101101101101', O: '010101101101010', P: '110101110100100', Q: '010101101110011', R: '110101110101101', S: '011100010001110', T: '111010010010010', U: '101101101101111', V: '101101101101010', W: '101101111111101', X: '101101010101101', Y: '101101010010010', Z: '111001010100111',
  0: '111101101101111', 1: '010110010010111', 2: '110001010100111', 3: '110001010001110', 4: '101101111001001', 5: '111100110001110', 6: '011100111101111', 7: '111001010010010', 8: '111101111101111', 9: '111101111001110', _: '000000000000111', '-': '000000111000000', '.': '000000000000010', ':': '000010000010000', ' ': '000000000000000' };
function text(img, w, s, px, py, col, sc) { for (const ch of s.toUpperCase()) { const g = GLY[ch] || GLY[' ']; for (let r = 0; r < 5; r++) for (let c = 0; c < 3; c++) if (g[r * 3 + c] === '1') for (let a = 0; a < sc; a++) for (let b = 0; b < sc; b++) { const o = ((py + r * sc + b) * w + px + c * sc + a) * 3; img[o] = col[0]; img[o + 1] = col[1]; img[o + 2] = col[2]; } px += 4 * sc; } }
function writePng(file, w, h, rgb) {
  const crcT = new Uint32Array(256); for (let k = 0; k < 256; k++) { let c = k; for (let j = 0; j < 8; j++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; crcT[k] = c >>> 0; }
  const crc = bb => { let c = 0xffffffff; for (const v of bb) c = crcT[(c ^ v) & 255] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
  const chunk = (t, d) => { const l = Buffer.alloc(4); l.writeUInt32BE(d.length); const td = Buffer.concat([Buffer.from(t), d]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([l, td, c]); };
  const raw = Buffer.alloc((w * 3 + 1) * h); for (let y = 0; y < h; y++) { raw[y * (w * 3 + 1)] = 0; Buffer.from(rgb.buffer, rgb.byteOffset + y * w * 3, w * 3).copy(raw, y * (w * 3 + 1) + 1); }
  const ih = Buffer.alloc(13); ih.writeUInt32BE(w, 0); ih.writeUInt32BE(h, 4); ih[8] = 8; ih[9] = 2;
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ih), chunk('IDAT', zlib.deflateSync(raw, { level: 6 })), chunk('IEND', Buffer.alloc(0))]));
}
const PW = Math.floor(W / MPP), PH = Math.floor(H / MPP), LEG = 24 + 16 * 4 + 16;
function drawOutlines(img, w) {
  for (const p of patches) for (const m of [0, RING]) {
    const xa = Math.floor((p.x0 - m - x0) / MPP), xb = Math.floor((p.x1 + m - x0) / MPP), za = Math.floor((p.z0 - m - z0) / MPP), zb = Math.floor((p.z1 + m - z0) / MPP);
    const c = m === 0 ? [0, 0, 0] : [90, 90, 90];
    for (let x = xa; x <= xb; x++) for (const z of [za, zb]) { const o = (z * w + x) * 3; img[o] = c[0]; img[o + 1] = c[1]; img[o + 2] = c[2]; }
    for (let z = za; z <= zb; z++) for (const x of [xa, xb]) { const o = (z * w + x) * 3; img[o] = c[0]; img[o + 1] = c[1]; img[o + 2] = c[2]; }
  }
}
{ // biome map (one sample per MPP x MPP block, taken at its centre), legend below
  const h = PH + LEG, img = new Uint8Array(PW * h * 3).fill(255);
  for (let py = 0; py < PH; py++) for (let px = 0; px < PW; px++) {
    const k = (py * MPP + (MPP >> 1)) * W + px * MPP + (MPP >> 1), nm = bname(bio[k]), c = COL[nm] || [255, 0, 255], o = (py * PW + px) * 3;
    img[o] = c[0]; img[o + 1] = c[1]; img[o + 2] = c[2];
  }
  drawOutlines(img, PW);
  const names = Object.keys(COL); let ly = PH + 10;
  text(img, PW, 'BIOME MAP ' + MPP + ' BLOCKS PER PIXEL  X EAST Z SOUTH  ORIGIN X ' + x0 + ' Z ' + z0 + '  BLACK = 500 SQUARE  GREY = +20 RING', 8, ly, [0, 0, 0], 2); ly += 24;
  names.forEach((nm, i) => { const cx = 8 + (i % 3) * 330, cy = ly + Math.floor(i / 3) * 16; for (let a = 0; a < 12; a++) for (let b = 0; b < 12; b++) { const o = ((cy + b) * PW + cx + a) * 3; img[o] = COL[nm][0]; img[o + 1] = COL[nm][1]; img[o + 2] = COL[nm][2]; } text(img, PW, nm, cx + 16, cy + 1, [0, 0, 0], 2); });
  writePng(outPrefix + '_biomes.png', PW, h, img);
}
{ // hillshaded height map: mean height per pixel, light from the north-west, blue below sea level, brighter = higher
  const hh = new Float32Array(PW * PH);
  for (let py = 0; py < PH; py++) for (let px = 0; px < PW; px++) { let s = 0; for (let a = 0; a < MPP; a++) for (let b = 0; b < MPP; b++) s += top[(py * MPP + a) * W + px * MPP + b]; hh[py * PW + px] = s / (MPP * MPP); }
  let lo = 1e9, hi = -1e9; for (const v of hh) { if (v < lo) lo = v; if (v > hi) hi = v; }
  const img = new Uint8Array(PW * (PH + 40) * 3).fill(255);
  for (let py = 0; py < PH; py++) for (let px = 0; px < PW; px++) {
    const v = hh[py * PW + px], l = hh[py * PW + Math.max(0, px - 1)], u = hh[Math.max(0, py - 1) * PW + px], r = hh[py * PW + Math.min(PW - 1, px + 1)], d = hh[Math.min(PH - 1, py + 1) * PW + px];
    const gx = (r - l) / (2 * MPP), gz = (d - u) / (2 * MPP);
    const sh = Math.max(0.15, Math.min(1.4, 0.75 + 1.6 * (-(gx) - gz) * 0.7 / Math.SQRT2 * 4 / 4));
    const t = (v - lo) / (hi - lo), o = (py * PW + px) * 3;
    let c = v < SEA - 0.5 ? [30 + 60 * (1 - (SEA - v) / (SEA - lo + 1)), 70, 150] : [90 + 150 * t, 110 + 120 * t, 60 + 130 * t];
    for (let i = 0; i < 3; i++) img[o + i] = Math.max(0, Math.min(255, c[i] * sh));
  }
  drawOutlines(img, PW);
  text(img, PW, 'HEIGHT HILLSHADE ' + MPP + ' BLOCKS PER PIXEL  MIN Y ' + Math.round(lo) + ' MAX Y ' + Math.round(hi) + ' (MEAN OF EACH PIXEL)  BLUE = BELOW SEA LEVEL 63', 8, PH + 12, [0, 0, 0], 2);
  writePng(outPrefix + '_height.png', PW, PH + 40, img);
}
R.worst_inner_target_share_pct = worstInner;
fs.writeFileSync(outPrefix + '_report.json', JSON.stringify(R, null, 1));
console.log(JSON.stringify(R, null, 1));
