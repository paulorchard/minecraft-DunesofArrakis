// TEST ONLY: writes variant world types (v1..v3) to find what makes dune chunks slow. Delete them with: node tools/rewrite/gen.js
const fs = require('fs'), path = require('path');
const NS = 'islandcraft_dunesofarrakis', B = path.join(__dirname, '../../src/main/resources/data', NS, 'worldgen');
const J = f => JSON.parse(fs.readFileSync(path.join(B, f), 'utf8')), W = (f, o) => fs.writeFileSync(path.join(B, f), JSON.stringify(o));
const base = J('noise_settings/arrakis_native.json'), pr = J('world_preset/arrakis_native.json');
const duneOnly = ns => { ns.noise_router.final_density = NS + ':arrakis_final_density'; ns.noise_router.preliminary_surface_level = NS + ':arrakis_surface_height'; return ns; };
const sandOnly = { type: 'minecraft:condition', if_true: { type: 'minecraft:biome', biome_is: [NS + ':arrakis_desert'] }, then_run: { type: 'minecraft:block', result_state: { Name: 'minecraft:sand' } } };
const variants = {
  v1: duneOnly(structuredClone(base)),                                   // dune density only, vanilla surface rule + big biome source
  v2: Object.assign(duneOnly(structuredClone(base)), { surface_rule: sandOnly }),   // + surface rule = sand only
  v3: Object.assign(duneOnly(structuredClone(base)), { surface_rule: sandOnly })    // + one-biome source
};
for (const [k, ns] of Object.entries(variants)) {
  W(`noise_settings/arrakis_${k}.json`, ns);
  const p = structuredClone(pr); p.dimensions['minecraft:overworld'].generator.settings = `${NS}:arrakis_${k}`;
  if (k === 'v3') p.dimensions['minecraft:overworld'].generator.biome_source = { type: 'minecraft:fixed', biome: NS + ':arrakis_desert' };
  W(`world_preset/arrakis_${k}.json`, p);
}
// second round: v4 = v3 + ore veins off; v5 = v4 + all other router entries 0; v6 = the old build's noise settings verbatim, in the overworld
const old = JSON.parse(require('child_process').execSync('git show 8ec4142:src/main/resources/data/islandcraft_dunesofarrakis/worldgen/noise_settings/arrakis.json', { encoding: 'utf8' }));
const v4 = structuredClone(variants.v3); v4.ore_veins_enabled = false;
const v5 = structuredClone(v4); for (const k of Object.keys(v5.noise_router)) if (!['final_density', 'preliminary_surface_level'].includes(k)) v5.noise_router[k] = 0.0;
for (const [k, ns] of Object.entries({ v4, v5, v6: old })) {
  W(`noise_settings/arrakis_${k}.json`, ns);
  const p = structuredClone(pr); p.dimensions['minecraft:overworld'].generator.settings = `${NS}:arrakis_${k}`;
  p.dimensions['minecraft:overworld'].generator.biome_source = { type: 'minecraft:fixed', biome: NS + ':arrakis_desert' };
  W(`world_preset/arrakis_${k}.json`, p);
}
// third round: v7 = old settings + surface rule [bedrock, biome->sand]; v8 = old settings but stone / water / sea 63 (old rule)
const v7 = structuredClone(old); v7.surface_rule.sequence[1] = sandOnly;
const v8 = structuredClone(old); v8.default_block = { Name: 'minecraft:stone' }; v8.default_fluid = { Name: 'minecraft:water', Properties: { level: '0' } }; v8.sea_level = 63;
for (const [k, ns] of Object.entries({ v7, v8 })) {
  W(`noise_settings/arrakis_${k}.json`, ns);
  const p = structuredClone(pr); p.dimensions['minecraft:overworld'].generator.settings = `${NS}:arrakis_${k}`;
  p.dimensions['minecraft:overworld'].generator.biome_source = { type: 'minecraft:fixed', biome: NS + ':arrakis_desert' };
  W(`world_preset/arrakis_${k}.json`, p);
}
// fourth round (blend cost, real biome source and surface rule kept): n1 = blend without the vanilla branch (0 instead); n2 = full blend, climate router entries 0
const nat = structuredClone(base), N = NS + ':island/w';
const n1 = structuredClone(nat), fd = n1.noise_router.final_density; fd.argument1 = { type: 'minecraft:mul', argument1: N, argument2: 0.0 };
const n2 = structuredClone(nat); for (const k of ['continents', 'erosion', 'ridges', 'temperature', 'vegetation']) n2.noise_router[k] = 0.0;
for (const [k, ns] of Object.entries({ n1, n2 })) {
  W(`noise_settings/arrakis_${k}.json`, ns);
  const p = structuredClone(pr); p.dimensions['minecraft:overworld'].generator.settings = `${NS}:arrakis_${k}`; W(`world_preset/arrakis_${k}.json`, p);
}
