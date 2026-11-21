# Outcrops with normalised heights (prompt 16)

Each circle is a rocky outcrop with a height we choose, rising out of the dunes. The world surface is one exact heightfield,
`surface = max(sand_level + dune, rock_top)`, and the density is the dunes' own formula `0.5 + surface - y` (`island/final_density`).

    rock_top = 63 + taper * rise            rise = min_rise + (max_rise - min_rise) * n
    n = clamp((H_v - low) / span, 0, 1)     H_v = find_top_surface of the copied sloped_cheese with the island's climate values (1-block steps, y 320 .. -64)
    taper = 1 inside core_fraction of the radius, smoothstep to 0 at the edge (spline on the squared distance, no sqrt)

`H_v` and `rise` are evaluated only inside the circles (range_choice on the circle mask, inside a flat_cache on the 4-block grid), then interpolated
between the 4-block corners (`interpolated`), so the relief is smooth. The biome follows the rock: the island's climate inputs are used where
`rock_top - dune >= 0.5` (`island/rock_mask`), the dune biome elsewhere in the circle. Surface rules are unchanged.

All numbers are in `tools/rewrite/grid.json` (`outcrop`: min_rise 30, core_fraction 0.5, min_span 16; per island `max_rise`, `vanilla_low`, `vanilla_high`).
Set `outcrop.enabled` to false for prompt 15's behaviour. Then `node tools/rewrite/gen.js`.

One run, seed 1, window x 96..1407, z 96..815 (3,690 chunks): 78 s (1 m 38 s with start-up). Pictures `outcrops/biomes.png`, `outcrops/height.png`, numbers `outcrops/outcrops.json`.
Tools: `tools/worldscan/outcrops.js` (per-circle metrics from the scan and a dune-only scan), `tools/worldscan/proposemoves.js` (smallest centre move).

Try it: Create New World, seed `1`, world type "Arrakis". Teleports (x y z yaw pitch; each stands 120 blocks south of a circle looking north; a dune mountain can block the view):

    /execute in minecraft:overworld run tp @s 300 124 420 180 -10   (Windswept Hills)
    /execute in minecraft:overworld run tp @s 600 100 420 180 -10   (Windswept Gravelly Hills)
    /execute in minecraft:overworld run tp @s 900 69 420 180 -10    (Ice Spikes)
    /execute in minecraft:overworld run tp @s 1200 72 420 180 -10   (Jagged Peaks)
    /execute in minecraft:overworld run tp @s 300 72 720 180 -10    (Stony Peaks)
    /execute in minecraft:overworld run tp @s 600 79 720 180 -10    (Windswept Savanna)
    /execute in minecraft:overworld run tp @s 900 72 720 180 -10    (Badlands)
    /execute in minecraft:overworld run tp @s 1200 78 720 180 -10   (Eroded Badlands)
    /execute in minecraft:overworld run tp @s 750 336 450 0 90      (250 up over the middle, looking down)
