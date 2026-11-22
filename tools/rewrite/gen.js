#!/usr/bin/env node
// Generates the three data files of the vanilla Dune Sand sea world (run tools/rewrite/extract-vanilla.sh once first):
//   worldgen/noise_settings/arrakis.json   vanilla overworld noise settings; default_fluid = Dune Sand; surface rule = bedrock + deepslate only (stone is the default block)
//   worldgen/biome/arrakis_stony_peaks.json vanilla stony_peaks: ores only, no spawns, desert weather and look
//   worldgen/world_preset/arrakis.json      overworld = noise + these settings + fixed biome source
const fs = require('fs'), path = require('path');
const ROOT = path.resolve(__dirname, '..', '..');
const NS = 'islandcraft_dunesofarrakis';
const VAN = path.join(ROOT, 'build/vanilla/data/minecraft/worldgen');
const OUT = path.join(ROOT, 'src/main/resources/data', NS, 'worldgen');
const readV = rel => JSON.parse(fs.readFileSync(path.join(VAN, rel), 'utf8'));
const write = (rel, obj) => { const p = path.join(OUT, rel); fs.mkdirSync(path.dirname(p), { recursive: true }); fs.writeFileSync(p, JSON.stringify(obj, null, 2) + '\n'); };

const OC = JSON.parse(fs.readFileSync(path.join(__dirname, 'ocean.json'), 'utf8'));
const COPY = ['continents', 'offset', 'factor', 'jaggedness', 'depth', 'sloped_cheese'];
const ours = s => s.replace(new RegExp('"minecraft:overworld/(' + COPY.join('|') + ')"', 'g'), '"' + NS + ':overworld/$1"');
let SPLINE_NOTE;
// ---- noise settings: vanilla, three changes (default_fluid, surface rule, router entries that reach continentalness point at our copies)
const ns = readV('noise_settings/overworld.json');
ns.default_fluid = { Name: NS + ':dune_sand' };
const v = ns.surface_rule.sequence;
if (v.length !== 3 || JSON.stringify(v[0]).indexOf('bedrock') < 0 || JSON.stringify(v[2]).indexOf('deepslate') < 0) throw new Error('vanilla surface rule skeleton changed');
// v[1] is the biome-specific part. Replacing it with a stone block would overwrite the Dune Sand (above_preliminary_surface covers the sand),
// and stone is already the default block, so it is dropped: the rule is the bedrock floor and the deepslate transition only.
ns.surface_rule = { type: 'minecraft:sequence', sequence: [v[0], v[2]] };
ns.noise_router = JSON.parse(ours(JSON.stringify(ns.noise_router)));
const DUNES = require("./dunes.js").applyDunes(ns, OC, NS);   // prompt 25: null when dune_enabled is false (then the output equals the world-v1.0-base data)
write('noise_settings/arrakis.json', ns);
const names = new Set(JSON.stringify(ns.surface_rule).match(/"Name": ?"[^"]+"/g));
console.log('SURFACE RULE BLOCKS', [...names].join(' '), '+ default block', ns.default_block.Name, '+ default fluid', ns.default_fluid.Name, '(sea_level ' + ns.sea_level + ')');

// ---- biome
const b = readV('biome/stony_peaks.json'), desert = readV('biome/desert.json');
const was = b.features.flat().length;
const kept = new Set(b.features[6].filter(f => /^minecraft:ore_/.test(f) && f !== 'minecraft:ore_infested'));
b.features = b.features.map((s, i) => i === 6 ? s.filter(f => kept.has(f)) : []);
console.log('FEATURES kept ' + b.features.flat().length + ', removed ' + (was - b.features.flat().length) + ': ' + b.features.flat().join(' '));
b.spawners = Object.fromEntries(Object.keys(b.spawners).map(k => [k, []]));
for (const k of ['monster', 'creature', 'ambient', 'axolotls', 'underground_water_creature', 'water_creature', 'water_ambient', 'misc']) b.spawners[k] = [];
b.creature_spawn_probability = 0.0; b.spawn_costs = {};
b.has_precipitation = false; b.temperature = 2.0; b.downfall = 0.0; delete b.temperature_modifier;
b.attributes = JSON.parse(JSON.stringify(desert.attributes)); b.effects = JSON.parse(JSON.stringify(desert.effects));
write('biome/arrakis_stony_peaks.json', b);
const SEA_BIOME = !!OC.dune_enabled;   // prompt 26: with dunes there is a second biome, identical to stony peaks except the id, for the open sand sea
if (SEA_BIOME) write('biome/arrakis_sand_sea.json', b); else fs.rmSync(path.join(OUT, 'biome/arrakis_sand_sea.json'), { force: true });
console.log('CARVERS', b.carvers.join(' '));

// ---- world type
// multi_noise biome source (prompt 26): sand sea below sea_biome_cut, stony peaks at or above it; continentalness is quantised as trunc(v * 10000) (Climate.quantizeCoord),
// so the two ranges end on half steps of different quantised integers: sand sea q <= q(cut) - 1, stony peaks q >= q(cut): no gap, no overlap. All other parameters take the full range -2..2.
function seaBiomeSource() {
  const cut = OC.sea_biome_cut === undefined ? OC.dune_gate_end : OC.sea_biome_cut, q = Math.trunc(Math.round(cut * 1e6) / 100), half = v => v + (v < 0 ? -0.5 : 0.5);
  const full = [-2.0, 2.0], mk = (biome, c) => ({ biome: NS + ':' + biome, parameters: { temperature: full, humidity: full, continentalness: c, erosion: full, depth: full, weirdness: full, offset: 0.0 } });
  console.log('BIOME CUT', cut, 'quantised', q, 'sand sea max', half(q - 1) / 1e4, 'stony peaks min', half(q) / 1e4);
  return { type: 'minecraft:multi_noise', biomes: [mk('arrakis_sand_sea', [-2.0, half(q - 1) / 1e4]), mk('arrakis_stony_peaks', [half(q) / 1e4, 2.0])] };
}
write('world_preset/arrakis.json', { dimensions: {
  'minecraft:overworld': { type: 'minecraft:overworld', generator: { type: 'minecraft:noise', settings: NS + ':arrakis', biome_source: SEA_BIOME ? seaBiomeSource() : { type: 'minecraft:fixed', biome: NS + ':arrakis_stony_peaks' } } },
  'minecraft:the_end': { type: 'minecraft:the_end', generator: { type: 'minecraft:noise', biome_source: { type: 'minecraft:the_end' }, settings: 'minecraft:end' } },
  'minecraft:the_nether': { type: 'minecraft:the_nether', generator: { type: 'minecraft:noise', biome_source: { type: 'minecraft:multi_noise', preset: 'minecraft:nether' }, settings: 'minecraft:nether' } } } });

// ---- Part B (prompt 22): wider, deeper sand ocean. Our own copies of ONLY the density functions that reference continentalness, directly or through each other
// (found by scanning vanilla's density_function folder for references, transitively): overworld/continents, offset, factor, jaggedness, depth, sloped_cheese.
// Everything else (base_3d_noise, erosion, ridges, ridges_folded, shift_x/z, all caves/*) keeps being referenced as minecraft:overworld/...
const readDF = n => JSON.parse(ours(JSON.stringify(readV('density_function/overworld/' + n + '.json'))));
const dfDir = path.join(OUT, 'density_function/overworld'); fs.rmSync(path.join(OUT, 'density_function'), { recursive: true, force: true });
for (const n of COPY) { const j = readDF(n); if (n === 'continents') {
    const sn = j.argument; if (j.type !== 'minecraft:flat_cache' || sn.type !== 'minecraft:shifted_noise' || sn.xz_scale !== 0.25) throw new Error('vanilla continents changed');
    sn.xz_scale = OC.continent_scale; j.argument = { type: 'minecraft:add', argument1: sn, argument2: OC.ocean_bias };
  } else if (n === 'offset') {
    const find = x => { if (x && x.type === 'minecraft:spline') return x.spline; if (x && typeof x === 'object') for (const k in x) { const r = find(x[k]); if (r) return r; } };
    const sp = find(j), P = sp.points;
    const want = [[-1.1, 0.044], [-1.02, -0.2222], [-0.51, -0.2222], [-0.44, -0.12], [-0.18, -0.12]];
    if (sp.coordinate !== NS + ':overworld/continents' || want.some((w, i) => P[i].location !== w[0] || P[i].value !== w[1] || P[i].derivative !== 0) || P[5].location !== -0.16) throw new Error('vanilla offset spline ocean end changed');
    const vOf = y => (y - 128) / 128 + 0.50375;                       // surface y = 128 * offset + 128, offset = -0.50375 + spline value
    const vf = +vOf(OC.floor_y).toFixed(6), end = OC.shelf_end_continentalness, a = +(end - OC.shelf_width_blocks * OC.shelf_gradient_per_block).toFixed(4);
    if (a <= -1.1 || OC.floor_y >= 48) throw new Error('shelf does not fit between continentalness -1.1 and ' + end);
    // ocean end only: flat floor (also removes vanilla's mushroom-island bump below -1.02), then a smooth ramp up to vanilla's shelf value at -0.18. Points from -0.18 up stay as vanilla.
    sp.points = [{ location: -1.1, value: vf, derivative: 0.0 }, { location: a, value: vf, derivative: 0.0 }, P[4], ...P.slice(5)];
    SPLINE_NOTE = { floor_value: vf, floor_y: OC.floor_y, ramp_from_continentalness: a, ramp_to_continentalness: end, ramp_blocks: OC.shelf_width_blocks };
    fs.mkdirSync(path.join(ROOT, 'build/model'), { recursive: true }); fs.writeFileSync(path.join(ROOT, 'build/model/spline.json'), JSON.stringify(sp));
  }
  write('density_function/overworld/' + n + '.json', j);
}
console.log('OCEAN', JSON.stringify(SPLINE_NOTE));

// ---- Part D (prompt 25): dune files (noises, dune_height). Nothing is written when dune_enabled is false.
fs.rmSync(path.join(OUT, 'noise'), { recursive: true, force: true });
if (DUNES) {
  for (const [n, j] of Object.entries(DUNES.noise)) write('noise/' + n + '.json', j);
  for (const [n, j] of Object.entries(DUNES.density)) write('density_function/' + n + '.json', j);
  console.log('DUNES', JSON.stringify(DUNES.info));
}
console.log('SURFACE RULE BLOCKS (final)', [...new Set(JSON.stringify(ns.surface_rule).match(/"Name":"[^"]+"/g))].join(' '));
