const SKIP=["tectonic:region/diamond/dune_base","tectonic:region/diamond/dune_offset","tectonic:region/diamond/wetland","tectonic:parameter/","tectonic:region/selector"];
const fs=require('fs'),p=require('path');const base=process.argv[2];const roots=process.argv.slice(3);
const seen=new Map();const custom=new Set(),noises=new Set();
function visit(id){if(seen.has(id))return;const f=p.join(base,'data/tectonic/worldgen/density_function',id+'.json');if(!fs.existsSync(f)){seen.set(id,null);return}
 const j=JSON.parse(fs.readFileSync(f,'utf8'));seen.set(id,f);
 (function w(o){if(Array.isArray(o))return o.forEach(w);if(o&&typeof o==='object'){if(typeof o.type==='string'&&/^(tectonic|lithostitched):/.test(o.type))custom.add(o.type+' @'+id);if(typeof o.noise==='string')noises.add(o.noise);for(const k in o)w(o[k])}else if(typeof o==='string'&&o.startsWith('tectonic:')&&!SKIP.some(k=>o.startsWith(k))){visit(o.slice(9))}})(j)}
roots.forEach(visit);
console.log('closure files:',[...seen.values()].filter(Boolean).length,'missing refs(noise defs etc):',[...seen.entries()].filter(([k,v])=>!v).map(([k])=>k).join(','));
console.log([...seen.keys()].join('\n'));console.log('--- custom types:\n'+[...custom].join('\n'));console.log('--- noises:',[...noises].join(', '));
