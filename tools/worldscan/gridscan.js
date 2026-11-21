#!/usr/bin/env node
// gridscan: per-column surface scan of a generated area (rectangle) for the biome grid.
//   node tools/worldscan/gridscan.js <regionDir> <outPrefix> <x0> <z0> <sizeX> <sizeZ>
// Writes <outPrefix>.bin (little-endian planes, row-major z then x):
//   Int16  top      y of the highest terrain block (stone, sand, dirt, ice, ... : not air, fluid, plants, leaves, logs)
//   Uint8  biome    index into names.biomes of the biome at the top terrain block
//   Uint8  surf     index into names.blocks of the top terrain block
//   Uint8  cover    index into names.blocks of the highest non-air block (water, ice, leaves, ... or the terrain block itself)
//   Uint8  void     count of non-solid blocks (air, water, lava) below the top terrain block (capped 255)
//   Uint8  plant    count of other non-terrain blocks (plants, leaves, logs, ...) below the top terrain block (capped 255)
//   <outPrefix>.vox   Int32 triples (x - x0, y, z - z0) of those non-solid blocks below the top terrain block
// and <outPrefix>.json (header: origin, size, name tables, chunk count). Heights are read from block data.
const { chunks, unpack } = require('./nbt.js');
const fs = require('fs'), path = require('path');
const [dir, outPrefix, X0, Z0, SX, SZ] = process.argv.slice(2);
const x0 = +X0, z0 = +Z0, W = +SX, H = +SZ;
if (!dir || !W || !H || isNaN(x0) || isNaN(z0)) { console.error('usage: gridscan.js <regionDir> <outPrefix> <x0> <z0> <sizeX> <sizeZ>'); process.exit(1); }

const FLUID = /^minecraft:(water|lava|bubble_column)$/, AIR = /^minecraft:(air|cave_air|void_air)$/;
const TERRAIN = /(stone|dirt|sand|gravel|clay|terracotta|ice|snow_block|powder_snow|bedrock|deepslate|andesite|diorite|granite|tuff|calcite|podzol|mud|_ore$|basalt|copper|obsidian|moss_block|mycelium|rooted|coarse|magma|dripstone|grass_block|farmland|path|amethyst|glazed|concrete|planks|cobble|brick)/;
const NOTTERRAIN = /(leaves|_log$|_wood$|snow$|button|pressure|slab|stairs|fence|wall|vine|bush|lichen|mushroom$|sapling|torch|lantern|bamboo)/;
const cls = n => AIR.test(n) || FLUID.test(n) ? 0 : (TERRAIN.test(n) && !NOTTERRAIN.test(n)) ? 2 : 1;
const clsCache = new Map(); const clsOf = n => { let v = clsCache.get(n); if (v === undefined) { v = cls(n); clsCache.set(n, v); } return v; };

const top = new Int16Array(W * H).fill(-32768), biome = new Uint8Array(W * H), surf = new Uint8Array(W * H), cover = new Uint8Array(W * H);
const vd = new Uint8Array(W * H), pl = new Uint8Array(W * H);
const biomeNames = [], blockNames = [];
const bidx = (arr, n) => { let i = arr.indexOf(n); if (i < 0) { i = arr.length; if (i > 255) throw new Error('more than 255 names'); arr.push(n); } return i; };
let nChunks = 0;
const census = {}, biomeSections = {};   // biomeSections: biome name -> number of 16x16x16 sections whose biome palette holds it (every depth, not just the surface)
const census0 = null;   // block name -> count over every block of every scanned chunk (only blocks inside the window rectangle are not separated: whole chunks are counted)
const vox = [];   // every non-solid block below a column's top terrain block: wx, y, wz (to tell overhangs, open to the outside, from sealed pockets)
for (const f of fs.readdirSync(dir).filter(f => f.endsWith('.mca'))) {
  for (const c of chunks(path.join(dir, f))) {
    const bx = c.xPos * 16, bz = c.zPos * 16;
    if (bx + 15 < x0 || bx >= x0 + W || bz + 15 < z0 || bz >= z0 + H) continue;
    if (c.Status !== 'minecraft:full') continue;
    nChunks++;
    const secs = [];
    for (const s of c.sections) {
      const bs = s.block_states; if (!bs) continue;
      const pal = bs.palette.map(p => p.Name), pc = pal.map(clsOf);
      const idx = pal.length > 1 ? unpack(bs.data, Math.max(4, Math.ceil(Math.log2(pal.length))), 4096) : null;
      let bpal = null, bidxs = null;
      if (s.biomes) { bpal = s.biomes.palette; for (const bn0 of new Set(bpal)) biomeSections[bn0] = (biomeSections[bn0] || 0) + 1; bidxs = bpal.length > 1 ? unpack(s.biomes.data, Math.max(1, Math.ceil(Math.log2(bpal.length))), 64) : null; }
      if (!idx) census[pal[0]] = (census[pal[0]] || 0) + 4096;
      else { const cnt = new Uint32Array(pal.length); for (let k = 0; k < 4096; k++) cnt[idx[k]]++; for (let k = 0; k < pal.length; k++) if (cnt[k]) census[pal[k]] = (census[pal[k]] || 0) + cnt[k]; }
      secs.push({ y: s.Y, pal, pc, idx, bpal, bidxs });
    }
    secs.sort((a, b) => b.y - a.y);
    const bn = (s, lx, ly, lz) => { if (!s.bpal) return 'none'; return s.bpal[s.bidxs ? s.bidxs[((ly >> 2) << 4) | ((lz >> 2) << 2) | (lx >> 2)] : 0]; };
    const colTop = new Int32Array(256).fill(-32768);
    for (let i = 0; i < 256; i++) {
      const lx = i & 15, lz = i >> 4;
      if (bx + lx < x0 || bx + lx >= x0 + W || bz + lz < z0 || bz + lz >= z0 + H) continue;
      let any = false;
      outer: for (const s of secs) {
        for (let ly = 15; ly >= 0; ly--) {
          const p = s.idx ? s.idx[(ly << 8) | (lz << 4) | lx] : 0;
          const name = s.pal[p], k = s.pc[p];
          if (!any && !AIR.test(name)) { any = true; cover[((bz + lz - z0) * W) + (bx + lx - x0)] = bidx(blockNames, name); }
          if (k === 2) {
            colTop[i] = s.y * 16 + ly;
            const o = ((bz + lz - z0) * W) + (bx + lx - x0);
            top[o] = colTop[i]; surf[o] = bidx(blockNames, name); biome[o] = bidx(biomeNames, bn(s, lx, ly, lz));
            break outer;
          }
        }
      }
    }
    // everything below the top terrain block that is not terrain
    for (const s of secs) {
      const base = s.y * 16;
      for (let i = 0; i < 256; i++) {
        const ct = colTop[i]; if (ct === -32768 || base >= ct) continue;
        const lx = i & 15, lz = i >> 4, o = ((bz + lz - z0) * W) + (bx + lx - x0);
        if (bx + lx < x0 || bx + lx >= x0 + W || bz + lz < z0 || bz + lz >= z0 + H) continue;
        const lim = Math.min(16, ct - base);
        if (!s.idx) { const k = s.pc[0]; if (k === 2) continue; for (let ly = 0; ly < lim; ly++) { if (k === 0) { vd[o] = Math.min(255, vd[o] + 1); vox.push(bx + lx - x0, base + ly, bz + lz - z0); } else pl[o] = Math.min(255, pl[o] + 1); } continue; }
        for (let ly = 0; ly < lim; ly++) {
          const k = s.pc[s.idx[(ly << 8) | (lz << 4) | lx]];
          if (k === 0) { vd[o] = Math.min(255, vd[o] + 1); vox.push(bx + lx - x0, base + ly, bz + lz - z0); } else if (k === 1) pl[o] = Math.min(255, pl[o] + 1);
        }
      }
    }
  }
}
fs.mkdirSync(path.dirname(outPrefix), { recursive: true });
fs.writeFileSync(outPrefix + '.bin', Buffer.concat([top, biome, surf, cover, vd, pl].map(a => Buffer.from(a.buffer, a.byteOffset, a.byteLength))));
fs.writeFileSync(outPrefix + '.vox', Buffer.from(Int32Array.from(vox).buffer));
fs.writeFileSync(outPrefix + '.json', JSON.stringify({ x0, z0, width: W, height: H, chunks: nChunks, biomes: biomeNames, blocks: blockNames, census, biomeSections }));
console.log(`gridscan: ${nChunks} chunks, ${W}x${H} columns, ${biomeNames.length} biomes, ${blockNames.length} block names -> ${outPrefix}.bin`);
