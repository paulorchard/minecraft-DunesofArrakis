# Eight hard-edged circles (prompt 15)

Eight vanilla biome islands, each a circle of 100 blocks diameter, standing in the original dunes (commit 8ec4142) with no blending.
Terrain and biome change at the circle's edge: the terrain mask is a step on the squared distance (x-cx)^2 + (z-cz)^2 < 50^2 (no square root),
the biome sampler uses the same step. Settings: `tools/rewrite/grid.json` (`transition` 0, `sharp_ramp` 0, `biome_transition` "sharp").
Prompt 14's blending is still in the generator: set `transition` above 0 (and `sharp_ramp`/`biome_transition`) and run `node tools/rewrite/gen.js` to get it back.

Layout (x east, z south): centres x = 300 + 300c, z = 300 + 300r. Row 0: Windswept Hills, Windswept Gravelly Hills, Ice Spikes, Jagged Peaks.
Row 1: Stony Peaks, Windswept Savanna, Badlands, Eroded Badlands. Climate tuples are unchanged from prompt 14.

One run, seed 1, window x 96..1407, z 96..815 (3,690 chunks): 71.5 s of generation (1 m 31 s with start-up). Pictures: `circles/circ_r_biomes.png`, `circles/circ_r_height.png`;
numbers: `circles/report.json`.

Try it: Create New World, More World Options (or World), seed `1`, world type "Arrakis". Teleports (the Arrakis type uses the normal overworld):

    /execute in minecraft:overworld run tp @s 300 89 300      (Windswept Hills)
    /execute in minecraft:overworld run tp @s 600 125 300     (Windswept Gravelly Hills)
    /execute in minecraft:overworld run tp @s 900 75 300      (Ice Spikes)
    /execute in minecraft:overworld run tp @s 1200 232 300    (Jagged Peaks)
    /execute in minecraft:overworld run tp @s 300 222 600     (Stony Peaks)
    /execute in minecraft:overworld run tp @s 600 69 600      (Windswept Savanna)
    /execute in minecraft:overworld run tp @s 900 120 600     (Badlands)
    /execute in minecraft:overworld run tp @s 1200 71 600     (Eroded Badlands)
    /execute in minecraft:overworld run tp @s 750 286 450 0 90        (200 up over the middle of the grid, looking straight down)
    /execute in minecraft:overworld run tp @s 1245 208 300 -90 0     (Jagged Peaks, east edge, looking out at the dunes)

The heights are the seed-1 surface plus one block (the 286 is the surface at the grid middle plus 200).
