#!/usr/bin/env node
// Vertical slice of generated chunks along a line (prompt 06): sand, sandstone and air, with a height scale.
//   node slice.js <regionDir> <x1> <z1> <x2> <z2> <out.png> [--ymin 40] [--ymax 190] [--hscale 1] [--vscale 3]
// One pixel column per block along the line (times hscale), vscale pixels per block of height. Grid lines every 10 blocks above the
// sand level (y 63), heavier every 50; the labels are heights above the sand. Also prints, along the line, the surface and block
// below the surface so a slice can be checked in numbers: where it is sandstone below sand level (the vertical-sided body) and where
// the surface is dune sand on top of the rock.
const fs = require('fs'), path = require('path'), zlib = require('zlib');
const { chunks, unpack } = require('./nbt.js');
const args = process.argv.slice(2), pos = [], opt = {};
for (let i = 0; i < args.length; i++) { if (args[i].startsWith('--')) { opt[args[i].slice(2)] = args[i + 1]; i++; } else pos.push(args[i]); }
const [dir, X1, Z1, X2, Z2, out] = pos;
if (!out) { console.error('usage: node slice.js <regionDir> <x1> <z1> <x2> <z2> <out.png> [--ymin 40] [--ymax 190] [--hscale 1] [--vscale 3]'); process.exit(1); }
const x1 = +X1, z1 = +Z1, x2 = +X2, z2 = +Z2, YMIN = +(opt.ymin || 40), YMAX = +(opt.ymax || 190), HS = +(opt.hscale || 1), VS = +(opt.vscale || 3), SAND = 63;
const len = Math.round(Math.hypot(x2 - x1, z2 - z1)), n = len + 1;
const pts = []; for (let s = 0; s < n; s++) pts.push([Math.round(x1 + (x2 - x1) * s / len), Math.round(z1 + (z2 - z1) * s / len)]);
const need = new Set(pts.map(p => `${p[0] >> 4},${p[1] >> 4}`));
const secs = new Map(); // "cx,cz" -> sections
const needRegions = new Set(pts.map(p => `r.${p[0] >> 9}.${p[1] >> 9}.mca`));
for (const f of fs.readdirSync(dir).filter(f => needRegions.has(f))) {
  for (const ch of chunks(path.join(dir, f))) {
    const key = `${ch.xPos},${ch.zPos}`; if (!need.has(key) || ch.Status !== 'minecraft:full') continue;
    const list = [];
    for (const s of ch.sections) { const bs = s.block_states; if (!bs) continue; const pal = bs.palette.map(p => p.Name); list.push({ y: s.Y, pal, idx: pal.length > 1 ? unpack(bs.data, Math.max(4, Math.ceil(Math.log2(pal.length))), 4096) : null }); }
    secs.set(key, list);
  }
}
const blockAt = (x, y, z) => {
  const l = secs.get(`${x >> 4},${z >> 4}`); if (!l) return null;
  const s = l.find(q => q.y === (y >> 4)); if (!s) return 'minecraft:air';
  return s.idx ? s.pal[s.idx[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)]] : s.pal[0];
};
const W = n * HS, H = (YMAX - YMIN + 1) * VS, LM = 34; // left margin for the labels
const WW = W + LM;
const px = Buffer.alloc(WW * H * 3);
const set = (x, y, r, g, b) => { if (x < 0 || y < 0 || x >= WW || y >= H) return; const o = (y * WW + x) * 3; px[o] = r; px[o + 1] = g; px[o + 2] = b; };
for (let y = 0; y < H; y++) for (let x = 0; x < WW; x++) set(x, y, 214, 232, 245);
const col = { 'islandcraft_dunesofarrakis:dune_sand': [232, 205, 130], 'minecraft:sandstone': [168, 110, 62], 'minecraft:bedrock': [40, 40, 40] };
const tops = [];
for (let s = 0; s < n; s++) {
  const [x, z] = pts[s]; let top = null, topName = null, atSand = null;
  for (let y = YMAX; y >= YMIN; y--) {
    const b = blockAt(x, y, z); if (b === null) break;
    if (y === SAND - 1) atSand = b;
    const c = b === 'minecraft:air' ? null : (col[b] || [255, 0, 255]);
    if (c) { if (top === null) { top = y; topName = b; } for (let h = 0; h < HS; h++) for (let v = 0; v < VS; v++) set(LM + s * HS + h, (YMAX - y) * VS + v, c[0], c[1], c[2]); }
  }
  tops.push([x, z, top, topName, atSand]);
}
// grid: heights above the sand every 10 blocks (heavier every 50), labels with a 3x5 digit font
const font = { 0: '111101101101111', 1: '010110010010111', 2: '111001111100111', 3: '111001111001111', 4: '101101111001001', 5: '111100111001111', 6: '111100111101111', 7: '111001001001001', 8: '111101111101111', 9: '111101111001111' };
const label = (txt, x0, y0) => { let x = x0; for (const ch of txt) { const g = font[ch]; if (g) for (let i = 0; i < 15; i++) if (g[i] === '1') set(x + (i % 3), y0 + ((i / 3) | 0), 0, 0, 0); x += 4; } };
for (let h = -20; SAND + h <= YMAX; h += 10) {
  const y = SAND + h; if (y < YMIN) continue; const py = (YMAX - y) * VS + (VS >> 1);
  const heavy = h % 50 === 0; for (let x = LM; x < WW; x += heavy ? 1 : 4) set(x, py, heavy ? 0 : 90, heavy ? 0 : 90, heavy ? 0 : 90);
  if (h >= 0 && h % 10 === 0 && (h % 20 === 0 || heavy)) label(String(h), 2, py - 2);
}
// distance ticks every 100 blocks along the line
for (let d = 0; d <= len; d += 100) { for (let y = H - 8; y < H; y++) set(LM + d * HS, y, 0, 0, 0); label(String(d), LM + d * HS + 2, H - 7); }
const chunk = (t, d) => { const b = Buffer.alloc(12 + d.length); b.writeUInt32BE(d.length, 0); b.write(t, 4); d.copy(b, 8); let c = ~0; const T = []; for (let i = 0; i < 256; i++) { let k = i; for (let j = 0; j < 8; j++) k = k & 1 ? 0xedb88320 ^ (k >>> 1) : k >>> 1; T[i] = k >>> 0; } for (let i = 4; i < 8 + d.length; i++) c = T[(c ^ b[i]) & 255] ^ (c >>> 8); b.writeUInt32BE(~c >>> 0, 8 + d.length); return b; };
const ih = Buffer.alloc(13); ih.writeUInt32BE(WW, 0); ih.writeUInt32BE(H, 4); ih[8] = 8; ih[9] = 2;
const raw = Buffer.alloc((WW * 3 + 1) * H); for (let y = 0; y < H; y++) { raw[y * (WW * 3 + 1)] = 0; px.copy(raw, y * (WW * 3 + 1) + 1, y * WW * 3, (y + 1) * WW * 3); }
fs.writeFileSync(out, Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ih), chunk('IDAT', zlib.deflateSync(raw, { level: 6 })), chunk('IEND', Buffer.alloc(0))]));
// numbers
const known = tops.filter(t => t[2] !== null);
let firstRock = tops.findIndex(t => t[3] === 'minecraft:sandstone'), bodyBelowSand = 0, rockTopCols = 0, duneOnRock = 0, maxH = -1e9, maxAt = null, outsideWithSandstone = 0, floating = 0;
for (let s = 0; s < tops.length; s++) {
  const t = tops[s]; if (t[2] === null) continue;
  if (t[3] === 'minecraft:sandstone') rockTopCols++;
  if (t[4] === 'minecraft:sandstone') bodyBelowSand++;
  if (t[3] === 'islandcraft_dunesofarrakis:dune_sand' && t[4] === 'minecraft:sandstone') duneOnRock++;
  if (t[2] - SAND > maxH) { maxH = t[2] - SAND; maxAt = [t[0], t[1]]; }
  if (t[4] !== 'minecraft:sandstone' && t[3] === 'minecraft:sandstone') outsideWithSandstone++; // sandstone surface above sand level but no sandstone body below the sand level: impossible if the body is vertical-sided
}
console.log(`slice ${out}: ${n} blocks from (${x1},${z1}) to (${x2},${z2}), y ${YMIN}..${YMAX}, ${known.length} columns read`);
console.log(`columns with sandstone at y=${SAND - 1} (the body below sand level): ${bodyBelowSand}; sandstone surface columns ${rockTopCols}; dune sand on top of rock ${duneOnRock}; sandstone surface without a body below ${outsideWithSandstone}; highest surface ${maxH} above sand at (${maxAt})`);
const rows = []; for (let s = 0; s < tops.length; s += Math.max(1, Math.round(len / 24))) { const t = tops[s]; rows.push(`${s}:${t[2] === null ? '-' : t[2] - SAND}${t[3] === 'minecraft:sandstone' ? 'R' : 'S'}${t[4] === 'minecraft:sandstone' ? '|' : ''}`); }
console.log('profile (distance:height above sand, R rock top, S sand top, | = sandstone below sand level): ' + rows.join(' '));
