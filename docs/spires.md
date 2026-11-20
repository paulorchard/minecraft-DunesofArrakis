# Kind 1: spires and wedges (prompt 11)

Kind 1 (green and red continents by default) is `ToughModel.evalSpires`, called from the same `eval` as kinds 2 and 3, so the density
function and the surface rule share `Rt = R' + T1` (T1 >= 0). Features are heightfield columns, so every block of a spire is generated
exactly (no vertical-cell shaving). All numbers are in `tough.json` (`kind1`, `kind1_cluster`, `kind1_eligible`, `kind1_spire`,
`kind1_wedge`, `kind1_bands`). `"tough": "off"` reproduces prompt 08; `kind_override` accepts null, 1, 2 or 3.

## Method
- Two jittered lattices from a seeded hash (`mix`, `u`): spires on 44 m cells, wedges on 100 m cells; a feature exists when
  `u < p_max * clusterDensity`. Profile = max over the 3x3 cell neighbourhood (touching spires merge). Per-thread cache of 8192 features
  plus a 256 m tile flag ("can any feature reach this tile"), so open rock costs one lookup.
- Cluster mask: two noises (wavelength 800 and 360 m), threshold 0.27, first-order signed distance, density thins to a tail over
  140 m (inside) + 90 m (outside).
- Spire: concave profile with ledges (stair blended with weight that is 0 at the top), lean 0-8 deg, 3-block stepped apron of base rock,
  top disc exact (flat-top blend so it is level at base + h). Height is lowered when it would pass the 190 m cap; the top is never flattened.
- Wedge: south face (`weather_angle`) with steps, 1-block-step back ramp (1:2 to 1:4), roof along the axis, long axis east-west +-30 deg.
- Eligibility: base >= 4 m above sand, >= 40 m from the coast, 8 footprint points inside kind-1 rock >= 15 m from the coast.
- Materials: stone, andesite in vertical streaks and patches; the top 3 blocks stone; apron is base rock.

## Results (seed 1 unless noted; tools in `tools/worldscan`, raw output in `docs/spires/sweep`)
- Chunk windows (1024^2, real generation): mismatch 0.7e-2 to 5.8e-2 % (limit 0.1 %), air below surface 0, on all windows.
- Tough off + layers off: 0 differing columns vs prompt 08 on all 25 windows. Override 3 (tough_*) vs prompt 10: 0. Override 2
  (plain_purple) vs prompt 10: 0. Default map, purple/blue windows vs prompt 10: 0 differing columns.
- Columns differing from prompt 10 lie within 15 m of the feature footprints (the listed reach is approximate; beyond 15 m: 0).
- Order independence (reverse) and repeat of the same seed: 0 differing blocks (heart and edge windows, 4096 chunks each).
- Named features in the heart window (26 spires, 6 wedges): the generated top equals the analytic top for all (worst difference 1 block,
  on edge columns, never a lower top). Top spire widths: min 3 blocks.
- Materials: stone and andesite only; andesite share per spire p10/p50/p90 = 0.23/0.34/0.42; streak width p50 4 blocks.
- 20-seed field sweep: top width min 3 on all; maximum top 190 m on all; wedge steps within 60 deg of south 97.9-98.6 %; wedge back
  steps <= 1: 97.1-98.0 %; wedge walk-up 100 %; spire walk-up 0 %; no spire above 110 m.
- Cost (ms per chunk, 1024^2 windows; prompt 10 numbers from the earlier run, not the same session): open desert 6.75 vs 6.47 (x1.04);
  cluster heart 9.7, edge 9.1, kind-1 rock without features 7.4; worst window slice_purple 11.9 (11.75 in prompt 10).

## Not met or open (honest list)
- Cluster share of kind-1 rock over 20 seeds ranges 13.6-36.9 % (target 15-25 %); green seed 1 25.3 %, red 17.1 %.
- Of 100 purple clusters only 54 are 0.05-0.5 km2; the rest are smaller (many tiny).
- Largest cluster-free circle is at least 1074 m on all seeds (target 500 m: met).
- Ring islands thin to 4 of 20 seeds without a monotone thinning; features per km2 on islands are low (ring 11.8).
- The same-session cost comparison with a prompt-10 build was not done (no second build); only same-tag t11 vs t11off windows.
- Dune mountains vs spires: in the heart and edge windows 2 of 237 spires have their top under dune sand.
