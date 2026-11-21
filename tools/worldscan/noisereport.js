#!/usr/bin/env node
// noisereport: statistics and pictures for the noise islands (prompt 17) from a gridscan.js dump of the generated world.
//   node tools/worldscan/noisereport.js <scanPrefix> <outDir> [mpp=2]
// A column is "rock" where the top terrain block is not plain sand (the dune surface rule makes every dune-biome column sand; the island biomes
// have no plain sand surface).  Islands are the 4-connected components of rock cells on the 4-block biome grid (cell = rock if more than half
// of its 16 columns are rock).  Roundness = perimeter^2 / (4 pi area) with perimeter = boundary edges * pi / 4 (a digital circle gives about 1.0).
const fs = require('fs'), zlib = require('zlib'), path = require('path');
const [scanP, outDir, MPP = '2'] = process.argv.slice(2);
const mpp = +MPP; fs.mkdirSync(outDir, { recursive: true });
const NS = 'islandcraft_dunesofarrakis', SAND = 63, CAP = 100;
const H = JSON.parse(fs.readFileSync(scanP + '.json', 'utf8')), W = H.width, HT = H.height, n = W * HT, b = fs.readFileSync(scanP + '.bin');
const top = new Int16Array(b.buffer.slice(b.byteOffset, b.byteOffset + 2 * n)), pl = k => b.subarray(n * (2 + k), n * (3 + k));
const bio = pl(0), surf = pl(1), cover = pl(2), vd = pl(3), plant = pl(4);
const bn = i => H.biomes[i].replace(NS + ':', ''), sn = i => H.blocks[i].replace('minecraft:', '');
const sandIdx = H.blocks.indexOf('minecraft:sand'), desertIdx = H.biomes.indexOf(NS + ':arrakis_desert');
const rock = new Uint8Array(n); let rockCols = 0;
for (let k = 0; k < n; k++) if (surf[k] !== sandIdx) { rock[k] = 1; rockCols++; }
const CW = Math.floor(W / 4), CH = Math.floor(HT / 4), cell = new Uint8Array(CW * CH);
for (let cz = 0; cz < CH; cz++) for (let cx = 0; cx < CW; cx++) { let s = 0; for (let z = 0; z < 4; z++) for (let x = 0; x < 4; x++) s += rock[(cz * 4 + z) * W + cx * 4 + x]; cell[cz * CW + cx] = s > 8 ? 1 : 0; }
const lab = new Int32Array(CW * CH), comps = [], stack = new Int32Array(CW * CH);
for (let s = 0; s < CW * CH; s++) if (cell[s] && !lab[s]) {
  const id = comps.length + 1; let sp = 0, cells = 0, edges = 0, sx = 0, sz = 0, mnx = 1e9, mxx = -1, mnz = 1e9, mxz = -1; stack[sp++] = s; lab[s] = id;
  while (sp) { const p = stack[--sp], px = p % CW, pz = (p / CW) | 0; cells++; sx += px; sz += pz; mnx = Math.min(mnx, px); mxx = Math.max(mxx, px); mnz = Math.min(mnz, pz); mxz = Math.max(mxz, pz);
    for (const [qx, qz] of [[px - 1, pz], [px + 1, pz], [px, pz - 1], [px, pz + 1]]) { if (qx < 0 || qz < 0 || qx >= CW || qz >= CH) { edges++; continue; } const q = qz * CW + qx; if (!cell[q]) edges++; else if (!lab[q]) { lab[q] = id; stack[sp++] = q; } } }
  comps.push({ id, cells, edges, cx: H.x0 + 4 * sx / cells, cz: H.z0 + 4 * sz / cells, diag: 4 * Math.hypot(mxx - mnx + 1, mxz - mnz + 1), touchesEdge: mnx === 0 || mnz === 0 || mxx === CW - 1 || mxz === CH - 1 });
}
for (const c of comps) { c.area = c.cells * 16; c.round = +((c.edges * 4 * Math.PI / 4) ** 2 / (4 * Math.PI * c.area)).toFixed(2); }
const isl = comps.filter(c => c.area >= 64).sort((p, q) => q.area - p.area);
// per island: highest block, biomes, coast step
const per = new Map(isl.map(c => [c.id, { top: -999, bio: {}, cols: 0 }]));
for (let cz = 0; cz < CH; cz++) for (let cx = 0; cx < CW; cx++) { const id = lab[cz * CW + cx]; if (!id || !per.has(id)) continue; const P = per.get(id);
  for (let z = 0; z < 4; z++) for (let x = 0; x < 4; x++) { const k = (cz * 4 + z) * W + cx * 4 + x; if (!rock[k]) continue; P.cols++; if (top[k] - SAND > P.top) P.top = top[k] - SAND; const nm = bn(bio[k]); P.bio[nm] = (P.bio[nm] || 0) + 1; } }
let coastStep = 0, coastStepSum = 0, coastN = 0;
for (let z = 1; z < HT - 1; z++) for (let x = 1; x < W - 1; x++) { const k = z * W + x; for (const o of [1, W]) if (rock[k] !== rock[k + o]) { const s = Math.abs(top[k] - top[k + o]); coastN++; coastStepSum += s; if (s > coastStep) coastStep = s; } }
const out = { scan: scanP, window: { x0: H.x0, z0: H.z0, size: [W, HT], chunks: H.chunks }, rock_columns: rockCols, land_share: +(rockCols / n).toFixed(4) };
out.islands_ge_64_columns = isl.length;
const hist = [0, 0, 0, 0]; for (const c of isl) hist[c.area < 5000 ? 0 : c.area < 50000 ? 1 : c.area < 500000 ? 2 : 3]++;
out.size_histogram_columns = { 'under_5k': hist[0], '5k_to_50k': hist[1], '50k_to_500k': hist[2], 'over_500k': hist[3] };
out.largest = isl.slice(0, 6).map(c => ({ area: c.area, longest_axis_bbox_diagonal: Math.round(c.diag), roundness: c.round, centre: [Math.round(c.cx), Math.round(c.cz)], touches_window_edge: c.touchesEdge }));
const big = isl.filter(c => c.area >= 50000 && !c.touchesEdge), bigAll = isl.filter(c => c.area >= 50000), small = isl.filter(c => c.area >= 500 && c.area < 50000);
const med = a => { a = a.slice().sort((p, q) => p - q); return a.length ? a[a.length >> 1] : null; };
out.roundness = { big_islands_ge_50k_all: { n: bigAll.length, median: med(bigAll.map(c => c.round)), min: Math.min(...bigAll.map(c => c.round)) }, big_islands_not_touching_the_window_edge: { n: big.length, median: med(big.map(c => c.round)), min: big.length ? Math.min(...big.map(c => c.round)) : null },
  small_500_to_50k: { n: small.length, median: med(small.map(c => c.round)) } };
// spawn island: the component under the origin (or the nearest one)
const oc = lab[Math.floor((0 - H.z0) / 4) * CW + Math.floor((0 - H.x0) / 4)];
out.origin = { is_rock: !!oc, island: oc ? (c => ({ area: c.area, diagonal: Math.round(c.diag), roundness: c.round, centre: [Math.round(c.cx), Math.round(c.cz)] }))(comps[oc - 1]) : null, top_above_sand: top[(0 - H.z0) * W + (0 - H.x0)] - SAND };
// small-island chains: clusters of islands (500 <= area < 50,000) linked at 300 blocks between centroids
const sm = isl.filter(c => c.area < 50000 && c.area >= 64), par = sm.map((_, i) => i), fd = i => { while (par[i] !== i) i = par[i] = par[par[i]]; return i; };
for (let i = 0; i < sm.length; i++) for (let j = i + 1; j < sm.length; j++) if (Math.hypot(sm[i].cx - sm[j].cx, sm[i].cz - sm[j].cz) <= 300) par[fd(i)] = fd(j);
const cl = new Map(); sm.forEach((_, i) => { const r = fd(i); cl.set(r, (cl.get(r) || []).concat([sm[i]])); });
const chains = [...cl.values()].filter(a => a.length >= 3).sort((p, q) => q.length - p.length);
out.satellites_under_50k = sm.length; out.chains_of_3_or_more = chains.length; out.chains_top = chains.slice(0, 5).map(a => ({ islands: a.length, centre: [Math.round(a.reduce((s, c) => s + c.cx, 0) / a.length), Math.round(a.reduce((s, c) => s + c.cz, 0) / a.length)] }));
// heights
const tops = isl.map(c => ({ area: c.area, top: per.get(c.id).top, centre: [Math.round(c.cx), Math.round(c.cz)] }));
out.highest_block_above_sand_overall = Math.max(...tops.map(t => t.top));
out.over_cap = tops.filter(t => t.top > CAP).length;
const five = tops.slice(0, 5); out.five_largest_tops = five.map(t => t.top); out.five_largest_top_ratio = +(Math.max(...five.map(t => t.top)) / Math.max(1, Math.min(...five.map(t => t.top)))).toFixed(2);
out.tallest_island = tops.slice().sort((p, q) => q.top - p.top)[0];
out.coast_step = { largest: coastStep, mean: +(coastStepSum / Math.max(1, coastN)).toFixed(2), boundary_pairs: coastN };
// oddities
let voidCols = 0, voidBlocks = 0, plantCols = 0, waterCols = 0;
for (let k = 0; k < n; k++) { if (vd[k]) { voidCols++; voidBlocks += vd[k]; } if (plant[k]) plantCols++; if (/water|lava/.test(sn(cover[k]))) waterCols++; }
out.oddities = { columns_with_air_below_surface: voidCols, air_blocks_below_surface_capped: voidBlocks, columns_with_water_or_lava_on_top: waterCols, columns_with_plants_leaves_logs_below_top: plantCols };
// biomes
const bc = {}, sandBio = {}; let sandCols = 0, sandOk = 0, rockOk = 0;
const dune = n_ => bn(n_) === 'arrakis_desert';
for (let k = 0; k < n; k++) { const nm = bn(bio[k]); if (rock[k]) { bc[nm] = (bc[nm] || 0) + 1; if (!dune(bio[k])) rockOk++; } else { sandCols++; if (dune(bio[k])) sandOk++; } }
out.rock_biome_share_pct = Object.fromEntries(Object.entries(bc).sort((p, q) => q[1] - p[1]).map(([k, v]) => [k, +(100 * v / rockCols).toFixed(1)]));
out.rock_columns_with_an_island_biome_pct = +(100 * rockOk / rockCols).toFixed(2); out.sand_columns_with_dune_biome_pct = +(100 * sandOk / sandCols).toFixed(2);
const multi = isl.filter(c => c.area >= 500).map(c => { const P = per.get(c.id); const k = Object.entries(P.bio).filter(([nm, v]) => nm !== 'arrakis_desert' && v >= 0.02 * P.cols).length; return { k, c }; });
out.islands_ge_500_columns = multi.length; out.islands_with_more_than_one_biome = multi.filter(m => m.k > 1).length; out.mean_biomes_per_island = +(multi.reduce((s, m) => s + m.k, 0) / Math.max(1, multi.length)).toFixed(2);
out.band_edge_examples = multi.filter(m => m.k > 1).sort((p, q) => q.c.area - p.c.area).slice(0, 5).map(m => ({ area: m.c.area, centre: [Math.round(m.c.cx), Math.round(m.c.cz)], biomes: per.get(m.c.id).bio }));
fs.writeFileSync(path.join(outDir, 'report.json'), JSON.stringify(out, null, 1));
console.log(JSON.stringify(out, null, 1));
// ---- pictures
function writePng(file, w, h, rgb) {
  const crcT = new Uint32Array(256); for (let k = 0; k < 256; k++) { let c = k; for (let j = 0; j < 8; j++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; crcT[k] = c >>> 0; }
  const crc = bb => { let c = 0xffffffff; for (const v of bb) c = crcT[(c ^ v) & 255] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
  const chunk = (t, d) => { const l = Buffer.alloc(4); l.writeUInt32BE(d.length); const td = Buffer.concat([Buffer.from(t), d]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([l, td, c]); };
  const raw = Buffer.alloc((w * 3 + 1) * h); for (let y = 0; y < h; y++) { raw[y * (w * 3 + 1)] = 0; rgb.copy(raw, y * (w * 3 + 1) + 1, y * w * 3, (y + 1) * w * 3); }
  const ih = Buffer.alloc(13); ih.writeUInt32BE(w, 0); ih.writeUInt32BE(h, 4); ih[8] = 8; ih[9] = 2;
  fs.writeFileSync(file, Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ih), chunk('IDAT', zlib.deflateSync(raw, { level: 6 })), chunk('IEND', Buffer.alloc(0))]));
}
const PW = Math.floor(W / mpp), PH = Math.floor(HT / mpp), land = Buffer.alloc(PW * PH * 3), shade = Buffer.alloc(PW * PH * 3), bmap = Buffer.alloc(PW * PH * 3);
const BCOL = { arrakis_desert: [40, 36, 30], windswept_hills: [120, 150, 100], windswept_gravelly_hills: [150, 150, 150], ice_spikes: [150, 220, 245], jagged_peaks: [235, 235, 245], stony_peaks: [140, 120, 110], windswept_savanna: [200, 170, 60], badlands: [200, 90, 40], eroded_badlands: [160, 40, 30] };
const topAt = (x, z) => top[Math.min(HT - 1, Math.max(0, z)) * W + Math.min(W - 1, Math.max(0, x))];
for (let py = 0; py < PH; py++) for (let px = 0; px < PW; px++) {
  const x = Math.floor(px * mpp), z = Math.floor(py * mpp), k = z * W + x, o = (py * PW + px) * 3;
  const l = rock[k] ? 255 : 0; land[o] = land[o + 1] = land[o + 2] = l;
  const dx = topAt(x + 2, z) - topAt(x - 2, z), dz = topAt(x, z + 2) - topAt(x, z - 2), sh = Math.max(0, Math.min(1, 0.55 + 0.12 * (-dx - dz))), base = rock[k] ? [170, 170, 170] : [215, 190, 130];
  for (let c = 0; c < 3; c++) shade[o + c] = Math.round(base[c] * (0.35 + 0.9 * sh) * Math.min(1, 0.7 + (top[k] - SAND) / 120));
  const bc3 = BCOL[bn(bio[k])] || [255, 0, 255]; for (let c = 0; c < 3; c++) bmap[o + c] = bc3[c];
}
writePng(path.join(outDir, 'land_mask.png'), PW, PH, land); writePng(path.join(outDir, 'hillshade.png'), PW, PH, shade); writePng(path.join(outDir, 'biomes.png'), PW, PH, bmap);
console.log('pictures: ' + PW + 'x' + PH + ' px at ' + mpp + ' blocks per pixel in ' + outDir);
