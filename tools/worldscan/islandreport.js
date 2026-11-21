#!/usr/bin/env node
// islandreport: checks, radial tables and pictures for the eight circular islands, from a gridscan.js dump.
//   node tools/worldscan/islandreport.js <scanPrefix> <outPrefix> [--dunes <dune-only scanPrefix>] [--mpp 2]
// --dunes: a scan of the same area and seed generated with the dune-only world type (the test preset), so that the dune height
//          under every island and ring is known exactly.
// Writes <outPrefix>_biomes.png, <outPrefix>_height.png (islands, edge, edge+100 and edge+200 drawn as thin lines),
// <outPrefix>_report.json and <outPrefix>_radial.md. The layout comes from tools/rewrite/grid.json.
const fs = require('fs'), zlib = require('zlib'), path = require('path');
const args = process.argv.slice(2);
const opt = (n, d) => { const i = args.indexOf('--' + n); if (i < 0) return d; const v = args[i + 1]; args.splice(i, 2); return v; };
const MPP = +opt('mpp', 2), DUNES = opt('dunes', null);
const [scanPrefix, outPrefix] = args;
const grid = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'rewrite', 'grid.json'), 'utf8'));
const NS = grid.namespace, SEA = 63;
const A = grid.diameter_x / 2, B = grid.diameter_z / 2, T = grid.transition, RM = (A + B) / 2, REND = 1 + T / RM;
const islands = grid.islands.map(p => Object.assign({ cx: grid.origin_x + grid.pitch * p.col, cz: grid.origin_z + grid.pitch * p.row }, p));
const DUNE_BIOME = NS + ':' + grid.desert.biome;
const load = prefix => {
  const hdr = JSON.parse(fs.readFileSync(prefix + '.json', 'utf8')), n = hdr.width * hdr.height, bin = fs.readFileSync(prefix + '.bin');
  const plane = k => bin.subarray(n * (1 + k), n * (2 + k));
  return { hdr, n, top: new Int16Array(bin.buffer.slice(bin.byteOffset, bin.byteOffset + n * 2)), bio: plane(1), surf: plane(2), cover: plane(3), vd: plane(4), pl: plane(5), prefix };
};
const S = load(scanPrefix), { hdr } = S, { x0, z0, width: W, height: H } = hdr, n = S.n;
const D = DUNES ? load(DUNES) : null;
const bname = i => hdr.biomes[i].replace(NS + ':', '');
const dist = q => (Math.sqrt(q) - 1) * RM;     // radial distance beyond the edge, blocks (the approximation the generator uses)
// per column: nearest island by normalised squared distance
const qmin = new Float32Array(n).fill(1e9), who = new Int8Array(n).fill(-1);
for (let k = 0; k < islands.length; k++) {
  const i = islands[k];
  for (let z = 0; z < H; z++) { const dz = ((z + z0 - i.cz) / B) ** 2; for (let x = 0; x < W; x++) { const q = ((x + x0 - i.cx) / A) ** 2 + dz, o = z * W + x; if (q < qmin[o]) { qmin[o] = q; who[o] = k; } } }
}
const QEND = REND * REND, QOUT = (REND + 20 / RM) ** 2;
const pct = (a, b) => b ? +(100 * a / b).toFixed(2) : 0;
const quant = (a, qs) => { const s = Float64Array.from(a).sort(); return qs.map(q => s.length ? s[Math.min(s.length - 1, Math.floor(q * s.length))] : null); };
const R = { scan: scanPrefix, mode_note: 'biome mode is a property of the world type used for the scan', window: { x0, z0, width: W, height: H }, chunks: hdr.chunks, islands: {}, dunes: {}, caves: {}, layout: {} };

// ---- islands
let worstInner = 100;
const radialRows = [];
for (let k = 0; k < islands.length; k++) {
  const i = islands[k], ti = hdr.biomes.indexOf(NS + ':' + i.biome), rec = {};
  let inner = 0, innerTot = 0; const others = {}, ringB = {}, ringBins = Array.from({ length: 10 }, () => ({})), ellipseH = [], ringH = [];
  let ringTot = 0, water = { ellipse: 0, ring: 0 };
  const stepMax = { ellipse: 0, ring: 0 }, stepList = { ellipse: [], ring: [] };
  for (let z = Math.floor(i.cz - B * REND - 2); z <= Math.ceil(i.cz + B * REND + 2); z++) for (let x = Math.floor(i.cx - A * REND - 2); x <= Math.ceil(i.cx + A * REND + 2); x++) {
    if (x < x0 || x >= x0 + W || z < z0 || z >= z0 + H) continue;
    const o = (z - z0) * W + (x - x0); if (who[o] !== k) continue;
    const q = qmin[o];
    if (q <= 0.81) { innerTot++; if (S.bio[o] === ti) inner++; else { const b = bname(S.bio[o]); others[b] = (others[b] || 0) + 1; } }
    let region = null; if (q <= 1) region = 'ellipse'; else if (q <= QEND) region = 'ring';
    if (!region) continue;
    if (region === 'ellipse') ellipseH.push(S.top[o]); else {
      ringTot++; ringH.push(S.top[o]); const b = bname(S.bio[o]); ringB[b] = (ringB[b] || 0) + 1;
      const bin = Math.min(9, Math.floor(dist(q) / (T / 10))); ringBins[bin][b] = (ringBins[bin][b] || 0) + 1;
    }
    if (S.top[o] < SEA && /water/.test(hdr.blocks[S.cover[o]])) water[region]++;
    for (const [dx, dz] of [[1, 0], [0, 1]]) { if (x + dx >= x0 + W || z + dz >= z0 + H) continue; const s = Math.abs(S.top[o] - S.top[o + dz * W + dx]); if (s > stepMax[region]) stepMax[region] = s; stepList[region].push(s); }
  }
  rec.inner_target_share_pct = pct(inner, innerTot); rec.inner_other_biomes = others; worstInner = Math.min(worstInner, rec.inner_target_share_pct);
  rec.ring_columns = ringTot; rec.ring_biomes_pct = Object.fromEntries(Object.entries(ringB).sort((a, b) => b[1] - a[1]).map(([b, c]) => [b, pct(c, ringTot)]));
  rec.ring_biomes_by_distance_pct = ringBins.map((bin, bi) => { const t = Object.values(bin).reduce((a, b) => a + b, 0); return { from: bi * T / 10, to: (bi + 1) * T / 10, biomes: Object.fromEntries(Object.entries(bin).map(([b, c]) => [b, pct(c, t)])) }; });
  const qt = quant(ellipseH, [0.05, 0.5, 0.95]); rec.ellipse_height_p5_p50_p95_max = [...qt, ellipseH.reduce((a, b) => a > b ? a : b, -999)];
  const sq = l => { const s = Int32Array.from(l).sort(); return s.length ? s[Math.floor(s.length * 0.999)] : 0; };
  rec.max_neighbour_step = { ellipse: stepMax.ellipse, ring: stepMax.ring, ellipse_p99_9: sq(stepList.ellipse), ring_p99_9: sq(stepList.ring) };
  rec.water_columns = water;
  // radial profile: east-going line from the centre, every 20 blocks to 450
  const prof = [];
  for (let d = 0; d <= 450; d += 20) {
    const x = i.cx + d, z = i.cz; if (x >= x0 + W || x < x0 || z < z0 || z >= z0 + H) break; const o = (z - z0) * W + (x - x0);
    const row = { r: d, q: +(((d / A) ** 2).toFixed(3)), beyond_edge: Math.round(d - A), y: S.top[o], block: hdr.blocks[S.surf[o]].replace('minecraft:', ''), biome: bname(S.bio[o]) };
    if (D) row.dune_y = D.top[o], row.above_dune = S.top[o] - D.top[o];
    prof.push(row);
  }
  rec.radial_east = prof;
  // height above the dune under the same columns (needs the dune-only scan)
  if (D) {
    const bins = {};
    for (let z = Math.floor(i.cz - B * REND - 2); z <= Math.ceil(i.cz + B * REND + 2); z++) for (let x = Math.floor(i.cx - A * REND - 2); x <= Math.ceil(i.cx + A * REND + 2); x++) {
      if (x < x0 || x >= x0 + W || z < z0 || z >= z0 + H) continue; const o = (z - z0) * W + (x - x0); if (who[o] !== k) continue;
      const q = qmin[o]; if (q > QEND) continue;
      const b = q <= 1 ? -1 : Math.min(19, Math.floor(dist(q) / 10)); (bins[b] = bins[b] || []).push(S.top[o] - D.top[o]);
    }
    rec.above_dune_by_distance = Object.fromEntries(Object.keys(bins).sort((a, b) => a - b).map(b => [b < 0 ? 'inside' : `${b * 10}-${b * 10 + 10}`, (() => { const a = bins[b]; return { mean: +(a.reduce((p, c) => p + c, 0) / a.length).toFixed(1), p5: quant(a, [0.05])[0], p95: quant(a, [0.95])[0] }; })()]));
    const far = Object.entries(rec.above_dune_by_distance).filter(([, v]) => v.mean > 5).map(([b]) => b);
    rec.still_more_than_5_above_dune_out_to = far.length ? far[far.length - 1] : 'only inside the ellipse';
  }
  R.islands[i.biome] = rec;
  // biome boundary (sharp) / outer extent of the target biome along 360 rays from the centre
  const rr = [];
  for (let a = 0; a < 360; a++) {
    const t = a * Math.PI / 180; let last = -1;
    for (let r = 0; r < 700; r += 1) { const x = Math.round(i.cx + r * Math.cos(t)), z = Math.round(i.cz + r * Math.sin(t)); if (x < x0 || x >= x0 + W || z < z0 || z >= z0 + H) break; const o = (z - z0) * W + (x - x0); if (S.bio[o] === ti) last = r; else if (qmin[o] > 1 && r > 0 && qmin[o] > QEND) break; }
    const px = i.cx + last * Math.cos(t), pz = i.cz + last * Math.sin(t); rr.push(last < 0 ? null : dist(((px - i.cx) / A) ** 2 + ((pz - i.cz) / B) ** 2));
  }
  const ok = rr.filter(v => v !== null), mean = ok.reduce((a, b) => a + b, 0) / ok.length, sd = Math.sqrt(ok.reduce((a, b) => a + (b - mean) ** 2, 0) / ok.length);
  rec.biome_outer_boundary_beyond_edge = { mean: +mean.toFixed(1), sd: +sd.toFixed(2), min: +Math.min(...ok).toFixed(1), max: +Math.max(...ok).toFixed(1), rays: ok.length };
  // extent of the target biome through the centre, and the centroid of all its columns near the island
  let x1 = 1e9, x2 = -1e9, z1 = 1e9, z2 = -1e9, sx = 0, sz = 0, cn = 0;
  for (let z = Math.floor(i.cz - B * REND); z <= Math.ceil(i.cz + B * REND); z++) for (let x = Math.floor(i.cx - A * REND); x <= Math.ceil(i.cx + A * REND); x++) {
    if (x < x0 || x >= x0 + W || z < z0 || z >= z0 + H) continue; const o = (z - z0) * W + (x - x0); if (who[o] !== k || S.bio[o] !== ti) continue;
    sx += x; sz += z; cn++; if (z === i.cz) { x1 = Math.min(x1, x); x2 = Math.max(x2, x); } if (x === i.cx) { z1 = Math.min(z1, z); z2 = Math.max(z2, z); }
  }
  rec.target_biome_extent_through_centre = { east_west: x2 - x1 + 1, north_south: z2 - z1 + 1 }; rec.target_biome_centroid = [+(sx / cn).toFixed(1), +(sz / cn).toFixed(1)]; rec.configured_centre = [i.cx, i.cz];
}
// ---- dunes: columns further than edge + 200 + 20 from every island
{
  const di = hdr.biomes.indexOf(DUNE_BIOME); let tot = 0, des = 0, sand = 0, voidc = 0, wat = 0; const other = {}, hs = [];
  for (let o = 0; o < n; o++) if (qmin[o] > QOUT) {
    tot++; hs.push(S.top[o]); if (S.bio[o] === di) des++; else { const b = bname(S.bio[o]); other[b] = (other[b] || 0) + 1; }
    if (hdr.blocks[S.surf[o]] === 'minecraft:sand') sand++; if (S.vd[o]) voidc++; if (/water/.test(hdr.blocks[S.cover[o]])) wat++;
  }
  R.dunes = { columns: tot, dune_biome_share_pct: pct(des, tot), other_biomes: other, top_block_sand_pct: pct(sand, tot), columns_with_void_below_top: voidc, water_columns: wat, height_above_sand_p5_p50_p95_max: [...quant(hs, [0.05, 0.5, 0.95]).map(v => v - SEA), hs.reduce((a, b) => a > b ? a : b, -999) - SEA] };
}
// ---- holes in the ring: void columns (open overhangs and sealed pockets)
{
  const v = new Int32Array(fs.readFileSync(scanPrefix + '.vox').buffer.slice(0)), N = v.length / 3, key = (x, y, z) => (y + 64) * n + z * W + x;
  const set = new Map(); for (let i = 0; i < N; i++) set.set(key(v[i * 3], v[i * 3 + 1], v[i * 3 + 2]), i);
  const open = new Uint8Array(N), st = [], Dd = [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]];
  const outside = (x, y, z) => x < 0 || z < 0 || x >= W || z >= H || y > S.top[z * W + x];
  for (let i = 0; i < N; i++) for (const d of Dd) if (outside(v[i * 3] + d[0], v[i * 3 + 1] + d[1], v[i * 3 + 2] + d[2])) { open[i] = 1; st.push(i); break; }
  while (st.length) { const i = st.pop(); for (const d of Dd) { const j = set.get(key(v[i * 3] + d[0], v[i * 3 + 1] + d[1], v[i * 3 + 2] + d[2])); if (j !== undefined && !open[j]) { open[j] = 1; st.push(j); } } }
  const per = {}; let sealed = 0; const where = [];
  for (let i = 0; i < N; i++) { const o = v[i * 3 + 2] * W + v[i * 3], q = qmin[o], area = q <= 1 ? 'ellipse' : q <= QEND ? 'ring' : 'dunes'; const kk = (who[o] >= 0 && q <= QEND ? islands[who[o]].biome : 'dunes') + ':' + area + (open[i] ? ':open' : ':sealed'); per[kk] = (per[kk] || 0) + 1; if (!open[i]) { sealed++; if (where.length < 10) where.push([v[i * 3] + x0, v[i * 3 + 1], v[i * 3 + 2] + z0]); } }
  const vcol = {}; for (let o = 0; o < n; o++) if (S.vd[o]) { const q = qmin[o], kk = (who[o] >= 0 && q <= QEND ? islands[who[o]].biome : 'dunes') + ':' + (q <= 1 ? 'ellipse' : q <= QEND ? 'ring' : 'dunes'); vcol[kk] = (vcol[kk] || 0) + 1; }
  R.caves = { void_blocks: N, sealed_void_blocks: sealed, sealed_examples: where, void_blocks_by_area: per, columns_with_void_below_top_by_area: vcol };
}
// ---- census, spawn
{ const c = hdr.census || {}; R.census = { ores_total: Object.entries(c).filter(([k]) => /_ore$/.test(k)).reduce((a, [, v]) => a + v, 0), fluids: Object.fromEntries(Object.entries(c).filter(([k]) => /water|lava/.test(k))) }; R.biome_sections = hdr.biomeSections; }
R.worst_inner_target_share_pct = worstInner;

// ---- pictures
const COL = { arrakis_desert: [230, 213, 138], windswept_hills: [79, 111, 82], windswept_gravelly_hills: [143, 143, 143], ice_spikes: [159, 230, 255], jagged_peaks: [255, 255, 255], stony_peaks: [106, 106, 138], windswept_savanna: [200, 162, 50], badlands: [196, 85, 45], eroded_badlands: [138, 58, 42] };
const GLY = { A: '010101111101101', B: '110101110101110', C: '011100100100011', D: '110101101101110', E: '111100110100111', F: '111100110100100', G: '011100101101011', H: '101101111101101', I: '111010010010111', J: '001001001101010', K: '101101110101101', L: '100100100100111', M: '101111111101101', N: '110101101101101', O: '010101101101010', P: '110101110100100', Q: '010101101110011', R: '110101110101101', S: '011100010001110', T: '111010010010010', U: '101101101101111', V: '101101101101010', W: '101101111111101', X: '101101010101101', Y: '101101010010010', Z: '111001010100111',
  0: '111101101101111', 1: '010110010010111', 2: '110001010100111', 3: '110001010001110', 4: '101101111001001', 5: '111100110001110', 6: '011100111101111', 7: '111001010010010', 8: '111101111101111', 9: '111101111001110', _: '000000000000111', '-': '000000111000000', '.': '000000000000010', ':': '000010000010000', ' ': '000000000000000', '+': '000010111010000', '=': '000111000111000' };
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
const PW = Math.floor(W / MPP), PH = Math.floor(H / MPP);
function overlay(img, w) {   // thin lines: edge (black), edge + 100 (red), edge + 200 (grey)
  const rings = [[1, [0, 0, 0]], [(1 + 100 / RM) ** 2, [200, 0, 0]], [QEND, [110, 110, 110]]];
  for (const i of islands) for (const [q, c] of rings) for (let a = 0; a < 3600; a++) {
    const t = a * Math.PI / 1800, x = i.cx + A * Math.sqrt(q) * Math.cos(t), z = i.cz + B * Math.sqrt(q) * Math.sin(t), px = Math.floor((x - x0) / MPP), py = Math.floor((z - z0) / MPP);
    if (px < 0 || py < 0 || px >= PW || py >= PH) continue; const o = (py * w + px) * 3; img[o] = c[0]; img[o + 1] = c[1]; img[o + 2] = c[2];
  }
}
{
  const LEG = 70, h = PH + LEG, img = new Uint8Array(PW * h * 3).fill(255);
  for (let py = 0; py < PH; py++) for (let px = 0; px < PW; px++) { const k = (py * MPP + (MPP >> 1)) * W + px * MPP + (MPP >> 1), c = COL[bname(S.bio[k])] || [255, 0, 255], o = (py * PW + px) * 3; img[o] = c[0]; img[o + 1] = c[1]; img[o + 2] = c[2]; }
  overlay(img, PW);
  text(img, PW, 'BIOME MAP ' + MPP + ' BLOCKS PER PIXEL  X EAST Z SOUTH  ORIGIN ' + x0 + ' ' + z0 + '  LINES: BLACK = ELLIPSE EDGE  RED = EDGE+100  GREY = EDGE+200', 8, PH + 8, [0, 0, 0], 2);
  Object.keys(COL).forEach((nm, i) => { const cx = 8 + (i % 5) * 200, cy = PH + 28 + Math.floor(i / 5) * 16; for (let a = 0; a < 12; a++) for (let b = 0; b < 12; b++) { const o = ((cy + b) * PW + cx + a) * 3; img[o] = COL[nm][0]; img[o + 1] = COL[nm][1]; img[o + 2] = COL[nm][2]; } text(img, PW, nm.replace('windswept_', 'WS_').replace('arrakis_', 'DUNE_'), cx + 16, cy + 1, [0, 0, 0], 1); });
  writePng(outPrefix + '_biomes.png', PW, h, img);
}
{
  const hh = new Float32Array(PW * PH);
  for (let py = 0; py < PH; py++) for (let px = 0; px < PW; px++) { let s = 0; for (let a = 0; a < MPP; a++) for (let b = 0; b < MPP; b++) s += S.top[(py * MPP + a) * W + px * MPP + b]; hh[py * PW + px] = s / (MPP * MPP); }
  let lo = 1e9, hi = -1e9; for (const v of hh) { if (v < lo) lo = v; if (v > hi) hi = v; }
  const img = new Uint8Array(PW * (PH + 30) * 3).fill(255);
  for (let py = 0; py < PH; py++) for (let px = 0; px < PW; px++) {
    const v = hh[py * PW + px], l = hh[py * PW + Math.max(0, px - 1)], u = hh[Math.max(0, py - 1) * PW + px], r = hh[py * PW + Math.min(PW - 1, px + 1)], d = hh[Math.min(PH - 1, py + 1) * PW + px];
    const gx = (r - l) / (2 * MPP), gz = (d - u) / (2 * MPP), sh = Math.max(0.15, Math.min(1.5, 0.75 + 2.2 * (-gx - gz))), t = (v - lo) / (hi - lo), o = (py * PW + px) * 3;
    const c = v < SEA - 0.5 ? [40, 80, 160] : [100 + 140 * t, 110 + 120 * t, 70 + 120 * t];
    for (let i = 0; i < 3; i++) img[o + i] = Math.max(0, Math.min(255, c[i] * sh));
  }
  overlay(img, PW);
  text(img, PW, 'HEIGHT HILLSHADE ' + MPP + ' BLOCKS PER PIXEL  MIN Y ' + Math.round(lo) + ' MAX Y ' + Math.round(hi) + '  BLUE = BELOW SEA LEVEL 63', 8, PH + 8, [0, 0, 0], 2);
  writePng(outPrefix + '_height.png', PW, PH + 30, img);
}
// ---- radial tables
{
  let md = '# Radial profile per island (east-going line from the centre, every 20 blocks)\n\nscan: ' + scanPrefix + '\n';
  for (const [name, rec] of Object.entries(R.islands)) {
    md += `\n## ${name}\n\n| r from centre | beyond edge | surface y | ${D ? 'dune-only y | above dune | ' : ''}surface block | biome |\n|---|---|---|${D ? '---|---|' : ''}---|---|\n`;
    for (const p of rec.radial_east) md += `| ${p.r} | ${p.beyond_edge} | ${p.y} | ${D ? `${p.dune_y} | ${p.above_dune} | ` : ''}${p.block} | ${p.biome} |\n`;
  }
  fs.writeFileSync(outPrefix + '_radial.md', md);
}
fs.writeFileSync(outPrefix + '_report.json', JSON.stringify(R, null, 1));
console.log(JSON.stringify({ worst_inner: worstInner, dunes: R.dunes, caves: { void_blocks: R.caves.void_blocks, sealed: R.caves.sealed_void_blocks } }, null, 1));
