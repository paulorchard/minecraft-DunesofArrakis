// Dune cover of the islands in one generated window (prompt 08).
//   node islandburial.js <heights.i16> <rockmap.i16> <islandmask.i16> <size> [<islands list file: "id role group x z major minor angle" lines from island.sh list>]
// heights: generated top block y per column (windowcheck_relief --save); rockmap: analytic top block y of the rock (-32768 = no rock);
// islandmask: 0 sand, -1 mapped rock, k+1 island k. A rock column is "covered" when the generated top is above the rock top (dune sand lies on it).
// Prints per island: columns, covered share, highest rock above the generated surface; and totals (islands more than 80% covered, fully covered).
const fs = require('fs');
const [hf, rf, mf, sizeS, listFile] = process.argv.slice(2);
const S = +sizeS;
const rd = f => { const b = fs.readFileSync(f); return new Int16Array(b.buffer.slice(b.byteOffset, b.byteOffset + b.length)); };
const h = rd(hf), r = rd(rf), m = rd(mf);
const per = new Map();
for (let i = 0; i < S * S; i++) {
  const k = m[i];
  if (k <= 0 || r[i] === -32768) continue;
  let e = per.get(k); if (!e) { e = { cols: 0, covered: 0, maxRock: -1e9, maxTop: -1e9, minCoverDepth: 0 }; per.set(k, e); }
  e.cols++;
  if (h[i] > r[i]) e.covered++;
  e.maxRock = Math.max(e.maxRock, r[i]);
  e.maxTop = Math.max(e.maxTop, h[i]);
}
let islands = 0, over80 = 0, full = 0, cols = 0, cov = 0, peakHidden = 0, anyCover = 0;
const lines = [];
for (const [k, e] of [...per.entries()].sort((a, b) => a[0] - b[0])) {
  islands++; cols += e.cols; cov += e.covered;
  const share = e.covered / e.cols;
  if (share >= 0.8) over80++;
  if (e.covered === e.cols) full++;
  if (e.covered > 0) anyCover++;
  if (e.maxTop > e.maxRock) peakHidden++; // the highest generated surface of the island is sand
  lines.push(`island index ${k - 1}: ${e.cols} rock columns, ${(100 * share).toFixed(0)}% under dune sand, rock peak y ${e.maxRock}, highest surface y ${e.maxTop}`);
}
console.log(lines.join('\n'));
console.log(`islands with rock in this window ${islands}; rock columns ${cols}, covered by dune sand ${cov} (${cols ? (100 * cov / cols).toFixed(1) : 0}%); islands with at least 80% of their columns covered ${over80}; fully covered ${full}; any column covered ${anyCover}; peak under sand ${peakHidden}`);
