# Terrain study (prompt 02): what exists for Arrakis on Minecraft 26.1.2 / NeoForge

Research round. No mod code or data was changed. Date: 2026-11-18. Prompt 01 works (build passes, flat sand dimension verified), so the study went ahead.

**Read this first: three limits of this round**

1. **No in-game screenshots.** I cannot drive the Minecraft window. In place of the three-view screenshots I generated real worlds on the dev server (same seed 424242 for every candidate) and rendered **top-down hillshaded height maps** from the saved chunk files, plus per-biome height statistics. This is measured data, but it is a plan view only. There are no ground-level or 100 m views. The coordinates are in every file name. If you want the ground and 100 m views, tell me which seed/coordinates and run `runClient` with the same jars (instructions at the end).
2. **The reference images were not visible to me.** The prompt names `island-render-*-reference` and `continent-sketch-reference` but no image files reached me. I worked from your written description of the targets only.
3. **Terralith was not studied, because of its licence** (section B). I did not download or read it.

---

## A. Candidates

Method: Modrinth API (`versions:26.1.2`, NeoForge loader, worldgen). CurseForge was **not** checked (its site blocks automated access), so "confirm on CurseForge" is unconfirmed; Modrinth listings are confirmed. Download counts are Modrinth project totals.

| Project | 26.1.2 build (NeoForge) | Downloads | Licence (declared) | Data or code | Depends on | Targets 1–5 | Verdict |
|---|---|---|---|---|---|---|---|
| Vanilla 26.1.2 | n/a | n/a | game terms | data (density functions, noise settings) + code | none | 1 weak, 2 none, 3 partial (badlands pillars), 4 partial (carver), 5 partial | Baseline, **studied** |
| Tectonic | 3.0.28-neoforge-26.1 (2026-09-11) | 17.8 M | MIT (jar) / CC0 (repo) | **data + code** (custom density types, 13 mixins) | Lithostitched, Apollib (bundled in jar) | 1 yes (dune recipe), 2 plateaus only, 3 no, 4 river carve only, 5 caves yes (generic) | **Deep study** |
| Regions Unexplored | 0.7.0+beta4-neoforge-26.1 (beta) | 10.2 M | MIT | **biomes + features + surface rules**, no terrain shaping | Lithostitched, Apollib (bundled) | none for terrain; 78 biomes | **Deep study (confirmed: nothing to adopt)** |
| Biomes O' Plenty | 26.1.2.0.22-neoforge (2026-06-20) | 39.0 M | All Rights Reserved | biomes + features (TerraBlender); no density functions | GlitchCore, TerraBlender (not bundled) | none for terrain | **Study only. Jar read, not run in game** (needs 2 more downloads; data shows no terrain shaping) |
| Terralith | 2.6.2 (datapack zip / NeoForge jar, 2026-03-31) | 24.9 M | Stardust Labs License | data | Lithostitched (jar form) | unknown (not read) | **Dropped: AI clause in licence** |
| WWOO (Wythers' Overhauled Overworld) | 2.7.0 beta (2026-07-16) | 2.2 M | CC-BY-NC-ND-4.0 (Modrinth metadata only) | not examined | Terrablender-family lib | unknown | **Dropped** (no derivatives allowed, so cannot be adapted; not downloaded) |
| Lithosphere | 1.9 (2026-11-10) | 1.3 M | All Rights Reserved (Modrinth metadata only) | not examined | Lithostitched | unknown | **Dropped** (study-only licence, capped at 3–4 deep studies) |

Dropped for other reasons: Regions Unexplored's Fabric/Forge builds and the many structure packs (`yungs-better-desert-temples`, `badlandsbiomesdimension`, etc.) are structure or biome-swap content with no terrain shaping. `badlandspyramid`, `grandscale` and `canyons_and_cliffs_and_biomes` appeared in searches but are tiny (under 4 k downloads) or All Rights Reserved; not examined. A search for "dunes" returned nothing for 26.1.2.

Dependency IDs on Modrinth: `XaDC71GB` = **Lithostitched** (MIT, github.com/Apollounknowndev/lithostitched, 25.9 M downloads). `P7dR8mSH` = Fabric API (Fabric builds only).

### Files downloaded (official Modrinth CDN, announced before download)

| File | Version | Size (bytes) | SHA-256 |
|---|---|---|---|
| `tectonic-3.0.28-neoforge-26.1.jar` | 3.0.28 | 447,700 | `e80f5ba26aa6deeeabaa75ce0e4f602a3d81b53c5557122799d27b7352e8b7ca` |
| `regions-unexplored-0.7.0+beta4-neoforge-26.1.jar` | 0.7.0+beta4 | 6,729,897 | `a4be3aa0c3e0474b943860cd0c51fa0469c5ea1f717fe12cebd5e054f001758c` |
| `BiomesOPlenty-neoforge-26.1.2-26.1.2.0.22.jar` | 26.1.2.0.22 | 22,838,154 | `9f0853d3ba2bd9eaf25eee5438d601f8222bb4bd3ef62e8d3afe56011c761209` |
| `lithostitched-1.8.1-neoforge-26.1.jar` | 1.8.1 | 1,069,275 | `bbe669da8671b2da36d3b89fbc113d28c7d92f3fe5b6709d0e6875e5f867d05d` |

Source URLs: `https://cdn.modrinth.com/data/lWDHr9jE/versions/RNLd3nvP/…`, `…/Tkikq67H/versions/CgLkBfwk/…`, `…/HXF82T3G/versions/mqkuMsI9/…`, `…/XaDC71GB/versions/pLlJIqyp/…`. Sizes matched the API listing. Nothing was run except by the game loader. They are kept in `run-server/study-downloads/` (git-ignored); during tests they were copied into `run-server/mods/` and removed afterwards.

**Dev environment loads downloaded jars: confirmed.** Jars in `run-server/mods` load normally in 26.1.2 dev (Tectonic, Lithostitched and Apollib all appeared in the mod list; Tectonic's data pack auto-loaded as `mod/tectonic:tectonic`). Nested jar-in-jar (Apollib) also works. One oddity: with Regions Unexplored on the **dedicated server**, the log shows `NeoForgeDevDistCleaner … Attempted to load class net.minecraft.client.gui.screens.CreateBuffetWorldScreen`, logged at ERROR level. This comes from a client-only class in Regions Unexplored's mixin/data. Nothing else failed and the world generated normally. It does not name our mod.

---

## B. Licences (quoted from the real text)

I am not giving legal advice, only what the text says.

### Tectonic (Apollo): MIT per jar, CC0 per repository (they disagree)
- The jar's `META-INF/neoforge.mods.toml` says `license = "MIT"`. Modrinth also says MIT.
- The repository's `LICENSE` file on branch `v3` (github.com/Apollounknowndev/tectonic) begins:
  > Creative Commons Legal Code — CC0 1.0 Universal …
  CC0 is a public-domain dedication. I could not find a `LICENSE` on `main`; the default-branch listing returned 404.
- **Either way copying and adapting is permitted.** If treated as MIT the condition is: *"The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software."* Under CC0 there is no condition. Safest: keep an attribution line and the MIT notice.
- Read for learning: yes. Copy/adapt data into our mod: yes (MIT: keep notice). Bundle or depend on the mod: permitted, but we plan not to.

### Regions Unexplored (Apollo): MIT
> MIT License — Copyright (c) 2026 Apollo — Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software … The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

(Source: `RegionsUnexplored/21.1/LICENSE`; the jar's toml also says MIT.) Same conclusion as Tectonic. Its terrain content is not useful anyway.

### Lithostitched (the dependency of both): MIT, Copyright (c) 2023 Apollo
Same MIT text. Not needed by us (see section E).

### Biomes O' Plenty: All Rights Reserved
- Repository `LICENSE` (Glitchfiend/BiomesOPlenty, branch `26.3`), full text, 20 bytes: **"All rights reserved."**
- The jar's toml: `license="All Rights Reserved"`.
- **Study only; reimplement from understanding.** We may not copy its files. (Reading a jar you downloaded for study is what I did; the licence grants nothing beyond that.)

### Terralith (Stardust Labs): Stardust Labs License. Key clauses, quoted
Full text read from `github.com/Stardust-Labs-MC/license`, `license.txt` (last altered 2023-02-05):
> "You are forbidden to use portions of any Stardust Labs datapack or mod's code as part of any other datapack, "datapack-pack", modpack or mod without express permission. You are free to read the code and use it as a resource to learn from, but copying parts of it into another project is not allowed."

> "All parties … are expressly forbidden from using Stardust Labs properties, textures, code, and other works, as part of a training dataset for artificial intelligence, neural networks, or other generative systems. Additionally, any and all code, textures, and all other products … may not be used to configure, test, debug, or augment any artificial intelligence, neural network, or other generative system. … In short: You are banned from using anything created by Stardust Labs as part of any AI project."

Meaning for us: copying is **not allowed** (study only). The second clause is a problem for this very workflow, because I am an AI assistant and reading Terralith's files into my context could fall under "used to … augment any … generative system". I did not download or read Terralith. Playing Terralith yourself for personal viewing is allowed by the licence ("use … for whatever personal purpose"). **Decision for you:** if you want Terralith examined, either get Stardust Labs' permission, or examine it yourself and describe what you see. I'd treat Terralith as "study only, by a human".

### WWOO: CC-BY-NC-ND-4.0, Lithosphere: All Rights Reserved
These two are **Modrinth metadata only**; I did not read the licence texts. "ND" (no derivatives) would forbid adapting WWOO files. Both are therefore "study only" until someone reads the text.

---

## C. How each candidate makes its terrain

### C0. Vanilla 26.1.2 (baseline, confirmed from the installed jar)
- Vanilla worldgen data lives in `build/moddev/artifacts/minecraft-patched-26.1.2.127.jar` under `data/minecraft/worldgen/` (not in the raw `minecraft_26.1.2_server.jar`, which has none).
- **Terrain** = `noise_settings/overworld.json` (`min_y -64`, `height 384`, `sea_level 63`, `size_horizontal 1`, `size_vertical 2`), with a noise router built from `density_function/overworld/*` (35 files: `continents`, `erosion`, `ridges`, `offset`, `factor`, `jaggedness`, `sloped_cheese`, caves: `caves/{entrances,noodle,pillars,spaghetti_2d,…}`). Terrain height comes from splines on `continents`/`erosion`/`ridges`. `continentalness` has `firstOctave -9`, so features are huge and smooth.
- **Desert** (`biome/desert.json`): the biome only picks surface blocks and features (desert wells, fossils, cactus). The *shape* is whatever the climate splines give. Measured in the seed-424242 sample (1024² block area at x -2512 z 1280, 123 k desert columns): height p5 62, median 71, p95 83, max 108. Local relief (max−min per 32×32) median **12**, p90 **21**. Nothing dune-like: **vanilla deserts are gentle rolling plains.**
- **Badlands** (`badlands`, `eroded_badlands`, `wooded_badlands`): terracotta bands come from the surface rule type `minecraft:bandlands` plus the `badlands_surface` / `badlands_pillar` / `badlands_pillar_roof` noises (`noise/badlands_pillar.json`, `…_roof`). Eroded badlands makes **pillars** through the surface rule, but they are only 1–10 blocks wide columns, not 40–80 block spires. Measured: wooded_badlands p95 112, max 131; relief median 24.
- **Caves:** vanilla carvers (`cave`, `cave_extra_underground`, `canyon`) plus noise caves in the density functions above. `canyon` is a ravine carver, not a rock-mass channel.
- Vanilla is the free baseline.

### C1. Tectonic 3.0.28
**Kind:** both data and code.
- **Data** (`resourcepacks/tectonic/`, 170 JSON files in the base pack + 9 optional overlays): 105 density functions under `data/tectonic/worldgen/density_function`, 4 noises, plus 20 files overriding `minecraft:` (`noise_settings/overworld.json`, `density_function/overworld/noise_router/*` (15 files), the two badlands noises, temperature/vegetation).
- **Code** (`dev/worldgen/tectonic/…`): 13 mixins (`NoiseBasedChunkGeneratorMixin`, `HeightmapMixin`, `NoisesMixin`, `BlendingDataMixin`, `WorldCarverMixin`, `DimensionTypeAccessor`, …), 4 custom density types (`tectonic:config_constant`, `config_noise`, `config_clamp`, `invert`) driven by a **config system** (`ConfigState`, presets), and custom placement `tectonic:height_stabilized_count`. The `tectonic:parameter/continentalness|erosion|ridge` noises are **not** JSON in the jar (inferred: generated by code/`NoisesMixin`).
- It ships a `tectonic:set_height_limits` worldgen modifier and rewrites the overworld terrain router (its `noise_settings/overworld.json` still declares `min_y -64`, `height 384`).

**Desert-like content and what makes it look that way**
- Tectonic does not add desert biomes; it reshapes terrain under vanilla biomes. Its terrain is organised into climate "regions" named after card suits (`region/club`, `heart`, `spade`, `diamond`). The **diamond region carries the dunes**:
  - `region/diamond/dune/noise.json`: `−|noise(dune_base, xz_scale 0.76)|` (a crest-line ridged noise). `dune_base` = `firstOctave −7, amplitudes [0.5]` (≈128-block base wavelength, single octave).
  - `region/diamond/dune/final.json`: a spline turning that noise into a profile in about −0.2 … +0.2 density units, multiplied by two ridged `shifted_noise` terms whose shift is `±16 × dune_offset` (a very low-frequency noise, `firstOctave −11`). The sheared/shifted ridges make the dunes **asymmetric and elongated**, not round bumps.
  - `region/diamond/dune/spline.json`: the **gate**. Dunes are non-zero only where `raw_continents` ∈ (0, 0.85), `erosion_folded` ≥ 0.4, `temperature_index` between 4 and 5 (hottest bands), `region_selector` > 0, and `ridges_folded` > 0, with weight up to 6 at ridges_folded 0.25.
  - `sloped_cheese.json` adds the term `max(0, dune/spline × dune/final)` into the 3D density, so the dune height in blocks depends on the vertical slope of the density (≈ factor × value). **Its metre height is not stated anywhere in the data; I did not measure it in game.**
- **Badlands/mesa shape:** `region/club/badlands_ridge.json`, `plateau_spline.json`, `single_valley_spline.json`, `double_valley_spline.json`; plus `mountain_ridges/*` (noise-shifted ridge system with `weathering`). These produce **large broad plateaus**.

**Measured in game (seed 424242, same coordinates as vanilla):**

| Biome | Metric | Vanilla | Tectonic |
|---|---|---|---|
| desert (x -2512 z 1280, 1024²) | height p5 / p50 / p95 / max | 62 / 71 / 83 / 108 | 62 / 73 / 93 / 125 |
| desert | relief per 32×32: median / p90 | 12 / 21 | 13 / 28 |
| wooded_badlands | height p50 / p95 / max | 95 / 112 / 131 | 96 / **160 / 174** |
| wooded_badlands | relief median / p90 | 24 / 43 | 24 / 49 |

Six Tectonic desert sites were sampled (the first area plus five more at 512², coordinates in the file names): desert relief medians were 3–12 (site 4 had only 2 usable windows, median 38) and p90s 6–28. The desert at `x -1900 z 2000` spans heights 66–80, i.e. **flat**.
**Result: I did not find dunes in any of the six sampled Tectonic deserts.** The dune term exists in the data and the gating suggests it appears only in hot, eroded, mid-continent areas; none of the sampled deserts met that. So "Tectonic gives rolling dunes of about N blocks" is **unconfirmed**; I can only say what the recipe is. (Screens: `docs/terrain-study/tectonic/`.)

**Does anything resemble your large rock land masses?** Partly. Tectonic produces **one big oval mesa/plateau with terraced rim ledges and a river wrapping around it** (`tectonic/topdown_x-2300_z1700_…_badlands-plateau.png`, and the wide shot `…x-2512_z1280…`): height p50 120, p95 164, max 174 (vs vanilla 99–131), relief median 36, p90 53. It has terraced steps on the rim and a canyon-like river loop. It is **not** a clustered multi-lobe mass with bays, peninsulas, sand channels or several summits; it is **terracotta (badlands), not sandstone**; and it rises out of forest and rivers, not out of a sand sea. So: "has terraced mesa plateaus; no clustered rock mass; no sand channels".

**Dependencies:** Lithostitched (declared on Modrinth), Apollib (jar-in-jar), custom density types and noises registered by Java, mixins, a config file system. **Not copy-and-run.**

**Size / depth:** dune recipe alone: 18 density-function files + 2 noise defs, depth ~6 levels, of which 5 `__constants/*` files use custom config types (`config_constant`, `config_noise`) that become plain `constant` / `noise` in a copy. Whole terrain recipe: 105 + 20 density files.

### C2. Regions Unexplored 0.7.0+beta4
**Kind:** biomes + features + surface rules. No terrain-shaping data.
- Content: 78 biomes (`data/regions_unexplored/worldgen/biome`), 384 configured features, 285 placed features, 52 surface-rule files under `lithostitched/surface_rule`. The only density functions are `inferno_weight` and `special_cave_cells`.
- Desert-like biomes: `saguaro_desert`, `joshua_desert`, `arid_mountains`, `dry_bushland`, `steppe`, `outback`, `scorching_caves`, `towering_cliffs`, `chalk_cliffs`, `rocky_reef`, `spires`. They differ by **features** (saguaro cacti, joshua trees, shrubs, barrel cactus) and surface blocks. **"spires" is an icy forest biome** (ice-spire trees, icicles), not rock spires.
- **Measured:** the terrain is vanilla's. In the seed-424242 sample at `x -2912 z 2592` regions_unexplored:joshua_desert heights p5 62 / p50 70 / p95 86 vs the neighbouring `minecraft:desert` 63 / 69 / 76. Same family of numbers; the terrain is vanilla's, only the biome placed on it differs.
- Dependencies: Lithostitched, Apollib; registered blocks/trees. Not relevant for terrain.
- Targets 1–5: **none**.

### C3. Biomes O' Plenty 26.1.2.0.22 (study only)
- Content: 68 biomes, 300 configured + 342 placed features. **No density functions or noise settings** in the jar (`data/biomesoplenty/worldgen` has only `biome`, `configured_carver`, `configured_feature`, `placed_feature`). Biomes are placed by TerraBlender in Java.
- Desert-like: `lush_desert`, `cold_desert`, `dryland`, `dune_beach`, `wasteland`, `scrubland`, `rocky_shrubland`, `crag`, `volcano`, `jade_cliffs`. These are climate-parameter biome entries with features, on **vanilla terrain**.- Not run in game (would need GlitchCore + TerraBlender downloads; the jar shows nothing terrain-shaping to see).
- Targets 1–5: none.

---

## D. Look at it in game

- Worlds: `study_vanilla`, `study_tect`, `study_ru` (dev server, seed 424242, in `run-server/`, git-ignored); located desert with `/locate biome`, force-loaded blocks, read the saved chunks.
- Images (plan view, hillshade, 1 px or 2–3 px per block): `docs/terrain-study/vanilla/`, `tectonic/`, `regions-unexplored/`. Colours: tan = desert, orange = badlands, green = forest/jungle, blue = water, grey = stone. Stats per biome in the matching `stats_*.json`. Heights include tree and plant tops.
- Tooling to reproduce: `tools/worldscan/` (`render.js`, `nbt.js`, `closure.js`, `srv.sh`).
- **26.1 world layout changed:** chunks are in `<world>/dimensions/minecraft/overworld/region/*.mca` (not `<world>/region`).
- Biome locations at seed 424242 (from the origin): vanilla desert `-2016, 1728`, badlands `-2368, 2080`; Tectonic desert `-1920, 1920`, badlands `-2048, 2016`; RU saguaro `-5184, 2976`, joshua `-2656, 2848`.
- **Dunes:** nothing in vanilla, Tectonic (as sampled) or Regions Unexplored looks like 40-block sand dunes. Honest answer: **no.**
- **Rock land masses:** Tectonic's terraced mesa plateau is the closest thing; it is a single oval, not the clustered archipelago in your description.

---

## E. What would we adopt

| # | Target | Recommendation | Detail |
|---|---|---|---|
| 1 | Dunes (sand, ≤ 40 blocks) | **Reimplement the idea; optionally adopt 4 small Tectonic files as a starting point** | Technique: crest line = `−|noise|` (single octave, ~100–130 block wavelength), sheared by adding a ridged noise that is itself shifted by a very low-frequency offset noise (`firstOctave −11`) so crests run in a direction and slip faces are asymmetric; pass through a spline to a −0.2…+0.2 profile; add `max(0, gate × profile)` to the density. We would replace the gate (hot/erosion/region) with our own "this is sand sea" mask. Files if copied (MIT; keep copyright notice; rename to `arrakis_`): `dune/final`, `dune/noise`, `dune/offset_x`, `dune/offset_z` + noises `dune_base`, `dune_offset`. These six use **vanilla types only**. `dune/spline` and its 12 dependencies must be dropped. **Caveat: I did not see Tectonic's dunes generate, so the amplitude is unknown; tune in our own flat-floor dimension where it can be verified.** Cost: 6 files, 2 noises. |
| 2 | Large clustered rock masses with terraces, bays, channels | **Reimplement** | Nothing to copy. Technique: a low-frequency noise with a domain warp thresholded into a lobed mask (several offset centres so you get bays and peninsulas); a height profile spline over the mask distance for the terrace "treads" (terrace steps from a staircase spline with sloped risers via smoothed derivatives, plus small noise on each tread so they are not flat); different shelf heights per mass via a per-mass noise cell; sandstone via our own surface rule; partly buried by dunes by adding the dune term after the rock term with `max`. Tectonic `region/club/plateau_spline` and `mountain_ridges/*` show the spline-on-ridged-noise approach; they depend on custom config types. Cost: 10–20 density functions, 3–6 noises (estimate). |
| 3 | Outcrops: wedges and spires | **Reimplement; vanilla gives a hint** | Vanilla's `badlands_pillar` + `badlands_pillar_roof` noises and the `bandlands` surface rule make pillars but only 1–10 blocks wide (confirmed from the jar). For 40–80 block spires: thresholded low-frequency noise with high vertical gain, density falling with distance from the mass (thinning out). RU's `spires` is ice trees: nothing to use. |
| 4 | Canyons, channels, enclosed sand basins | **Reimplement** | Technique: subtract `1 − |noise|` ridged noise (narrow where |noise| near zero) from the rock-mass density to carve channels; basins via `min()` of the rock mask and a ring (8–20 block gap) from a second noise. Vanilla `canyon` carver is a cave-style ravine and not a fit. |
| 5 | Caves in rock only, ore on cave surfaces only | **Reimplement; use vanilla pieces** | Vanilla cave density functions (`caves/entrances`, `noodle`, `spaghetti_2d`, `pillars`) are plain vanilla types and free to copy; multiply by our rock-mass mask so caves never form under sand; `entrances` gives natural openings. Ore: vanilla `ore_*` placed features are in the jar; to limit ore to cave surfaces use vanilla placement modifiers (`minecraft:environment_scan` / `block_predicate_filter` with an air-adjacent test) instead of Tectonic's code-based `height_stabilized_count`. Vanilla lists copper/iron/gold/diamond/emerald already. |

### Dependency lists (everything we would have to rename into our namespace)
- **Tectonic dune files**: noises `tectonic:region/diamond/dune_base`, `…/dune_offset`; density functions `tectonic:region/diamond/dune/{final,noise,offset_x,offset_z}`. No other ids. (Reimplementing avoids even these.)
- **Vanilla files we could copy** (free under the game's terms): `density_function/overworld/caves/*`, `noise/badlands_pillar*.json`, `noise/{cave_*,spaghetti_*,noodle*,pillar*}.json`, `placed_feature/ore_*`.
- **We should not depend on** Tectonic, Lithostitched, Apollib, TerraBlender or GlitchCore. Our dimension has its own `noise_settings`, so none are required.

### Rough cost estimate
- Dune term: ~6 density functions and 2 noises, 2D only (cache_2d); cheap.
- Rock masses + terraces + outcrops + canyons: roughly 15–30 density functions, mostly 2D (`flat_cache`/`cache_2d`), applied once per column; cheap if kept 2D.
- Caves: reuse vanilla's 3D cave density functions; this is the expensive part (vanilla caves are the heaviest part of the overworld router). Keep them gated by the rock mask evaluated in 2D first (`range_choice` skips the 3D work outside rock).
- Reference points measured: vanilla's 4096-chunk sample generated in well under the 120 s I allowed; Tectonic's whole router (105 + 20 files) also finished inside that window. **I did not time generation precisely, so there is no hard number.** Estimates above are inferences.

---

## F. What was wrong or changed in 26.1.2 (prompt corrections)

- **Confirmed:** Tectonic, Biomes O' Plenty, Regions Unexplored and Terralith all publish NeoForge builds for 26.1.2 (on Modrinth). Tectonic's build is `-26.1` (covers 26.1, 26.1.1 and 26.1.2). Regions Unexplored is still a **beta**.
- **Licence surprises:** Tectonic jar says MIT, repository says CC0. Terralith's licence forbids use in AI projects. BOP is "All rights reserved". WWOO is "no derivatives".
- **Library mods:** Tectonic and Regions Unexplored require **Lithostitched** (+ Apollib, bundled in the jars), not TerraBlender; BOP requires TerraBlender + GlitchCore. Terralith's datapack zip has no dependencies; its jar needs Lithostitched.
- **Tectonic's recipe is not pure data**: custom density types and noises registered in Java, so its JSON cannot be dropped into our mod unchanged.
- **Where vanilla worldgen lives:** only in the NeoForge-patched jar, not the raw server jar. **World folder layout** is `dimensions/<namespace>/<path>/region`.
- **"Desert terrain"**: in vanilla, Regions Unexplored and BOP, desert biomes do not shape terrain; they only choose blocks and features.
- Hytale material: not used.

## How to see the ground-level views yourself
1. Copy `run-server/study-downloads/tectonic-…jar` and `lithostitched-…jar` into `run/mods/`.
2. `runClient`, new world, seed `424242`, then `/tp @s -1920 150 1920` (Tectonic desert) or `/tp @s -2300 250 1700` (the mesa plateau).
3. Remove the jars from `run/mods/` afterwards so our own tests stay clean.
