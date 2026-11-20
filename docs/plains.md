# Kind 2: plains (prompt 10)

Kind 2 is wide, smooth plains of sandstone and red sandstone that end in sharp cliffs only on the weather side (default: due south) and run down gently into the base on the other sides. It is added as Rt = R' + T2 by the same `ToughModel.eval` that the density function and the surface rule both call; `"tough": "off"` still reproduces prompt 08 and kind 3 is untouched.

## Switching
`arrakis/tough.json`: `kind_override` is `null` (kind map: blue and brown are kind 2, purple 3), `2` (every rock column is a plain) or `3`. Rebuild or restart after editing. `kind2.weather_angle` is degrees from +X toward +Z (90 = south).

## How it works (all numbers in `tough.json`, sections `kind2*`)
- **Mask**: two seeded noises (wavelengths 1600 and 650 m), plateau where v > threshold (-0.28). `s` = signed distance to the outline, first order, `(v - thr)/|grad v|` (forward difference, 14 m). Edge noise (24 m, wavelength 300) is added to `s` only near the outline.
- **Edge direction**: the outward normal is the gradient of `s`; the edge noise's gradient is added but capped at 0.6 of the unit normal, so it can turn the facing and never flip it. Cone weight is 1 within 45 deg of the weather direction, 0 beyond 90 deg. Where the mask gradient is nearly zero (saddles) there is no cliff.
- **Cliff-ramp blend**: one profile over one rise, `p = (1-c)*ramp + c*cliff`, with `c` = cone weight x gradient confidence. Additive, so the slope stays bounded; earlier steepness-interpolation variants made walls at corners.
- **Top**: broad base reference at the weather-side edge point + height (8..30 m by noise) + top_offset (10) - (plateau length toward the weather side)/tilt_length (22..38 m per block) + undulation (1.5 m, 220 m), max 150 above sand. Eligibility (base >= 12 m, coast >= 150 m, half-width >= 150 m) is soft: the rise fades in over `kind2_fade`.
- **Cliff**: slope 2.5..5.5 blocks per metre, ledges, lean 0..3, minimum height 14 m near the edge.
- **Ramp**: 1:6..1:14 metres per block, eased at both ends (0.6), single-block steps.
- **Scree**: a gated apron (noise gate, share 0.93 of the noise range) up to a third of the face height, 50 m long.
- **Bands**: sandstone and red sandstone alternate in 3..10 block bands (seeded 24-band cycle), thin accent bands of smooth and cut sandstone, shifted by the kind 3 slow noises; cap red sandstone with sandstone patches. No terracotta.

## Tools (`tools/worldscan/plain.sh`)
`render` (rt or facing colour), `facing` (compass histogram, ramp slopes, face heights, scree, irregularity), `plateaus` (areas, tops, neighbouring heights, tilt, coverage), `routes` (whole continent walk-up, non-south entries and any entry), `bandstats`, `pick` (window rows and teleports), `probe`. ReliefScan gained `override2`. `windows.sh`/`checkrelief.sh` ONLY and SKIP are now regular expressions.

## Results (seed 1 unless named; summaries in docs/plains)
See the commit message and the report; honest status of the targets is recorded in `docs/plains/*.txt`. Known misses: facing share below 95% on override-2 seeds 2 (87%) and 9 (91.5%); under-8 m south cliff length above 10% on several seeds; scree 34..95% by seed; tilt about 1 block per 70..90 m (target 40..80); not every plateau has a non-south route (blue 94-97%, brown 67%).
