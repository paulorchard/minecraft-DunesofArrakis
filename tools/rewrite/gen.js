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

// ---- noise settings: vanilla, two changes
const ns = readV('noise_settings/overworld.json');
ns.default_fluid = { Name: NS + ':dune_sand' };
const v = ns.surface_rule.sequence;
if (v.length !== 3 || JSON.stringify(v[0]).indexOf('bedrock') < 0 || JSON.stringify(v[2]).indexOf('deepslate') < 0) throw new Error('vanilla surface rule skeleton changed');
// v[1] is the biome-specific part. Replacing it with a stone block would overwrite the Dune Sand (above_preliminary_surface covers the sand),
// and stone is already the default block, so it is dropped: the rule is the bedrock floor and the deepslate transition only.
ns.surface_rule = { type: 'minecraft:sequence', sequence: [v[0], v[2]] };
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
console.log('CARVERS', b.carvers.join(' '));

// ---- world type
write('world_preset/arrakis.json', { dimensions: {
  'minecraft:overworld': { type: 'minecraft:overworld', generator: { type: 'minecraft:noise', settings: NS + ':arrakis', biome_source: { type: 'minecraft:fixed', biome: NS + ':arrakis_stony_peaks' } } },
  'minecraft:the_end': { type: 'minecraft:the_end', generator: { type: 'minecraft:noise', biome_source: { type: 'minecraft:the_end' }, settings: 'minecraft:end' } },
  'minecraft:the_nether': { type: 'minecraft:the_nether', generator: { type: 'minecraft:noise', biome_source: { type: 'minecraft:multi_noise', preset: 'minecraft:nether' }, settings: 'minecraft:nether' } } } });
