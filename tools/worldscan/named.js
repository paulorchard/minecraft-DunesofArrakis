#!/usr/bin/env node
// Named-feature comparison (prompt 11, check 7): for features listed by `spire.sh named`, compare the analytic block top of every column of the
// feature's footprint (rockmap.i16 from relief.sh) with the generated top (heights i16 saved by windowcheck_relief.js --save).
//   node named.js <named.tsv> <heights.i16> <rockmap.i16> <x0> <z0> <size>
// Lines of named.tsv: label  type  cx  cz  reach  height  topradius-or-crestwidth
// Reports per feature: columns compared, columns where the generated top is lower / higher than the analytic one, the worst difference (blocks) with its
// coordinates, the analytic and generated top at the highest analytic column, and the differences of the columns at 75%, 50% and 25% of the feature's height.
// A generated top higher than the analytic one is dune sand banked over the rock (not a shaved top); lower is a fail beyond 1 block.
const fs = require('fs');
const [tsv, hFile, rFile, X0, Z0, SIZE] = process.argv.slice(2);
const x0 = +X0, z0 = +Z0, S = +SIZE, NO = -32768, SAND = 63;
const rd = f => { const b = fs.readFileSync(f); return new Int16Array(b.buffer.slice(b.byteOffset, b.byteOffset + b.length)); };
const gen = rd(hFile), ana = rd(rFile);
if (gen.length !== S * S || ana.length !== S * S) { console.error('size mismatch'); process.exit(1); }
let worst = { d: 0, label: '-', at: '-' }, totalCols = 0, shaved = 0, tops = 0, topsLow = 0;
const rows = [];
for (const line of fs.readFileSync(tsv, 'utf8').split('\n').filter(l => l.trim())) {
  const [label, type, cx, cz, reach, h, w] = line.split('\t'); const R = +reach, X = +cx, Z = +cz;
  let n = 0, low = 0, high = 0, wl = 0, wlAt = '-', topA = -1e9, topAt = null, lo = 1e9, cnt = 0;
  const cols = [];
  for (let z = Math.floor(Z - R); z <= Math.ceil(Z + R); z++) for (let x = Math.floor(X - R); x <= Math.ceil(X + R); x++) {
    const i = x - x0, j = z - z0; if (i < 0 || j < 0 || i >= S || j >= S) continue;
    const a = ana[j * S + i], g = gen[j * S + i]; if (a === NO || g === NO) continue;
    if (Math.hypot(x - X, z - Z) > R) continue;
    n++; const d = (g - SAND) - (a - SAND); // generated minus analytic (both absolute y)
    if (d < 0) low++; if (d > 0) high++;
    if (d < wl) { wl = d; wlAt = `(${x}, ${z})`; }
    cols.push([x, z, a, g]); if (a > topA) { topA = a; topAt = [x, z, g]; } if (a < lo) lo = a;
  }
  totalCols += n; shaved += cols.filter(c => c[3] < c[2] - 1).length;
  if (wl < worst.d) worst = { d: wl, label, at: wlAt };
  // columns at 75, 50 and 25% of the height above the feature's lowest column
  const q = f => { const t = lo + f * (topA - lo); let b = null; for (const c of cols) if (b === null || Math.abs(c[2] - t) < Math.abs(b[2] - t)) b = c; return b ? `${b[3] - b[2]}@${b[2]}` : '-'; };
  const tk = topAt ? topAt[2] - topA : NaN; tops++; if (tk < -1) topsLow++;
  rows.push(`${label.padEnd(14)} ${type.padEnd(6)} (${X}, ${Z}) h ${h}: ${n} cols, generated lower ${low}, higher ${high}, worst lower ${wl} at ${wlAt}; top: analytic y ${topA} generated ${topAt ? topAt[2] : '-'} (diff ${tk}); diff at 75/50/25% of the height: ${q(.75)} ${q(.5)} ${q(.25)}`);
}
console.log(rows.join('\n'));
console.log(`features ${tops}, columns compared ${totalCols}, columns where the generated top is more than 1 block lower than the analytic one: ${shaved}; features whose top column is more than 1 block lower: ${topsLow}; worst lower difference ${worst.d} blocks (${worst.label} ${worst.at})`);
