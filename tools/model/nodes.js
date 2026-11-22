#!/usr/bin/env node
// Counts the density function nodes the dunes add (prompt 25, cost question a). Reads the generated data, no server.
const fs = require('fs'), path = require('path');
const ROOT = path.resolve(__dirname, '..', '..'), W = path.join(ROOT, 'src/main/resources/data/islandcraft_dunesofarrakis/worldgen');
const ns = JSON.parse(fs.readFileSync(path.join(W, 'noise_settings/arrakis.json'), 'utf8')), fd = ns.noise_router.final_density;
if (fd.type !== 'minecraft:max') { console.log('no dunes in this data'); process.exit(0); }
const dh = JSON.parse(fs.readFileSync(path.join(W, 'density_function/overworld/dune_height.json'), 'utf8'));
const count = (n, t = {}) => { if (n && typeof n === 'object') { if (n.type) t[n.type] = (t[n.type] || 0) + 1; for (const k in n) count(n[k], t); } return t; };
const sum = t => Object.values(t).reduce((a, b) => a + b, 0);
const term = fd.argument2, tt = count(term), th = count(dh), noises = Object.entries(th).filter(([k]) => k === 'minecraft:noise' || k === 'minecraft:shifted_noise');
console.log('term nodes (router, outside dune_height):', sum(tt), JSON.stringify(tt));
console.log('dune_height nodes:', sum(th), JSON.stringify(th));
const octaves = n => JSON.parse(fs.readFileSync(path.join(W, 'noise', n + '.json'), 'utf8')).amplitudes.length;
console.log('noise octaves per evaluation: dunes', octaves('arrakis_dunes'), ', fields', octaves('arrakis_dune_fields'), ', style', octaves('arrakis_dune_style'), '(each octave = 2 improved-noise lookups)');
console.log('plus one lookup of continents (flat_cache, quart resolution) for the gate');
