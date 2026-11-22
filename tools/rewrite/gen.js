#!/usr/bin/env node
// Generates the island layer of the "Arrakis" world type from tools/rewrite/grid.json and vanilla's own 26.1.2 data.
//   node tools/rewrite/gen.js          (run tools/rewrite/extract-vanilla.sh once first: it fills build/vanilla and build/rw/params.json)
// The dune base is NOT generated here: it is the prompt-03 dune data (tools/dunes/generate.js, files arrakis_*).
// Writes under src/main/resources/data/<namespace>/ : island masks, the terrain and biome-sampler climate functions, a copy of
// vanilla's overworld density graph, the blended noise settings (two biome modes), eight biome copies and the world presets.
// Nothing under data/minecraft is overridden (the world-type list is a tag with replace:false, which merges).
const fs = require('fs'), path = require('path');
const ROOT = path.resolve(__dirname, '..', '..');
const ISL_FILE = path.join(__dirname, 'islands.json');
const ISL = fs.existsSync(ISL_FILE) ? JSON.parse(fs.readFileSync(ISL_FILE, 'utf8')) : null;
const LAYOUT = process.env.LAYOUT || (ISL && !process.env.GRID_FILE ? ISL.layout : 'circles');   // "noise" (prompt 17, default) | "circles" (prompts 14-16, tools/rewrite/grid.json)
if (!['noise', 'circles'].includes(LAYOUT)) throw new Error('layout must be noise or circles');
const NOISE = LAYOUT === 'noise';
const grid = NOISE ? Object.assign({ namespace: ISL.namespace, desert: ISL.desert, removed_features: ISL.removed_features, biome_transition: 'noise', transition: 0, sharp_ramp: 0,
  diameter_x: 100, diameter_z: 100, pitch: 300, origin_x: 0, origin_z: 0, islands: ISL.biomes.map((b, k) => Object.assign({ col: k, row: 0 }, b)) }, {})
  : JSON.parse(fs.readFileSync(process.env.GRID_FILE || path.join(__dirname, 'grid.json'), 'utf8'));
const NS = grid.namespace;
const OC = NOISE ? { enabled: true } : (grid.outcrop || { enabled: false });   // prompt 16: normalised outcrop heights (a heightfield in each circle)
const VAN = path.join(ROOT, 'build/vanilla/data/minecraft/worldgen');
const RES = path.join(ROOT, 'src/main/resources');
const OUT = path.join(RES, 'data', NS);
const readV = rel => JSON.parse(fs.readFileSync(path.join(VAN, rel), 'utf8'));
function write(rel, obj, base = OUT) {
  const p = path.join(base, rel); fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, JSON.stringify(obj, null, 2) + '\n');
}
// remove only what this script generates (the dune files are not ours to delete)
for (const d of ['island', 'overworld', 'biome_native', 'biome_sharp', 'biome_noise', 'patch']) fs.rmSync(path.join(OUT, 'worldgen/density_function', d), { recursive: true, force: true });
for (const f of ['arrakis', 'arrakis_native', 'arrakis_sharp', 'arrakis_test']) { fs.rmSync(path.join(OUT, 'worldgen/noise_settings', f + '.json'), { force: true }); fs.rmSync(path.join(OUT, 'worldgen/world_preset', f + '.json'), { force: true }); }
if (fs.existsSync(path.join(OUT, 'worldgen/noise'))) for (const n of fs.readdirSync(path.join(OUT, 'worldgen/noise'))) if (n.startsWith('islands_')) fs.rmSync(path.join(OUT, 'worldgen/noise', n));
const keepBiome = new Set(['arrakis_desert.json']);
if (fs.existsSync(path.join(OUT, 'worldgen/biome'))) for (const f of fs.readdirSync(path.join(OUT, 'worldgen/biome'))) if (!keepBiome.has(f)) fs.rmSync(path.join(OUT, 'worldgen/biome', f));

// ---- geometry
const A = grid.diameter_x / 2, B = grid.diameter_z / 2, T = grid.transition, RM = (A + B) / 2;
const REND = 1 + T / RM, QEND = REND * REND;                 // normalised radius and squared normalised radius where the transition ends
const distOfQ = q => (Math.sqrt(q) - 1) * RM;                // the radial distance beyond the edge, in blocks
const qOfDist = d => (1 + d / RM) * (1 + d / RM);
const islands = grid.islands.map(p => Object.assign({ cx: grid.origin_x + grid.pitch * p.col, cz: grid.origin_z + grid.pitch * p.row }, p));
const MODE = grid.biome_transition;
if (!NOISE && !['native', 'sharp'].includes(MODE)) throw new Error('biome_transition must be native or sharp');
// refuse overlapping transition zones: sample each zone boundary and test it against every other zone
const qAt = (i, x, z) => ((x - i.cx) / A) ** 2 + ((z - i.cz) / B) ** 2;
let minGap = Infinity;
for (const i of NOISE ? [] : islands) for (const j of islands) {
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
for (let k = 0; k < (NOISE ? 0 : 360); k++) for (let m = 1; m <= 40; m++) {
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
for (const i of NOISE ? [] : islands) {
  write(`${DF}island/w_${i.biome}.json`, (T > 0 && !OC.enabled) ? splineOver(i, terrainPts()) : stepMask(i));
  write(`${DF}island/wb_${i.biome}.json`, (T > 0 && grid.sharp_ramp > 0 && !OC.enabled) ? splineOver(i, sharpPts()) : stepMask(i));
}
// total terrain mask, exact per block (cache_2d, never flat_cache). The zones never overlap (checked above), so instead of summing all
// eight masks it picks the one island whose bounding box holds the block: a nested range_choice on block_x (columns), then block_z (rows).
// A dune column then costs about four comparisons, not eight splines (the terrain graph re-evaluates this once per vertical cell).
const pick = (coord, lo, hi, inner, outer) => rc(coord, Math.floor(lo), Math.ceil(hi) + 1, inner, outer);
const cols = [...new Set(islands.map(i => i.col))].sort((p, q) => p - q);
function pickTree(prefix) {   // the island's own function (prefix_<biome>) inside its bounding box, 0 elsewhere
  let tree = 0.0;
  for (const c of cols.slice().reverse()) {
    const inCol = islands.filter(i => i.col === c).sort((p, q) => p.row - q.row);
    let rowTree = 0.0;
    for (const i of inCol.slice().reverse()) rowTree = pick(BZ, i.cz - B * REND, i.cz + B * REND, `${NS}:island/${prefix}_${i.biome}`, rowTree);
    tree = pick(BX, inCol[0].cx - A * REND, inCol[0].cx + A * REND, rowTree, tree);
  }
  return tree;
}
if (!NOISE) write(`${DF}island/w.json`, c2d(pickTree('w')));
const W = `${NS}:island/w`;

// ---- outcrop heights (prompt 16)
const SAND = NS + ':arrakis_sand_level', SURF = NS + ':arrakis_surface_height';
const ROCK = NS + ':island/rock_height', MASK = NS + ':island/rock_mask';
if (OC.enabled && !NOISE) {
  const sel = fn => islands.reduce((e, i) => add(e, mul(rc(`${NS}:island/w_${i.biome}`, 1e-9, 2.0, 1.0, 0.0), r9(fn(i)))), 0.0);   // the selected island's constant
  const eff = i => { const mid = (i.vanilla_low + i.vanilla_high) / 2, span = Math.max(i.vanilla_high - i.vanilla_low, OC.min_span); return { low: mid - span / 2, span }; };
  write(`${DF}island/vanilla_top.json`, { type: 'minecraft:find_top_surface', density: `${NS}:overworld/sloped_cheese`, upper_bound: OC.search_top, lower_bound: OC.search_bottom, cell_height: OC.cell_height });
  write(`${DF}island/relief_n.json`, { type: 'minecraft:clamp', input: mul(add(`${NS}:island/vanilla_top`, mul(-1.0, sel(i => eff(i).low))), sel(i => 1 / eff(i).span)), min: 0.0, max: 1.0 });
  // the rise before the taper, evaluated on the 4-block grid and only where a circle is (range_choice is lazy inside the cache)
  write(`${DF}island/rise_raw.json`, flat(rc(W, 1e-9, 2.0, add(OC.min_rise, mul(add(sel(i => i.max_rise), -OC.min_rise), `${NS}:island/relief_n`)), 0.0)));
  for (const i of islands) {   // taper: 1 inside core_fraction of the radius, smoothstep to 0 at the edge; spline on the squared distance
    const c = OC.core_fraction, pts = [];
    for (let k = 0; k <= 24; k++) { const r = c + (1 - c) * k / 24, st = 1 - (r - c) / (1 - c); pts.push({ location: r9(r * r), value: r9(smooth(st)), derivative: r9((-dsmooth(st) / (1 - c)) / (2 * r)) }); }
    write(`${DF}island/taper_${i.biome}.json`, { type: 'minecraft:spline', spline: { coordinate: qFn(i), points: pts } });
  }
  write(`${DF}island/taper.json`, c2d(pickTree('taper')));
  // rise: the 4-block values are interpolated between the cell corners, so the relief is smooth, not terraced
  write(`${DF}island/rock_height.json`, add(SAND, mul(`${NS}:island/taper`, { type: 'minecraft:interpolated', argument: `${NS}:island/rise_raw` })));
  write(`${DF}island/surface.json`, { type: 'minecraft:max', argument1: SURF, argument2: ROCK });
  write(`${DF}island/final_density.json`, add(0.5, add(`${NS}:island/surface`, mul(-1.0, 'minecraft:y'))));
  // 1 where the rock stands at least half a block above the dune surface (data for the biome), 0 where the sand covers it
  write(`${DF}island/rock_mask.json`, rc(W, 1e-9, 2.0, rc(add(ROCK, mul(-1.0, SURF)), 0.5, 1e6, 1.0, 0.0), 0.0));
}

// ---- noise-shaped islands (prompt 17, rock and sand reworked in prompt 18): one land field F, no circles
const ROUTER_NOISE = { continentalness: 'continents', erosion: 'erosion', weirdness: 'ridges', temperature: 'temperature', humidity: 'vegetation' };
const CLIM_NOISE = ['continentalness', 'erosion', 'weirdness', 'temperature', 'humidity'];
let FIELD_SUMMARY = null;
if (NOISE) {
  const F = ISL.field, H = ISL.height, BN = ISL.biome_noise, AR = F.archipelago, SP = F.spawn, WD = ISL.wind, WS = ISL.wind_sand;
  const bad = m => { throw new Error('islands.json: ' + m); };
  if (ISL.biomes.length !== 8) bad('eight biomes expected');
  if (!(BN.cuts.length === 7 && BN.cuts.every((c, k) => k === 0 || c > BN.cuts[k - 1]))) bad('biome_noise.cuts needs 7 strictly increasing values');
  if (!(H.ramp > 0 && H.big_ramp > 0 && AR.ridge_width > 0 && AR.band_half_width > 0 && SP.radius > 0 && WS.edge_ramp > 0 && WS.scour_pad > 0)) bad('ramp, big_ramp, ridge_width, band_half_width, spawn.radius, wind_sand.edge_ramp and scour_pad must be positive');
  for (const k of ['scale_large', 'scale_mid', 'scale_fine', 'warp_scale']) if (!(F[k] >= 16 && F[k] <= 65536)) bad(k + ' must be 16..65536 blocks');
  for (const k of ['stretch_large', 'stretch_mid', 'stretch_fine']) if (!(WD[k] >= 0.5 && WD[k] <= 6)) bad('wind.' + k + ' must be 0.5..6');
  for (const b of ISL.biomes) {
    if (b.max_rise < H.min_rise) bad(`${b.biome}: max_rise must be at least min_rise`);
    if (!(b.relief_scale >= 16 && b.relief_gain > 0)) bad(`${b.biome}: relief_scale >= 16 and relief_gain > 0 needed`);
  }
  if (H.min_rise < 0 || H.cap_above_sand < H.min_rise || H.big_bonus < 0) bad('min_rise, cap_above_sand, big_bonus out of range');
  if (!(F.threshold > -1 && F.threshold < 2) || F.a < 0 || F.b < 0 || F.c < 0) bad('threshold / a / b / c out of range');
  if (SP.strength < F.threshold + 1.0) console.warn('WARNING spawn.strength is small: the origin may fall on sand where the noise is low (guaranteed land needs about threshold + 1.0 or more)');
  const fo = size => -Math.round(Math.log2(size));
  const nf = (name, size, amps) => write(`worldgen/noise/${name}.json`, { firstOctave: fo(size), amplitudes: amps });
  nf('islands_large', F.scale_large, F.amplitudes); nf('islands_mid', F.scale_mid, F.amplitudes); nf('islands_fine', F.scale_fine, F.amplitudes);
  nf('islands_warp_x', F.warp_scale, [1, 0.5]); nf('islands_warp_z', F.warp_scale, [1, 0.5]); nf('islands_ridge', AR.scale, F.amplitudes); nf('islands_biome', BN.scale, BN.amplitudes);
  for (const b of ISL.biomes) nf(`islands_relief_${b.biome}`, b.relief_scale, [1, 0.5]);
  const plain = name => ({ type: 'minecraft:noise', noise: `${NS}:${name}`, xz_scale: 1.0, y_scale: 0.0 });
  const IS = n => `${NS}:island/${n}`;
  const clampN = (x, lo, hi) => ({ type: 'minecraft:clamp', input: x, min: lo, max: hi });
  const smoothFn = x => mul(sq(x), add(3.0, mul(-2.0, x)));
  write(`${DF}island/warp_x.json`, c2d(mul(F.warp_amplitude, plain('islands_warp_x'))));
  write(`${DF}island/warp_z.json`, c2d(mul(F.warp_amplitude, plain('islands_warp_z'))));
  // wind-aligned coordinates: u along the wind (divided by the stretch, so features are longer downwind), v across it; off = shift of the sample along the wind in blocks
  const wr = WD.toward_degrees * Math.PI / 180, cw = Math.cos(wr), sw = Math.sin(wr);
  const lin = (cx, cz) => ({ type: `${NS}:arrakis_linear`, x_coefficient: r9(cx), z_coefficient: r9(cz) });
  const wnoise = (name, stretch, off) => c2d({ type: 'minecraft:shifted_noise', noise: `${NS}:${name}`, xz_scale: 0.0, y_scale: 0.0,
    shift_x: add(lin(cw / stretch, sw / stretch), off ? add(IS('warp_x'), r9(off / stretch)) : IS('warp_x')), shift_y: 0.0, shift_z: add(lin(-sw, cw), IS('warp_z')) });
  // the land field F at the point shifted by off blocks along the wind (suffix names the copy); each node is cached once per column
  function emitField(sfx, off) {
    for (const [n, nm, st] of [['n_large', 'islands_large', WD.stretch_large], ['n_mid', 'islands_mid', WD.stretch_mid], ['n_fine', 'islands_fine', WD.stretch_fine], ['n_ridge', 'islands_ridge', WD.stretch_mid]]) write(`${DF}island/${n}${sfx}.json`, wnoise(nm, st, off));
    write(`${DF}island/ridge${sfx}.json`, clampN(add(1.0, mul(-1 / AR.ridge_width, { type: 'minecraft:abs', argument: IS('n_ridge' + sfx) })), 0.0, 1.0));
    write(`${DF}island/band${sfx}.json`, clampN(add(1.0, mul(-1 / (AR.band_half_width * AR.band_half_width), sq(add(IS('n_large' + sfx), -AR.band_centre)))), 0.0, 1.0));
    const sdx = add(BX, r9(off * cw)), sdz = add(BZ, r9(off * sw));
    write(`${DF}island/spawn_bump${sfx}.json`, mul(SP.strength, sq(clampN(add(1.0, mul(-1 / (SP.radius * SP.radius), add(sq(sdx), sq(sdz)))), 0.0, 1.0))));
    write(`${DF}island/f${sfx}.json`, c2d(add(add(add(mul(F.a, IS('n_large' + sfx)), mul(F.b, IS('n_mid' + sfx))), mul(F.c, IS('n_fine' + sfx))), add(mul(AR.d, mul(IS('ridge' + sfx), IS('band' + sfx))), IS('spawn_bump' + sfx)))));
  }
  emitField('', 0);
  write(`${DF}island/n_biome.json`, wnoise('islands_biome', 1.0, 0));
  write(`${DF}island/w.json`, rc(IS('f'), F.threshold, 99.0, 1.0, 0.0));   // land: the lazy gate for the rock, the vein toggle and the biome
  // sand wraps the rock: windward bank (rock lies downwind of the point), lee tail (rock lies upwind), scour moat at the foot where neither applies
  if (WS.bank_height > 0 || WS.tail_height > 0 || WS.scour_depth > 0) {
    if (WS.bank_height > 0) emitField('_bank', WS.bank_distance);
    if (WS.tail_height > 0) emitField('_tail', -WS.tail_distance);
  }
  const inside = (sfx, ramp) => smoothFn(clampN(mul(1 / ramp, add(IS('f' + sfx), -F.threshold)), 0.0, 1.0));
  const terms = [`${NS}:arrakis_dune_height`];
  if (WS.bank_height > 0) { write(`${DF}island/bank.json`, c2d(mul(WS.bank_height, inside('_bank', WS.edge_ramp)))); terms.push(IS('bank')); }
  if (WS.tail_height > 0) { write(`${DF}island/tail.json`, c2d(mul(WS.tail_height, inside('_tail', WS.edge_ramp)))); terms.push(IS('tail')); }
  if (WS.scour_depth > 0) {
    const s = clampN(mul(1 / (1.3 * WS.scour_pad), add(IS('f'), -(F.threshold - WS.scour_pad))), 0.0, 1.0);
    let sc = mul(WS.scour_depth, mul(4.0, mul(s, add(1.0, mul(-1.0, s)))));
    if (WS.bank_height > 0) sc = mul(sc, add(1.0, mul(-1.0, clampN(mul(1 / 0.03, add(IS('f_bank'), -F.threshold)), 0.0, 1.0))));
    if (WS.tail_height > 0) sc = mul(sc, add(1.0, mul(-1.0, clampN(mul(1 / 0.03, add(IS('f_tail'), -F.threshold)), 0.0, 1.0))));
    write(`${DF}island/scour.json`, c2d(mul(-1.0, sc))); terms.push(IS('scour'));
  }
  const sum = terms.reduce((e, t) => e === null ? t : add(e, t), null);
  // the sand surface: sand level + dunes - dips + bank + tail - scour, never below sand level - dip depth
  write(`${DF}island/dune_surface.json`, { type: 'minecraft:max', argument1: add(`${NS}:arrakis_sand_level`, -ISL.dune_floor_below_sand), argument2: add(`${NS}:arrakis_sand_level`, sum) });
  // rock height: a smoothstep of the land field above the threshold times the rise (2D, cached once per column)
  write(`${DF}island/t.json`, c2d(smoothFn(clampN(mul(1 / H.ramp, add(IS('f'), -F.threshold)), 0.0, 1.0))));
  write(`${DF}island/t2.json`, c2d(clampN(mul(1 / H.big_ramp, add(IS('f'), -(F.threshold + H.ramp))), 0.0, 1.0)));
  const bandTree = vals => { const go = (lo, hi) => lo === hi ? vals[lo] : (m => rc(IS('n_biome'), BN.cuts[m - 1], 1e3, go(m, hi), go(lo, m - 1)))(Math.floor((lo + hi + 1) / 2)); return go(0, 7); };
  // relief shape n in 0..1 per biome from its own 2D noise (smooth, ridged, sharpened, terraced)
  const terrace = m => { const pts = [], P = 0.18; for (let i = 0; i < m; i++) { const v = m === 1 ? 0 : i / (m - 1); pts.push({ location: r9((i + P) / m), value: r9(v), derivative: 0.0 }); pts.push({ location: r9((i + 1 - P) / m), value: r9(v), derivative: 0.0 }); } pts.unshift({ location: 0.0, value: 0.0, derivative: 0.0 }); pts.push({ location: 1.0, value: 1.0, derivative: 0.0 }); return pts; };
  for (const b of ISL.biomes) {
    let n = b.relief_ridged ? clampN(add(1.0, mul(-b.relief_gain, { type: 'minecraft:abs', argument: plain('islands_relief_' + b.biome) })), 0.0, 1.0) : clampN(add(0.5, mul(b.relief_gain, plain('islands_relief_' + b.biome))), 0.0, 1.0);
    for (let k = 1; k < (b.relief_power || 1); k++) n = sq(n);
    if (b.relief_terrace > 0) n = { type: 'minecraft:spline', spline: { coordinate: n, points: terrace(b.relief_terrace) } };
    write(`${DF}island/relief_${b.biome}.json`, c2d(n));
  }
  write(`${DF}island/rise.json`, c2d(rc(IS('f'), F.threshold, 99.0, add(add(H.min_rise, mul(add(bandTree(ISL.biomes.map(i => i.max_rise)), -H.min_rise), bandTree(ISL.biomes.map(i => IS('relief_' + i.biome))))), mul(H.big_bonus, IS('t2'))), 0.0)));
  write(`${DF}island/rock_height.json`, add(`${NS}:arrakis_sand_level`, { type: 'minecraft:min', argument1: H.cap_above_sand, argument2: mul(IS('t'), IS('rise')) }));
  write(`${DF}island/surface.json`, { type: 'minecraft:max', argument1: IS('dune_surface'), argument2: IS('rock_height') });
  write(`${DF}island/final_density.json`, add(0.5, add(IS('surface'), mul(-1.0, 'minecraft:y'))));
  write(`${DF}island/rock_mask.json`, rc(IS('f'), F.threshold, 99.0, rc(add(IS('rock_height'), mul(-1.0, IS('dune_surface'))), 0.5, 1e6, 1.0, 0.0), 0.0));
  for (const key of ['continentalness', 'erosion', 'weirdness']) write(`${DF}overworld/${ROUTER_NOISE[key]}.json`, flat(bandTree(ISL.biomes.map(i => i[key]))));
  for (const key of CLIM_NOISE) write(`${DF}biome_noise/${ROUTER_NOISE[key]}.json`, flat(add(grid.desert[key], mul(IS('rock_mask'), add(bandTree(ISL.biomes.map(i => i[key])), -grid.desert[key])))));
  FIELD_SUMMARY = { first_octaves: { large: fo(F.scale_large), mid: fo(F.scale_mid), fine: fo(F.scale_fine), warp: fo(F.warp_scale), ridge: fo(AR.scale), biome: fo(BN.scale) }, biome_cuts: BN.cuts, threshold: F.threshold, wind_degrees: WD.toward_degrees };
}

// ---- climate functions
const CLIM = ['continentalness', 'erosion', 'weirdness', 'temperature', 'humidity'];
const ROUTER = { continentalness: 'continents', erosion: 'erosion', weirdness: 'ridges', temperature: 'temperature', humidity: 'vegetation' };
const d0 = grid.desert;
// (a) terrain graph leaves: the island's full values wherever its mask is above 0 (a step, not a ramp), desert values elsewhere.
//     The vanilla density is only ever used where the mask is above 0, so those are the only places they matter.
for (const key of NOISE ? [] : ['continentalness', 'erosion', 'weirdness']) {
  let e = d0[key];
  for (const i of islands) e = add(e, mul(rc(`${NS}:island/w_${i.biome}`, 1e-9, 2.0, 1.0, 0.0), r9(i[key] - d0[key])));
  write(`${DF}overworld/${ROUTER[key]}.json`, flat(e));
}
// (b) biome-sampler functions, two modes: native = ramp with the terrain mask; sharp = island values out to edge + 100, short ramp
for (const [mode, prefix] of NOISE ? [] : [['native', 'w_'], ['sharp', 'wb_']]) for (const key of CLIM) {
  let e = 0.0;
  for (const i of islands) e = add(e, mul(`${NS}:island/${prefix}${i.biome}`, r9(i[key] - d0[key])));
  // outcrop mode: the island's climate only where its rock stands above the sand (mask m), the dune biome elsewhere in the circle
  write(`${DF}biome_${mode}/${ROUTER[key]}.json`, flat(add(d0[key], OC.enabled ? rc(W, 1e-9, 2.0, mul(MASK, e), 0.0) : e)));
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
const DUNE_SURFACE = { type: 'minecraft:condition', if_true: { type: 'minecraft:biome', biome_is: [NS + ':' + d0.biome] }, then_run: { type: 'minecraft:block', result_state: { Name: NS + ':dune_sand' } } };   // the Dune Sand block (no gravity, harvest-only), not vanilla sand
// ---- prompt 19: banded rock (data only: y_above ladders, a few shifted copies chosen by a wobble noise), per island biome
const BAND_STATS = [];
let BAND_RULE = null;
if (NOISE) {
  const BD = ISL.bands, MATS = ['minecraft:stone', 'minecraft:sandstone', 'minecraft:red_sandstone'];
  const sandLevel = JSON.parse(fs.readFileSync(path.join(OUT, 'worldgen/density_function/arrakis_sand_level.json'), 'utf8'));   // the named constant of the dune data
  if (typeof sandLevel !== 'number') throw new Error('arrakis_sand_level must be a constant');
  const y0 = sandLevel - BD.from_below_sand, topY = sandLevel + ISL.height.cap_above_sand + 8;
  const badP = m => { throw new Error('palette error: ' + m); };
  write('worldgen/noise/band_wobble.json', { firstOctave: -Math.round(Math.log2(BD.wobble_scale)), amplitudes: [1, 1] });
  const block = name => ({ type: 'minecraft:block', result_state: { Name: name } });
  const yAbove = y => ({ type: 'minecraft:y_above', anchor: { absolute: y }, surface_depth_multiplier: 0, add_stone_depth: false });
  const perBiome = ISL.biomes.map(b => {
    const p = b.palette, m = b.band_wobble;
    if (!Array.isArray(p) || p.length === 0) badP(b.biome + ': empty palette');
    for (const [mat, th] of p) { if (!MATS.includes(mat)) badP(b.biome + ': ' + mat + ' is not stone, sandstone or red_sandstone'); if (!Number.isInteger(th) || th < 1 || th > 5) badP(b.biome + ': thickness ' + th + ' not an integer 1..5'); }
    if (![0, 1, 2].includes(m)) badP(b.biome + ': band_wobble must be 0, 1 or 2');
    const cyc = p.map(([mat, th]) => ({ mat, th })), P = cyc.reduce((s, x) => s + x.th, 0);
    const bands = [];   // [start, material], merged where neighbours share a material
    for (let y = y0, k = 0; y < topY + 8; k++) { const x = cyc[k % cyc.length]; if (bands.length && bands[bands.length - 1][1] === x.mat) { /* merge */ } else bands.push([y, x.mat]); y += x.th; }
    const ladder = s => { const arr = bands.slice(1).reverse().map(([y, mat]) => ({ type: 'minecraft:condition', if_true: yAbove(y + s), then_run: block(mat) })); arr.push(block(bands[0][1])); return { type: 'minecraft:sequence', sequence: arr }; };
    const buckets = []; for (let s = -m; s <= m; s++) buckets.push(s);
    const sel = buckets.map(s => ({ type: 'minecraft:condition', if_true: { type: 'minecraft:noise_threshold', noise: NS + ':band_wobble', min_threshold: s === -m ? -10 : (s - 0.5) * BD.wobble_step, max_threshold: s === m ? 10 : (s + 0.5) * BD.wobble_step }, then_run: ladder(s) }));
    const body = m === 0 ? ladder(0) : { type: 'minecraft:sequence', sequence: sel };
    BAND_STATS.push({ biome: b.biome, cycle: P, bands: bands.length, y_above_per_ladder: bands.length - 1, ladders: buckets.length, y_above_total: (bands.length - 1) * buckets.length, noise_threshold: m === 0 ? 0 : buckets.length, blocks: [...new Set(p.map(x => x[0]))] });
    return { type: 'minecraft:condition', if_true: { type: 'minecraft:biome', biome_is: [NS + ':' + b.biome] }, then_run: body };
  });
  BAND_RULE = { type: 'minecraft:condition', if_true: yAbove(y0), then_run: { type: 'minecraft:sequence', sequence: perBiome } };
}
function noiseSettings(mode) {
  const ns = readV('noise_settings/overworld.json'), router = rw(ns.noise_router);
  const vanillaFinal = OC.enabled ? null : gateInterpolated(vanillaNoCaves(router));
  for (const key of CLIM) router[ROUTER[key]] = `${NS}:biome_${mode}/${ROUTER[key]}`;
  router.depth = `${NS}:overworld/depth`;
  // terrain: final = w * vanilla + (1 - w) * dune, the dune part exact per block and outside every interpolated / flat cache
  if (OC.enabled) router.final_density = `${NS}:island/final_density`;   // prompt 16: one exact heightfield, dunes and rock together
  else router.final_density = add(mul(W, vanillaFinal), mul(add(1.0, mul(-1.0, W)), `${NS}:arrakis_final_density`));
  // preliminary surface height (read by the surface rule): the same blend, of heights
  if (OC.enabled) router.preliminary_surface_level = `${NS}:island/surface`;
  else router.preliminary_surface_level = add(mul(W, rc(W, 1e-9, 2.0, router.preliminary_surface_level, 0.0)), mul(add(1.0, mul(-1.0, W)), `${NS}:arrakis_surface_height`));
  // no ore veins in the dunes (they would replace the sand): the vein toggle is 0 wherever the mask is 0
  router.vein_toggle = rc(W, 1e-9, 2.0, router.vein_toggle, 0.0);
  if (NOISE) {   // nothing 3D reaches the final density or the cheap router entries: veins, fluids, barrier and the initial density are constants
    for (const k of ['barrier', 'fluid_level_floodedness', 'fluid_level_spread', 'lava', 'vein_toggle', 'vein_ridged', 'vein_gap', 'initial_density_without_jaggedness']) router[k] = 0.0;
    ns.ore_veins_enabled = false;
  }
  ns.noise_router = router;
  ns.aquifers_enabled = false;
  if (NOISE) {   // prompt 19: bedrock floor, Dune Sand in the dune biome, banded stone / sandstone / red sandstone in the island biomes, vanilla's deepslate gradient; vanilla's biome-specific rules are dropped
    const v = ns.surface_rule.sequence; ns.surface_rule = { type: 'minecraft:sequence', sequence: [v[0], DUNE_SURFACE, BAND_RULE, v[2]] };
  } else {
  ns.surface_rule = mapStr(ns.surface_rule, ownBiome);
  ns.surface_rule.sequence.splice(1, 0, DUNE_SURFACE);        // right after the bedrock gradient, ahead of vanilla's rules
  }
  return ns;
}
if (!NOISE) { write('worldgen/noise_settings/arrakis_native.json', noiseSettings('native')); write('worldgen/noise_settings/arrakis_sharp.json', noiseSettings('sharp')); }
write('worldgen/noise_settings/arrakis.json', noiseSettings(NOISE ? 'noise' : MODE));

// ---- biome copies of the eight islands: carvers emptied, cave-style features removed
const removed = new Set(grid.removed_features);
// prompt 19: desert weather and look in every biome, no spawns, only the kept (ore) features
const DESERT = readV('biome/desert.json'), FEATURE_REPORT = [];
function desertBiome(j, keep, name) {
  const kept = new Set(keep); let nRemoved = 0; const was = j.features.flat().length;
  j.features = j.features.map(step => step.filter(f => kept.has(f)));
  nRemoved = was - j.features.flat().length; FEATURE_REPORT.push({ biome: name, kept: j.features.flat(), removed: nRemoved });
  j.has_precipitation = false; j.temperature = 2.0; j.downfall = 0.0; j.temperature_modifier = 'none';
  j.attributes = JSON.parse(JSON.stringify(DESERT.attributes)); j.effects = JSON.parse(JSON.stringify(DESERT.effects));
  j.spawners = Object.fromEntries(['monster', 'creature', 'ambient', 'axolotls', 'underground_water_creature', 'water_creature', 'water_ambient', 'misc'].map(k => [k, []]));
  j.creature_spawn_probability = 0.0; j.spawn_costs = {};
}
for (const b of BIOMES) {
  const j = readV(`biome/${b}.json`);
  j.carvers = [];
  if (NOISE) desertBiome(j, ISL.kept_features, b); else j.features = j.features.map(step => step.filter(f => !removed.has(f)));
  write(`worldgen/biome/${b}.json`, j);
}
if (NOISE) { const j = readV('biome/desert.json'); j.carvers = []; desertBiome(j, [], d0.biome); write(`worldgen/biome/${d0.biome}.json`, j); }   // the dune biome: no features at all (an ore has no stone to replace in Dune Sand)

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
if (!NOISE) { write('worldgen/world_preset/arrakis_native.json', preset('arrakis_native')); write('worldgen/world_preset/arrakis_sharp.json', preset('arrakis_sharp')); }
// the world-type list: "Arrakis" (the mode of grid.json) and the other mode as a second entry (a tag with replace:false merges with vanilla's)
write('tags/worldgen/world_preset/normal.json', { replace: false, values: NOISE ? [`${NS}:arrakis`] : [`${NS}:arrakis`, `${NS}:arrakis_${MODE === 'native' ? 'sharp' : 'native'}`] }, path.join(RES, 'data/minecraft'));
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
if (FIELD_SUMMARY) console.log(JSON.stringify(FIELD_SUMMARY));
if (NOISE) { console.log('BANDS ' + JSON.stringify(BAND_STATS)); console.log('FEATURES ' + JSON.stringify(FEATURE_REPORT)); }
console.log(`wrote ${islands.length} islands (biome mode of the default world type: ${MODE}), ${mine.length} parameter points`);
