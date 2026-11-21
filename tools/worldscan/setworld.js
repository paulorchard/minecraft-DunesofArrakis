#!/usr/bin/env node
// Edit the three switches of arrakis/world.json in place:  node setworld.js <scatter|mapped> <new|old> <dune_height_scale>
const fs = require('fs'), p = 'src/main/resources/data/islandcraft_dunesofarrakis/arrakis/world.json';
let s = fs.readFileSync(p, 'utf8');
const [f, m, d] = process.argv.slice(2);
s = s.replace(/"footprint": "[a-z]+"/, `"footprint": "${f}"`).replace(/"materials": "[a-z]+"/, `"materials": "${m}"`).replace(/"dune_height_scale": [0-9.]+/, `"dune_height_scale": ${d}`);
fs.writeFileSync(p, s);
JSON.parse(s);
console.log(`world.json: footprint ${f}, materials ${m}, dune_height_scale ${d}`);
