#!/usr/bin/env node
// Generates the island layer of the "Arrakis" world type from tools/rewrite/grid.json and vanilla's own 26.1.2 data.
//   node tools/rewrite/gen.js          (run tools/rewrite/extract-vanilla.sh once first: it fills build/vanilla and build/rw/params.json)
// The dune base is NOT generated here: it is the prompt-03 dune data (tools/dunes/generate.js, files arrakis_*).
// Writes under src/main/resources/data/<namespace>/ : island masks, the terrain and biome-sampler climate functions, a copy of
// vanilla's overworld density graph, the blended noise settings (two biome modes), eight biome copies and the world presets.
// Nothing under data/minecraft is overridden (the world-type list is a tag with replace:false, which merges).
const fs = require('fs'), path = require('path');
const ROOT = path.resolve(__dirname, '..', '..');
const grid = JSON.parse(fs.readFileSync(process.env.GRID_FILE || path.join(__dirname, 'grid.json'), 'utf8'));
const NS = grid.namespace;
const VAN = path.join(ROOT, 'build/vanilla/data/minecraft/worldgen');
const RES = path.join(ROOT, 'src/main/resources');
const OUT = path.join(RES, 'data', NS);
const readV = rel => JSON.parse(fs.readFileSync(path.join(VAN, rel), 'utf8'));
function write(rel, obj, base = OUT) {
  const p = path.join(base, rel); fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, JSON.stringify(obj, null, 2) + '\n');
}
// remove only what this script generates (the dune files are not ours to delete)
for (const d of ['island', 'overworld', 'biome_native', 'biome_sharp', 'patch']) fs.rmSync(path.join(OUT, 'worldgen/density_function', d), { recursive: true, force: true });
for (const f of ['arrakis', 'arrakis_native', 'arrakis_sharp', 'arrakis_test']) { fs.rmSync(path.join(OUT, 'worldgen/noise_settings', f + '.json'), { force: true }); fs.rmSync(path.join(OUT, 'worldgen/world_preset', f + '.json'), { force: true }); }
const keepBiome = new Set(['arrakis_desert.json']);
if (fs.existsSync(path.join(OUT, 'worldgen/biome'))) for (const f of fs.readdirSync(path.join(OUT, 'worldgen/biome'))) if (!keepBiome.has(f)) fs.rmSync(path.join(OUT, 'worldgen/biome', f));

// ---- geometry
const A = grid.diameter_x / 2, B = grid.diameter_z / 2, T = grid.transition, RM = (A + B) / 2;
const REND = 1 + T / RM, QEND = REND * REND;                 // normalised radius and squared normalised radius where the transition ends
const distOfQ = q => (Math.sqrt(q) - 1) * RM;                // the radial distance beyond the edge, in blocks
const qOfDist = d => (1 + d / RM) * (1 + d / RM);
const islands = grid.islands.map(p => Object.assign({ cx: grid.origin_x + grid.pitch * p.col, cz: grid.origin_z + grid.pitch * p.row }, p));
const MODE = grid.biome_transition;
if (!['native', 'sharp'].includes(MODE)) throw new Error('biome_transition must be native or sharp');
// refuse overlapping transition zones: sample each zone boundary and test it against every other zone
const qAt = (i, x, z) => ((x - i.cx) / A) ** 2 + ((z - i.cz) / B) ** 2;
let minGap = Infinity;
for (const i of islands) for (const j of islands) {
  if (i === j) continue;
  for (let k = 0; k < 7200; k++) {
    const t = 2 * Math.PI * k / 7200, x = i.cx + A * Math.sqrt(QEND) * Math.cos(t), z = i.cz + B * Math.sqrt(QEND) * Math.sin(t);
    if (qAt(j, x, z) < QEND) throw new Error(`transition zones of ${i.biome} and ${j.biome} overlap: pitch ${grid.pitch} is too small for diameters ${grid.diameter_x} x ${grid.diameter_z} and transition ${T}`);
  }
  if (i.row === j.row && Math.abs(i.col - j.col) === 1) minGap = Math.min(minGap, Math.hypot(i.cx - j.cx, 0) - 2 * A * Math.sqrt(QEND));
  if (i.col === j.col && Math.abs(i.row - j.row) === 1) minGap = Math.min(minGap, Math.hypot(0, i.cz - j.cz) - 2 * B * Math.sqrt(QEND));
}
// worst error of the radial-distance approximation against the true distance to the ellipse, over the whole transition
function trueDist(px, pz) {   // px, pz relative to the centre, outside the ellipse
  let best = Infinity, bt = 0;
  for (let k = 0; k < 720; k++) { const t = Math.PI * 2 * k / 720, d = Math.hypot(px - A * Math.cos(t), pz - B * Math.sin(t)); if (d < best) { best = d; bt = t; } }
  let lo = bt - Math.PI / 360, hi = bt + Math.PI / 360;
  for (let it = 0; it < 40; it++) {
    const m1 = lo + (hi - lo) / 3, m2 = hi - (hi - lo) / 3;
    const f = t => Math.hypot(px - A * Math.cos(t), pz - B * Math.sin(t));
    if (f(m1) < f(m2)) hi = m2; else lo = m1;
  }
  return Math.hypot(px - A * Math.cos((lo + hi) / 2), pz - B * Math.sin((lo + hi) / 2));
}
let worstErr = 0, worstAt = null;
for (let k = 0; k < 360; k++) for (let m = 1; m <= 40; m++) {
  const t = 2 * Math.PI * k / 360, r = 1 + (REND - 1) * m / 40, px = A * r * Math.cos(t), pz = B * r * Math.sin(t);
  const err = distOfQ(r * r) - trueDist(px, pz);
  if (Math.abs(err) > Math.abs(worstErr)) { worstErr = err; worstAt = { angle_deg: +(t * 180 / Math.PI).toFixed(0), approx: +distOfQ(r * r).toFixed(1), true: +trueDist(px, pz).toFixed(1) }; }
}

// ---- density function node helpers
const add = (a, b) => ({ type: 'minecraft:add', argument1: a, argument2: b });
const mul = (a, b) => ({ type: 'minecraft:mul', argument1: a, argument2: b });
const sq = a => ({ type: 'minecraft:square', argument: a });
const flat = a => ({ type: 'minecraft:flat_cache', argument: a });
const c2d = a => ({ type: 'minecraft:cache_2d', argument: a });
const rc = (input, lo, hi, inr, outr) => ({ type: 'minecraft:range_choice', input, min_inclusive: lo, max_exclusive: hi, when_in_range: inr, when_out_of_range: outr });
const BX = { type: NS + ':block_x' }, BZ = { type: NS + ':block_z' };
const r9 = v => +v.toFixed(9);
// normalised squared distance q = ((x-cx)/A)^2 + ((z-cz)/B)^2 from squares of block_x, block_z (no square root in vanilla)
const qFn = i => add(mul(1 / (A * A), sq(add(BX, -i.cx))), mul(1 / (B * B), sq(add(BZ, -i.cz))));
// a monotone profile over distance d (blocks beyond the edge) -> spline over q with Hermite derivatives
function splineOver(i, pts) {   // pts: [{d, value, dvalue_dd}]
  return { type: 'minecraft:spline', spline: { coordinate: qFn(i), points: pts.map(p => {
    const q = qOfDist(p.d), dqdd = 2 * (1 + p.d / RM) / RM;     // dq/dd
    return { location: r9(q), value: r9(p.value), derivative: r9(p.dvalue_dd / dqdd) };
  }) } };
}
const smooth = s => s * s * (3 - 2 * s), dsmooth = s => 6 * s * (1 - s);
// terrain mask: full inside the ellipse, smoothstep to 0 at edge + transition
function terrainPts() {
  const pts = []; const N = 40;
  for (let k = 0; k <= N; k++) { const d = T * k / N, s = 1 - d / T; pts.push({ d, value: smooth(s), dvalue_dd: -dsmooth(s) / T }); }
  return pts;
}
// sharp biome mask: full out to edge + sharp_edge_fraction*transition, then a short smoothstep ramp to 0
function sharpPts() {
  const E = grid.sharp_edge_fraction * T, R = grid.sharp_ramp, pts = [{ d: 0, value: 1, dvalue_dd: 0 }]; const N = 10;
  for (let k = 0; k <= N; k++) { const d = E + R * k / N, s = 1 - k / N; pts.push({ d, value: smooth(s), dvalue_dd: -dsmooth(s) / R }); }
  return pts;
}
const DF = 'worldgen/density_function/';
// transition 0 / ramp 0: a true step at the circle (1 where the squared normalised distance q < 1, else 0), no degenerate spline.
const stepMask = i => rc(qFn(i), 0.0, 1.0, 1.0, 0.0);
for (const i of islands) {
  write(`${DF}island/w_${i.biome}.json`, T > 0 ? splineOver(i, terrainPts()) : stepMask(i));
  write(`${DF}island/wb_${i.biome}.json`, (T > 0 && grid.sharp_ramp > 0) ? splineOver(i, sharpPts()) : stepMask(i));
}
// total terrain mask, exact per block (cache_2d, never flat_cache). The zones never overlap (checked above), so instead of summing all
// eight masks it picks the one island whose bounding box holds the block: a nested range_choice on block_x (columns), then block_z (rows).
// A dune column then costs about four comparisons, not eight splines (the terrain graph re-evaluates this once per vertical cell).
const pick = (coord, lo, hi, inner, outer) => rc(coord, Math.floor(lo), Math.ceil(hi) + 1, inner, outer);
const cols = [...new Set(islands.map(i => i.col))].sort((p, q) => p - q);
let tree = 0.0;
for (const c of cols.slice().reverse()) {
  const inCol = islands.filter(i => i.col === c).sort((p, q) => p.row - q.row);
  let rowTree = 0.0;
  for (const i of inCol.slice().reverse()) rowTree = pick(BZ, i.cz - B * REND, i.cz + B * REND, `${NS}:island/w_${i.biome}`, rowTree);
  tree = pick(BX, inCol[0].cx - A * REND, inCol[0].cx + A * REND, rowTree, tree);
}
write(`${DF}island/w.json`, c2d(tree));
const W = `${NS}:island/w`;

// ---- climate functions
const CLIM = ['continentalness', 'erosion', 'weirdness', 'temperature', 'humidity'];
const ROUTER = { continentalness: 'continents', erosion: 'erosion', weirdness: 'ridges', temperature: 'temperature', humidity: 'vegetation' };
const d0 = grid.desert;
// (a) terrain graph leaves: the island's full values wherever its mask is above 0 (a step, not a ramp), desert values elsewhere.
//     The vanilla density is only ever used where the mask is above 0, so those are the only places they matter.
for (const key of ['continentalness', 'erosion', 'weirdness']) {
  let e = d0[key];
  for (const i of islands) e = add(e, mul(rc(`${NS}:island/w_${i.biome}`, 1e-9, 2.0, 1.0, 0.0), r9(i[key] - d0[key])));
  write(`${DF}overworld/${ROUTER[key]}.json`, flat(e));
}
// (b) biome-sampler functions, two modes: native = ramp with the terrain mask; sharp = island values out to edge + 100, short ramp
for (const [mode, prefix] of [['native', 'w_'], ['sharp', 'wb_']]) for (const key of CLIM) {
  let e = d0[key];
  for (const i of islands) e = add(e, mul(`${NS}:island/${prefix}${i.biome}`, r9(i[key] - d0[key])));
  write(`${DF}biome_${mode}/${ROUTER[key]}.json`, flat(e));
}

// ---- copy of vanilla's overworld density function graph (every minecraft:overworld/... reference becomes ours)
const rw = n => typeof n === 'string' ? n.replace(/^minecraft:overworld\//, NS + ':overworld/')
  : Array.isArray(n) ? n.map(rw) : (n && typeof n === 'object') ? Object.fromEntries(Object.entries(n).map(([k, v]) => [k, rw(v)])) : n;
const COPIED = ['base_3d_noise', 'depth', 'factor', 'jaggedness', 'offset', 'ridges_folded', 'sloped_cheese'];
for (const f of COPIED) write(`${DF}overworld/${f}.json`, rw(readV(`density_function/overworld/${f}.json`)));

// ---- noise settings
const SLOPED = `${NS}:overworld/sloped_cheese`;
function vanillaNoCaves(router) {   // squeeze(0.64 * interpolated(...)) with min(..., noodle) and the cave range_choice removed
  let fd = router.final_density, foundNoodle = false, foundRange = 0;
  if (fd.type === 'minecraft:min' && fd.argument2 === `${NS}:overworld/caves/noodle`) { fd = fd.argument1; foundNoodle = true; }
  const strip = n => Array.isArray(n) ? n.map(strip) : (n && typeof n === 'object')
    ? (n.type === 'minecraft:range_choice' && n.input === SLOPED ? (foundRange++, SLOPED) : Object.fromEntries(Object.entries(n).map(([k, v]) => [k, strip(v)]))) : n;
  fd = strip(fd);
  if (!foundNoodle || foundRange !== 1 || JSON.stringify(fd).includes('caves/')) throw new Error('final_density no longer has the expected cave structure');
  return fd;
}
// put the mask range_choice INSIDE the interpolated, so cell corners where the mask is 0 cost nothing (range_choice is lazy)
function gateInterpolated(n) {
  let hit = 0;
  const go = x => Array.isArray(x) ? x.map(go) : (x && typeof x === 'object')
    ? (x.type === 'minecraft:interpolated' ? (hit++, { type: 'minecraft:interpolated', argument: rc(W, 1e-9, 2.0, x.argument, 0.0) }) : Object.fromEntries(Object.entries(x).map(([k, v]) => [k, go(v)]))) : x;
  const out = go(n); if (hit !== 1) throw new Error('expected exactly one interpolated in the final density, found ' + hit);
  return out;
}
const BIOMES = islands.map(i => i.biome);
const ownBiome = s => (typeof s === 'string' && s.startsWith('minecraft:') && BIOMES.includes(s.slice(10))) ? NS + ':' + s.slice(10) : s;
const mapStr = (n, f) => Array.isArray(n) ? n.map(x => mapStr(x, f)) : (n && typeof n === 'object') ? Object.fromEntries(Object.entries(n).map(([k, v]) => [k, mapStr(v, f)])) : f(n);
const DUNE_SURFACE = { type: 'minecraft:condition', if_true: { type: 'minecraft:biome', biome_is: [NS + ':' + d0.biome] }, then_run: { type: 'minecraft:block', result_state: { Name: 'minecraft:sand' } } };
function noiseSettings(mode) {
  const ns = readV('noise_settings/overworld.json'), router = rw(ns.noise_router);
  const vanillaFinal = gateInterpolated(vanillaNoCaves(router));
  for (const key of CLIM) router[ROUTER[key]] = `${NS}:biome_${mode}/${ROUTER[key]}`;
  router.depth = `${NS}:overworld/depth`;
  // terrain: final = w * vanilla + (1 - w) * dune, the dune part exact per block and outside every interpolated / flat cache
  router.final_density = add(mul(W, vanillaFinal), mul(add(1.0, mul(-1.0, W)), `${NS}:arrakis_final_density`));
  // preliminary surface height (read by the surface rule): the same blend, of heights
  router.preliminary_surface_level = add(mul(W, rc(W, 1e-9, 2.0, router.preliminary_surface_level, 0.0)), mul(add(1.0, mul(-1.0, W)), `${NS}:arrakis_surface_height`));
  // no ore veins in the dunes (they would replace the sand): the vein toggle is 0 wherever the mask is 0
  router.vein_toggle = rc(W, 1e-9, 2.0, router.vein_toggle, 0.0);
  ns.noise_router = router;
  ns.aquifers_enabled = false;
  ns.surface_rule = mapStr(ns.surface_rule, ownBiome);
  ns.surface_rule.sequence.splice(1, 0, DUNE_SURFACE);        // right after the bedrock gradient, ahead of vanilla's rules
  return ns;
}
write('worldgen/noise_settings/arrakis_native.json', noiseSettings('native'));
write('worldgen/noise_settings/arrakis_sharp.json', noiseSettings('sharp'));
write('worldgen/noise_settings/arrakis.json', noiseSettings(MODE));

// ---- biome copies of the eight islands: carvers emptied, cave-style features removed
const removed = new Set(grid.removed_features);
for (const b of BIOMES) {
  const j = readV(`biome/${b}.json`);
  j.carvers = [];
  j.features = j.features.map(step => step.filter(f => !removed.has(f)));
  write(`worldgen/biome/${b}.json`, j);
}

// ---- biome source: vanilla's parameter points of these biomes (the Desert boxes now select the dune biome)
const all = JSON.parse(fs.readFileSync(path.join(ROOT, 'build/rw/params.json'), 'utf8'));
const target = b => b === d0.vanilla_biome ? NS + ':' + d0.biome : NS + ':' + b;
const mine = all.filter(x => BIOMES.includes(x.biome.slice(10)) || x.biome === 'minecraft:' + d0.vanilla_biome).map(x => ({ biome: target(x.biome.slice(10)), parameters: x.parameters }));
const q4 = v => Math.trunc(v * 10000);
const dist = (v, r) => { const lo = q4(Array.isArray(r) ? r[0] : r), hi = q4(Array.isArray(r) ? r[1] : r); return Math.max(v - hi, lo - v, 0); };
function nearest(t, depth = 0) {
  const tv = { temperature: q4(t.temperature), humidity: q4(t.humidity), continentalness: q4(t.continentalness), erosion: q4(t.erosion), weirdness: q4(t.weirdness), depth: q4(depth) };
  let best = Infinity, who = new Set();
  for (const m of mine) {
    let d = 0; for (const k of Object.keys(tv)) d += dist(tv[k], m.parameters[k]) ** 2;
    d += q4(m.parameters.offset) ** 2;
    if (d < best) { best = d; who = new Set([m.biome]); } else if (d === best) who.add(m.biome);
  }
  return { best, who: [...who] };
}
let bad = 0;
for (const t of [{ ...d0, target: NS + ':' + d0.biome }, ...islands.map(i => ({ ...i, target: NS + ':' + i.biome }))]) {
  const r = nearest(t);
  if (!(r.best === 0 && r.who.length === 1 && r.who[0] === t.target)) { bad++; console.warn(`WARNING tuple of ${t.biome}: nearest ${r.who.join(',')} distance ${r.best}`); }
}
if (bad) console.warn(bad + ' tuple(s) do not select their own biome uniquely');
const preset = settings => ({
  dimensions: {
    'minecraft:overworld': { type: 'minecraft:overworld', generator: { type: 'minecraft:noise', settings: `${NS}:${settings}`, biome_source: { type: 'minecraft:multi_noise', biomes: mine } } },
    'minecraft:the_end': { type: 'minecraft:the_end', generator: { type: 'minecraft:noise', biome_source: { type: 'minecraft:the_end' }, settings: 'minecraft:end' } },
    'minecraft:the_nether': { type: 'minecraft:the_nether', generator: { type: 'minecraft:noise', biome_source: { type: 'minecraft:multi_noise', preset: 'minecraft:nether' }, settings: 'minecraft:nether' } }
  }
});
write('worldgen/world_preset/arrakis.json', preset('arrakis'));
write('worldgen/world_preset/arrakis_native.json', preset('arrakis_native'));
write('worldgen/world_preset/arrakis_sharp.json', preset('arrakis_sharp'));
// the world-type list: "Arrakis" (the mode of grid.json) and the other mode as a second entry (a tag with replace:false merges with vanilla's)
write('tags/worldgen/world_preset/normal.json', { replace: false, values: [`${NS}:arrakis`, `${NS}:arrakis_${MODE === 'native' ? 'sharp' : 'native'}`] }, path.join(RES, 'data/minecraft'));
// TEST ONLY (TEST_PRESETS=1): the dune terrain alone under the same world type, to know the dune height under every island exactly.
if (process.env.TEST_PRESETS) {
  const nd = noiseSettings('native'); nd.noise_router.final_density = `${NS}:arrakis_final_density`; nd.noise_router.preliminary_surface_level = `${NS}:arrakis_surface_height`;
  write('worldgen/noise_settings/arrakis_test.json', nd);
  write('worldgen/world_preset/arrakis_test.json', preset('arrakis_test'));
  console.log('TEST-ONLY dune-only preset written as arrakis_test; run gen.js again without TEST_PRESETS to remove it');
}
const geo = { radius_semi_axes: [A, B], mean_radius: RM, transition_end_q: +QEND.toFixed(4), transition_end_semi_axes: [+(A * REND).toFixed(1), +(B * REND).toFixed(1)],
  min_clear_gap_between_zones: +minGap.toFixed(1), radial_distance_worst_error_blocks: +worstErr.toFixed(2), worst_error_at: worstAt };
console.log(JSON.stringify(geo));
console.log(`wrote ${islands.length} islands (biome mode of the default world type: ${MODE}), ${mine.length} parameter points`);
