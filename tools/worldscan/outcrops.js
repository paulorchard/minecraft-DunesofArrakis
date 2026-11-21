#!/usr/bin/env node
// outcrops: per-circle outcrop metrics (prompt 16) from a gridscan.js dump of the generated world and a dune-only scan of the same seed
// (the dune terrain does not depend on the islands, so a dune-only scan made earlier gives the exact dune surface under every circle).
//   node tools/worldscan/outcrops.js <scanPrefix> <duneOnlyScanPrefix> [outJson] [prompt15ScanPrefix]
// Columns where the generated surface is higher than the dune surface are "rock columns". Reports per circle: highest block above sand level (63),
// share of rock columns, p50 of rock height above the dune, largest neighbour step, mean slope of the rock, the highest dune inside the circle and
// within 100 blocks around it, biome / surface-block agreement, and (with a prompt-15 scan) the number of differing dune columns outside the circles.
const fs = require('fs'), path = require('path');
const [scanP, duneP, outJson, p15] = process.argv.slice(2);
const grid = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'rewrite', 'grid.json'), 'utf8'));
const NS = grid.namespace, SAND = 63, R = grid.diameter_x / 2;
const islands = grid.islands.map(p => Object.assign({ cx: grid.origin_x + grid.pitch * p.col, cz: grid.origin_z + grid.pitch * p.row }, p));
const load = p => { const h = JSON.parse(fs.readFileSync(p + '.json', 'utf8')), n = h.width * h.height, b = fs.readFileSync(p + '.bin');
  const pl = k => b.subarray(n * (1 + k), n * (2 + k));
  return { h, n, top: new Int16Array(b.buffer.slice(b.byteOffset, b.byteOffset + n * 2)), bio: pl(1), surf: pl(2), vd: pl(4), at: (x, z) => (z - h.z0) * h.width + (x - h.x0) }; };
const S = load(scanP), D = load(duneP), P = p15 ? load(p15) : null;
const bn = i => S.h.biomes[i].replace(NS + ':', ''), sn = i => S.h.blocks[i].replace('minecraft:', '');
const quant = (a, q) => { const s = Float64Array.from(a).sort(); return s.length ? s[Math.min(s.length - 1, Math.floor(q * s.length))] : null; };
const out = { scan: scanP, dune_only: duneP, circles: {} };
for (const i of islands) {
  const rec = {}, inC = [], rockH = [], slopes = [], around = []; let rock = 0, tot = 0, rockTarget = 0, sandCols = 0, sandOk = 0, maxTop = -999, maxDuneIn = -999, edgeStep = 0, stepMax = 0, voidCols = 0;
  for (let z = i.cz - 4 * R; z <= i.cz + 4 * R; z++) for (let x = i.cx - 4 * R; x <= i.cx + 4 * R; x++) {
    if (x < S.h.x0 + 1 || z < S.h.z0 + 1 || x >= S.h.x0 + S.h.width - 1 || z >= S.h.z0 + S.h.height - 1) continue;
    const q = ((x - i.cx) ** 2 + (z - i.cz) ** 2) / (R * R), o = S.at(x, z), od = D.at(x, z), dtop = D.top[od] ;
    if (q > 9) continue;
    if (q >= 1) { around.push(dtop - SAND); continue; }
    tot++; const t = S.top[o]; if (t - SAND > maxTop) maxTop = t - SAND; if (dtop - SAND > maxDuneIn) maxDuneIn = dtop - SAND;
    if (S.vd[o]) voidCols++;
    const dx = S.top[o + 1] - S.top[o - 1], dz = S.top[o + S.h.width] - S.top[o - S.h.width];
    for (const [a, b] of [[o, o + 1], [o, o + S.h.width]]) stepMax = Math.max(stepMax, Math.abs(S.top[a] - S.top[b]));
    if (t > dtop) { rock++; rockH.push(t - dtop); slopes.push(Math.hypot(dx, dz) / 2); if (bn(S.bio[o]) === i.biome) rockTarget++; }
    else { sandCols++; if (bn(S.bio[o]) === grid.desert.biome && sn(S.surf[o]) === 'sand') sandOk++; }
  }
  // the largest step across the circle's edge
  for (let a = 0; a < 3600; a++) { const t = a * Math.PI / 1800, x1 = Math.round(i.cx + (R - 1) * Math.cos(t)), z1 = Math.round(i.cz + (R - 1) * Math.sin(t)), x2 = Math.round(i.cx + (R + 1) * Math.cos(t)), z2 = Math.round(i.cz + (R + 1) * Math.sin(t)); edgeStep = Math.max(edgeStep, Math.abs(S.top[S.at(x1, z1)] - S.top[S.at(x2, z2)])); }
  rec.highest_block_above_sand_level = maxTop; rec.rock_columns_pct = +(100 * rock / tot).toFixed(1);
  rec.rock_height_above_dune_p50 = quant(rockH, 0.5); rec.rock_height_above_dune_p95 = quant(rockH, 0.95); rec.largest_neighbour_step_in_circle = stepMax; rec.edge_step_across_circle_edge = edgeStep;
  rec.mean_slope_of_rock_blocks_per_block = +(slopes.reduce((a, b) => a + b, 0) / (slopes.length || 1)).toFixed(2);
  rec.highest_dune_inside_circle_above_sand = maxDuneIn; rec.highest_dune_within_100_around_above_sand = Math.max(...around);
  rec.rock_columns_with_island_biome_pct = +(100 * rockTarget / (rock || 1)).toFixed(1); rec.sand_covered_columns_with_dune_biome_and_sand_pct = +(100 * sandOk / (sandCols || 1)).toFixed(1);
  rec.columns_with_air_below_surface = voidCols; rec.buried = rec.rock_columns_pct < 40;
  out.circles[i.biome] = rec;
}
const hs = Object.values(out.circles).map(c => c.highest_block_above_sand_level);
out.highest_points = { min: Math.min(...hs), max: Math.max(...hs), ratio: +(Math.max(...hs) / Math.min(...hs)).toFixed(2) };
if (P) {   // dune columns outside every circle (more than 6 blocks) against the prompt-15 scan
  let diff = 0, cnt = 0;
  for (let z = S.h.z0; z < S.h.z0 + S.h.height; z++) for (let x = S.h.x0; x < S.h.x0 + S.h.width; x++) {
    if (islands.some(i => ((x - i.cx) ** 2 + (z - i.cz) ** 2) < (R + 6) ** 2)) continue; cnt++; if (S.top[S.at(x, z)] !== P.top[P.at(x, z)]) diff++;
  }
  out.dunes_vs_prompt15 = { columns_compared: cnt, differing_columns: diff };
}
if (outJson) fs.writeFileSync(outJson, JSON.stringify(out, null, 1));
for (const [n, c] of Object.entries(out.circles)) console.log(n.padEnd(26), JSON.stringify(c));
console.log(JSON.stringify({ highest_points: out.highest_points, dunes_vs_prompt15: out.dunes_vs_prompt15 }));
