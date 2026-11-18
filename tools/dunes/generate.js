#!/usr/bin/env node
// Generates the dune density functions and noises from ONE block of knobs.
//   node tools/dunes/generate.js          (writes into src/main/resources/data/islandcraft_dunesofarrakis/worldgen)
// Do not hand-edit the generated arrakis_dune_*.json files; edit the knobs below and re-run.
// Meaning of every knob: docs/dunes.md.
const fs = require('fs'), path = require('path');
const NS = 'islandcraft_dunesofarrakis';

// ───────────────────────── KNOBS ─────────────────────────
const KNOBS = {
  // Direction the wind blows TOWARD, degrees from +x (east) towards +z (south). Crests run perpendicular to it.
  wind_toward_degrees: 115,
  // Crest-to-crest distance along the wind direction, in blocks.
  wavelength: 160,
  // Height a "typical" crest reaches (used only to turn the leeward slope into a windward fraction).
  reference_crest_height: 24,
  // Leeward (steep) slope in degrees at the reference crest height. Windward slope is derived and printed.
  leeward_slope_deg: 28,
  // Optional override of the windward fraction (0.05..0.95). null = derive from the two knobs above.
  windward_fraction_override: null,
  // Half-width of the rounded crest / toe, in cycles (1 cycle = one wavelength).
  crest_rounding: 0.04,
  toe_rounding: 0.07,
  // Height of a dune whose envelope is 1.
  max_height: 40,
  // Crest bending ("shear"): how far crest lines are pushed sideways by a low-frequency noise, in cycles
  // (1 cycle = one wavelength). 0 = ruler-straight parallel crests.
  warp_cycles: 0.26,
  warp_first_octave: -9, warp_amplitudes: [1, 0.5],
  // Stretch of the warp noise across (u) and along (v) the crests. Values > 1 make the noise features longer in that
  // direction. Keep the warp varying slowly ACROSS the crests (so wavelength stays near the knob) and faster ALONG them.
  warp_cross_stretch: 1.4, warp_along_stretch: 1.0,
  // Envelope: where dunes are tall (1) or absent (0) = clamp(envelope_gain * noise + envelope_offset, 0, 1).
  envelope_gain: 1.6, envelope_offset: 0.72,
  envelope_first_octave: -8, envelope_amplitudes: [1, 0.6],
  // Stretch of the envelope noise across / along the crests (along > 1 = long dune fields that follow the crests).
  envelope_cross_stretch: 1.0, envelope_along_stretch: 2.5,

  // --- Irregularity and non-dune relief (all heights in blocks, added on top of the dunes) ---
  // Second, slower warp that changes the crest SPACING (cycles; 0 = even spacing). Wavelength of this noise = 2^-octave.
  spacing_warp_cycles: 0.35, spacing_first_octave: -10,
  // Break-up: isotropic noise that cuts the ridges into segments, saddles and gaps (no preferred direction).
  // factor = clamp(break_gain * noise + break_offset, 0, 1) multiplies the dune height. Lower offset = more gaps.
  break_gain: 1.6, break_offset: 1.05, break_first_octave: -8,
  // Low rolling hills with no direction, strongest where the dunes are absent (fill the flat floor).
  hill_height: 5, hill_first_octave: -7, hill_gain: 1.4, hill_offset: 0.45,
  // Mountains: broad smooth massifs with no direction, dunes still ripple on top of them.
  // mask = clamp(mountain_gain * noise + mountain_offset, 0, 1)^2; higher offset = more/larger mountains.
  mountain_height: 80, mountain_first_octave: -10, mountain_gain: 2.5, mountain_offset: 0.05,
};
// ──────────────────────── end knobs ───────────────────────

const K = Object.assign({}, KNOBS, process.env.DUNE_KNOBS ? JSON.parse(process.env.DUNE_KNOBS) : {});
const rad = d => d * Math.PI / 180;
const lee_len = K.reference_crest_height / Math.tan(rad(K.leeward_slope_deg));
const a = K.windward_fraction_override ?? Math.min(0.95, Math.max(0.05, 1 - lee_len / K.wavelength));
const w = rad(K.wind_toward_degrees);
const out = path.join(__dirname, '..', '..', 'src/main/resources/data', NS, 'worldgen');
const put = (rel, obj) => { const f = path.join(out, rel); fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, JSON.stringify(obj, null, 2) + '\n'); };
const r6 = v => Math.round(v * 1e9) / 1e9;

const lin = (cx, cz) => ({ type: `${NS}:arrakis_linear`, x_coefficient: r6(cx), z_coefficient: r6(cz) });
// Noise sampled in (u / cross, v / along) coordinates: u across the crests (the wind direction), v along them.
const aniso = (noise, cross, along) => ({
  type: 'minecraft:shifted_noise', noise: `${NS}:${noise}`, xz_scale: 0.0, y_scale: 0.0,
  shift_x: lin(Math.cos(w) / cross, Math.sin(w) / cross), shift_y: 0.0,
  shift_z: lin(-Math.sin(w) / along, Math.cos(w) / along),
});
const ref = n => `${NS}:${n}`;

put('noise/arrakis_dune_warp.json', { firstOctave: K.warp_first_octave, amplitudes: K.warp_amplitudes });
put('noise/arrakis_dune_envelope.json', { firstOctave: K.envelope_first_octave, amplitudes: K.envelope_amplitudes });
put('noise/arrakis_dune_spacing.json', { firstOctave: K.spacing_first_octave, amplitudes: [1, 0.5] });
put('noise/arrakis_dune_break.json', { firstOctave: K.break_first_octave, amplitudes: [1, 0.6] });
put('noise/arrakis_hills.json', { firstOctave: K.hill_first_octave, amplitudes: [1, 0.6] });
put('noise/arrakis_mountain.json', { firstOctave: K.mountain_first_octave, amplitudes: [1, 0.4] });
const iso = n => ({ type: 'minecraft:noise', noise: `${NS}:${n}`, xz_scale: 1.0, y_scale: 0.0 });
const clamp01 = (gain, off, n) => ({ type: 'minecraft:clamp', input: { type: 'minecraft:add', argument1: off, argument2: { type: 'minecraft:mul', argument1: gain, argument2: iso(n) } }, min: 0.0, max: 1.0 });

// phase in cycles = distance along the wind / wavelength + warp
put('density_function/arrakis_dune_phase.json', {
  type: 'minecraft:add',
  argument1: lin(Math.cos(w) / K.wavelength, Math.sin(w) / K.wavelength),
  argument2: { type: 'minecraft:add',
    argument1: { type: 'minecraft:mul', argument1: K.warp_cycles, argument2: aniso('arrakis_dune_warp', K.warp_cross_stretch, K.warp_along_stretch) },
    argument2: { type: 'minecraft:mul', argument1: K.spacing_warp_cycles, argument2: aniso('arrakis_dune_spacing', 1.0, 1.0) } },
});
put('density_function/arrakis_dune_profile.json', {
  type: `${NS}:arrakis_sawtooth`, argument: ref('arrakis_dune_phase'),
  windward_fraction: r6(a), crest_rounding: K.crest_rounding, toe_rounding: K.toe_rounding,
});
put('density_function/arrakis_dune_envelope.json', {
  type: 'minecraft:clamp',
  input: { type: 'minecraft:add', argument1: K.envelope_offset, argument2: { type: 'minecraft:mul', argument1: K.envelope_gain, argument2: aniso('arrakis_dune_envelope', K.envelope_cross_stretch, K.envelope_along_stretch) } },
  min: 0.0, max: 1.0,
});
put('density_function/arrakis_dune_break.json', clamp01(K.break_gain, K.break_offset, 'arrakis_dune_break'));
put('density_function/arrakis_mountain_mask.json', { type: 'minecraft:square', argument: clamp01(K.mountain_gain, K.mountain_offset, 'arrakis_mountain') });
// dune height above sand level, in blocks, 2D, computed once per column
put('density_function/arrakis_dune_height.json', {
  type: 'minecraft:cache_2d',
  argument: { type: 'minecraft:add',
    argument1: { type: 'minecraft:add',
      // dunes, cut into segments by the break-up noise
      argument1: { type: 'minecraft:mul', argument1: K.max_height, argument2: { type: 'minecraft:mul', argument1: ref('arrakis_dune_break'), argument2: { type: 'minecraft:mul', argument1: ref('arrakis_dune_envelope'), argument2: ref('arrakis_dune_profile') } } },
      // low hills, mostly where the dune envelope is low
      argument2: { type: 'minecraft:mul', argument1: K.hill_height, argument2: { type: 'minecraft:mul', argument1: clamp01(K.hill_gain, K.hill_offset, 'arrakis_hills'), argument2: { type: 'minecraft:add', argument1: 1.0, argument2: { type: 'minecraft:mul', argument1: -0.8, argument2: ref('arrakis_dune_envelope') } } } } },
    argument2: { type: 'minecraft:mul', argument1: K.mountain_height, argument2: ref('arrakis_mountain_mask') } },
});

const lw = K.wavelength * a, ll = K.wavelength * (1 - a);
console.log(`knobs: wavelength ${K.wavelength}, wind toward ${K.wind_toward_degrees}deg (crests run ${(K.wind_toward_degrees + 90) % 180}deg), max_height ${K.max_height}`);
console.log(`windward fraction ${a.toFixed(3)}: windward run ${lw.toFixed(1)} blocks, leeward run ${ll.toFixed(1)} blocks`);
for (const h of [K.reference_crest_height, K.max_height])
  console.log(`  crest ${h}: windward slope ${(Math.atan(h / lw) * 180 / Math.PI).toFixed(1)} deg, leeward slope ${(Math.atan(h / ll) * 180 / Math.PI).toFixed(1)} deg`);
