const fs = require('fs'); const rows = {};
for (let sd = 1; sd <= 20; sd++) {
  let cur = null; for (const l of fs.readFileSync(`stats_s${sd}.txt`, 'utf8').split('\n')) {
    let m = l.match(/^== (\w+( isl)?): kind 1 rock ([\d.]+) km2/); if (m) { cur = m[1]; continue; } if (!cur) continue;
    const r = rows[cur] = rows[cur] || { dens: [], share: [], free: [], drop: [], over110: 0, big: [], clust: [], ncl: [] };
    if ((m = l.match(/features per km2 of kind 1 rock: ([\d.]+)/))) r.dens.push([+m[1], sd]);
    if ((m = l.match(/share of the kind 1 rock inside clusters: ([\d.]+)%/))) r.share.push([+m[1], sd]);
    if ((m = l.match(/free circle: diameter (\d+) m/))) r.free.push([+m[1], sd]);
    if ((m = l.match(/in core ([\d.]+); 0-60 m ([\d.]+); 60-120 m ([\d.]+); 120-180 m ([\d.]+); 180-240 m ([\d.]+)/))) r.drop.push([[+m[1], +m[2], +m[3], +m[4], +m[5]], sd]);
    if ((m = l.match(/above 110: (\d+)/))) r.over110 += +m[1];
    if ((m = l.match(/clusters: (\d+), .* of 0.05-0.5 km2: (\d+)/))) r.ncl.push([+m[1], +m[2]]);
    if ((m = l.match(/top above sand .*max ([\d.]+)/))) r.big.push([+m[1], sd]);
  }
}
const mm = (a, f) => { const s = [...a].sort((x, y) => x[0] - y[0]); return f ? s[s.length - 1] : s[0]; };
for (const [c, r] of Object.entries(rows)) { if (!r.dens.length) continue;
  const mon = r.drop.filter(d => d[0].every((v, i, a) => i == 0 || v <= a[i - 1] + 1e-9)).length;
  console.log(`${c.padEnd(12)} seeds ${r.dens.length}: features/km2 min ${mm(r.dens)[0]} (s${mm(r.dens)[1]}) max ${mm(r.dens, 1)[0]} (s${mm(r.dens, 1)[1]}); cluster share min ${mm(r.share)[0]}% (s${mm(r.share)[1]}) max ${mm(r.share, 1)[0]}% (s${mm(r.share, 1)[1]}); free circle min ${mm(r.free)[0]} m (s${mm(r.free)[1]}); thinning monotone in ${mon} of ${r.drop.length}; spires above 110: ${r.over110}; max top ${mm(r.big, 1)[0]} (s${mm(r.big, 1)[1]})`); }
