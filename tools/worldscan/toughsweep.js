#!/usr/bin/env node
// Summary of a tough.sh stats sweep (prompt 09): min and max over the seeds of each invariant, with the worst seed.
//   node toughsweep.js docs/tough/sweep_20_seeds_override3.txt
const t = require('fs').readFileSync(process.argv[2], 'utf8').split('seed ').slice(1);
const num = (b, re) => { const m = b.match(re); return m ? m.slice(1).map(Number) : null; };
const rows = t.map(b => {
  const seed = +b.split(',')[0];
  const cov = ['purple', 'green', 'blue', 'brown', 'red'].map(n => { const m = b.match(new RegExp(n + '\\s+rock\\s+[\\d.]+ km2\\s+tough\\s+[\\d.]+ km2\\s+=\\s+([\\d.]+)%')); return m ? +m[1] : null; });
  return { seed, cov, big: num(b, /at least 100 x 100 m: (\d+)/), mx: num(b, /highest rock surface ([\d.]+)/), holes: num(b, /holes.*: (\d+)/), near: num(b, /within 1 block: (\d+) of (\d+)/), ap: num(b, /talus apron.*: ([\d.]+)%/), top: num(b, /plateau top height above sand \(m\): min ([\d.]+) p10 ([\d.]+) median ([\d.]+) p90 ([\d.]+) max ([\d.]+)/) };
});
const mm = (f, name) => {
  const v = rows.map(r => [f(r), r.seed]).filter(x => x[0] != null && !isNaN(x[0])).sort((a, b) => a[0] - b[0]);
  if (!v.length) return;
  console.log(name.padEnd(50), 'min', v[0][0].toFixed(1), '(seed ' + v[0][1] + ')  max', v[v.length - 1][0].toFixed(1), '(seed ' + v[v.length - 1][1] + ')');
};
mm(r => r.cov[0], 'tough share of purple rock area %');
mm(r => r.cov[1], 'tough share of green rock area %');
mm(r => r.cov[2], 'tough share of blue rock area %');
mm(r => r.big && r.big[0], 'plateaus of at least 100 x 100 m (all land)');
mm(r => r.mx && r.mx[0], 'highest rock surface above sand (limit about 165)');
mm(r => r.holes && r.holes[0], 'holes in tough rock');
mm(r => r.near && r.near[1] ? 100 * r.near[0] / r.near[1] : null, 'plateaus with a neighbour top within 1 block, %');
mm(r => r.ap && r.ap[0], 'cliff length with a talus apron %');
mm(r => r.top && r.top[2], 'median plateau top above sand (m)');
