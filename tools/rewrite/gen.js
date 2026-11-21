#!/usr/bin/env node
// Generates the whole "Arrakis" generator data from tools/rewrite/grid.json and vanilla's own 26.1.2 data.
//   node tools/rewrite/gen.js          (run tools/rewrite/extract-vanilla.sh once first: it fills build/vanilla and build/rw/params.json)
// Writes under src/main/resources/data/<namespace>/ : density functions (copies of vanilla's overworld graph with five leaf inputs
// replaced by the patch functions), noise settings, biome copies and the world preset. Nothing under data/minecraft is written.
const fs = require('fs'), path = require('path');
const ROOT = path.resolve(__dirname, '..', '..');
const grid = JSON.parse(fs.readFileSync(path.join(__dirname, 'grid.json'), 'utf8'));
const NS = grid.namespace;
const VAN = path.join(ROOT, 'build/vanilla/data/minecraft/worldgen');
const OUT = path.join(ROOT, 'src/main/resources/data', NS);
const readV = rel => JSON.parse(fs.readFileSync(path.join(VAN, rel), 'utf8'));
function write(rel, obj) {
  const p = path.join(OUT, rel); fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, JSON.stringify(obj, null, 2) + '\n');
}
// start clean so a removed patch does not leave a stale file behind
fs.rmSync(path.join(OUT, 'worldgen'), { recursive: true, force: true });

// ---- sanity of the layout
const size = grid.patch_size, blend = grid.blend;
if (2 * blend > Math.min(grid.gap_x, grid.gap_z)) throw new Error('rings of neighbouring patches would overlap: need 2*blend <= gap');
const patches = grid.patches.map(p => {
  const x0 = grid.origin_x + (size + grid.gap_x) * p.col, z0 = grid.origin_z + (size + grid.gap_z) * p.row;
  return Object.assign({ x0, z0, x1: x0 + size, z1: z0 + size, cx: x0 + size / 2, cz: z0 + size / 2 }, p);
});
const CLIM = ['continentalness', 'erosion', 'weirdness', 'temperature', 'humidity'];
const OUTNAME = { continentalness: 'continents', erosion: 'erosion', weirdness: 'ridges', temperature: 'temperature', humidity: 'vegetation' };

// ---- density function node helpers (vanilla types only, plus our block_x / block_z)
const add = (a, b) => ({ type: 'minecraft:add', argument1: a, argument2: b });
const mul = (a, b) => ({ type: 'minecraft:mul', argument1: a, argument2: b });
const max = (a, b) => ({ type: 'minecraft:max', argument1: a, argument2: b });
const abs = a => ({ type: 'minecraft:abs', argument: a });
const clamp = (a, lo, hi) => ({ type: 'minecraft:clamp', input: a, min: lo, max: hi });
const flat = a => ({ type: 'minecraft:flat_cache', argument: a });
const BX = { type: NS + ':block_x' }, BZ = { type: NS + ':block_z' };
// mask = 1 inside the square, ramps linearly to 0 over `blend` blocks outside it (Chebyshev distance), 0 beyond.
// t = max(|x-cx|-half, |z-cz|-half) is negative inside, 0 on the edge, positive outside; mask = clamp(1 - t/blend, 0, 1).
function maskOf(p) {
  const half = size / 2;
  const tx = add(abs(add(BX, -p.cx)), -half), tz = add(abs(add(BZ, -p.cz)), -half);
  return clamp(add(1.0, mul(-1.0 / blend, max(tx, tz))), 0.0, 1.0);
}
for (const p of patches) write(`worldgen/density_function/patch/mask_${p.biome}.json`, maskOf(p));
// value = desert + sum over patches of mask_p * (patch_value - desert)
function climateFn(key) {
  const d = grid.desert[key];
  let e = d;
  for (const p of patches) e = add(e, mul(`${NS}:patch/mask_${p.biome}`, +(p[key] - d).toFixed(6)));
  return flat(e);
}
for (const key of CLIM) write(`worldgen/density_function/overworld/${OUTNAME[key]}.json`, climateFn(key));

// ---- copy of vanilla's overworld density function graph (every minecraft:overworld/... reference becomes ours)
const rw = n => typeof n === 'string' ? n.replace(/^minecraft:overworld\//, NS + ':overworld/')
  : Array.isArray(n) ? n.map(rw) : (n && typeof n === 'object') ? Object.fromEntries(Object.entries(n).map(([k, v]) => [k, rw(v)])) : n;
const COPIED = ['base_3d_noise', 'depth', 'factor', 'jaggedness', 'offset', 'ridges_folded', 'sloped_cheese'];
for (const f of COPIED) write(`worldgen/density_function/overworld/${f}.json`, rw(readV(`density_function/overworld/${f}.json`)));

// ---- noise settings: vanilla overworld, our router, no aquifers, no cave terms in the final density
const ns = readV('noise_settings/overworld.json');
const router = rw(ns.noise_router);
router.temperature = `${NS}:overworld/temperature`;
router.vegetation = `${NS}:overworld/vegetation`;
// final density: drop min(..., noodle) and replace the range_choice (entrances / cheese caves / spaghetti / pillars) by sloped_cheese itself
const SLOPED = `${NS}:overworld/sloped_cheese`;
let foundNoodle = false, foundRange = 0;
let fd = router.final_density;
if (fd.type === 'minecraft:min' && fd.argument2 === `${NS}:overworld/caves/noodle`) { fd = fd.argument1; foundNoodle = true; }
const strip = n => {
  if (Array.isArray(n)) return n.map(strip);
  if (n && typeof n === 'object') {
    if (n.type === 'minecraft:range_choice' && n.input === SLOPED) { foundRange++; return SLOPED; }
    return Object.fromEntries(Object.entries(n).map(([k, v]) => [k, strip(v)]));
  }
  return n;
};
fd = strip(fd);
if (!foundNoodle || foundRange !== 1) throw new Error('final_density no longer has the expected cave structure (noodle ' + foundNoodle + ', range_choice ' + foundRange + ')');
if (JSON.stringify(fd).includes('caves/')) throw new Error('a cave function is still referenced');
router.final_density = fd;
ns.noise_router = router;
ns.aquifers_enabled = false;
const BIOMES = ['desert', ...patches.map(p => p.biome)];
const ownBiome = s => (typeof s === 'string' && s.startsWith('minecraft:') && BIOMES.includes(s.slice(10))) ? NS + ':' + s.slice(10) : s;
const mapStr = (n, f) => Array.isArray(n) ? n.map(x => mapStr(x, f)) : (n && typeof n === 'object') ? Object.fromEntries(Object.entries(n).map(([k, v]) => [k, mapStr(v, f)])) : f(n);
ns.surface_rule = mapStr(ns.surface_rule, ownBiome);
write('worldgen/noise_settings/arrakis.json', ns);

// ---- biome copies: carvers emptied, cave-style features removed, everything else byte for byte
const removed = new Set(grid.removed_features), seenRemoved = new Set();
for (const b of BIOMES) {
  const j = readV(`biome/${b}.json`);
  j.carvers = [];
  j.features = j.features.map(step => step.filter(f => { if (removed.has(f)) { seenRemoved.add(f); return false; } return true; }));
  write(`worldgen/biome/${b}.json`, j);
}
for (const f of removed) if (!seenRemoved.has(f)) console.warn('note: removed feature never used by these biomes: ' + f);

// ---- biome source: vanilla's parameter points of these 11 biomes, pointing at our copies
const paramsFile = path.join(ROOT, 'build/rw/params.json');
const all = JSON.parse(fs.readFileSync(paramsFile, 'utf8'));
const mine = all.filter(x => BIOMES.includes(x.biome.slice(10))).map(x => ({ biome: NS + ':' + x.biome.slice(10), parameters: x.parameters }));
// check each tuple against the subset the way Climate does (distance to the box, quantised to 1e-4)
const q = v => Math.trunc(v * 10000);
const dist = (v, r) => { const lo = q(Array.isArray(r) ? r[0] : r), hi = q(Array.isArray(r) ? r[1] : r); return Math.max(v - hi, lo - v, 0); };
function nearest(t) {
  const tv = { temperature: q(t.temperature), humidity: q(t.humidity), continentalness: q(t.continentalness), erosion: q(t.erosion), weirdness: q(t.weirdness), depth: 0 };
  let best = Infinity, who = new Set();
  for (const m of mine) {
    const P = m.parameters; let d = 0;
    for (const k of ['temperature', 'humidity', 'continentalness', 'erosion', 'weirdness', 'depth']) d += dist(tv[k], P[k]) ** 2;
    d += q(P.offset) ** 2;
    if (d < best) { best = d; who = new Set([m.biome]); } else if (d === best) who.add(m.biome);
  }
  return { best, who: [...who] };
}
let bad = 0;
for (const t of [{ biome: 'desert', ...grid.desert }, ...patches]) {
  const r = nearest(t);
  const ok = r.best === 0 && r.who.length === 1 && r.who[0] === NS + ':' + t.biome;
  if (!ok) { bad++; console.warn(`WARNING tuple of ${t.biome}: nearest ${r.who.join(',')} distance ${r.best}`); }
}
if (bad) console.warn(bad + ' tuple(s) do not select their own biome uniquely');
const gen = { type: 'minecraft:noise', settings: `${NS}:arrakis`, biome_source: { type: 'minecraft:multi_noise', biomes: mine } };
write('worldgen/world_preset/arrakis.json', {
  dimensions: {
    'minecraft:overworld': { type: 'minecraft:overworld', generator: gen },
    'minecraft:the_end': { type: 'minecraft:the_end', generator: { type: 'minecraft:noise', biome_source: { type: 'minecraft:the_end' }, settings: 'minecraft:end' } },
    'minecraft:the_nether': { type: 'minecraft:the_nether', generator: { type: 'minecraft:noise', biome_source: { type: 'minecraft:multi_noise', preset: 'minecraft:nether' }, settings: 'minecraft:nether' } }
  }
});
// TEST ONLY (WITH_CAVES=1): the same generator with vanilla's cave terms kept in the final density, to measure what removing them changes at the surface.
if (process.env.WITH_CAVES) {
  for (const f of fs.readdirSync(path.join(VAN, 'density_function/overworld/caves'))) write(`worldgen/density_function/overworld/caves/${f}`, rw(readV(`density_function/overworld/caves/${f}`)));
  const nc = JSON.parse(JSON.stringify(ns)); nc.noise_router.final_density = rw(readV('noise_settings/overworld.json').noise_router.final_density);
  write('worldgen/noise_settings/arrakis_caves.json', nc);
  const pc = JSON.parse(fs.readFileSync(path.join(OUT, 'worldgen/world_preset/arrakis.json'), 'utf8')); pc.dimensions['minecraft:overworld'].generator.settings = `${NS}:arrakis_caves`;
  write('worldgen/world_preset/arrakis_caves.json', pc);
  console.log('test-only arrakis_caves preset written; run gen.js again without WITH_CAVES to remove it');
}
console.log(`wrote ${patches.length} patch masks, 5 climate functions, ${COPIED.length} copied functions, noise_settings, ${BIOMES.length} biomes, world preset (${mine.length} parameter points)`);
