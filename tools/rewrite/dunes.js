// Sand dunes on the flat sand ocean (prompt 25). Pure data: noises, one 2D density function dune_height, an outermost term in final_density, one surface-rule branch.
// applyDunes(ns, OC, NS) changes the noise settings object in place and returns the extra files {noise: {name: json}, density: {name: json}}; with dune_enabled = false it changes nothing.
//
//   dune_height(x, z)  = clamp(max_height * field * gate * style_mix * shape, 0, max_height)          2D, no y, no cache of its own
//     field  = clamp((F - field_threshold) / field_soft, 0, 1)         F = noise arrakis_dune_fields at (x, z) unscaled and unwarped, so the surface rule can test the same noise exactly
//     gate   = clamp((gate_end - continents) / (gate_end - gate_open), 0, 1)       full in the open ocean, 0 at the shelf end: dunes get low and short towards the shore, then none
//     shape  = flat_mult * (sharp_w * sharp + (1 - sharp_w) * smooth)     sharp = 1 - |D| * 2.8 (crest), smooth = smoothstep(2.2 * D) (rounded), D = noise arrakis_dunes
//     flat_mult / sharp_w come from the style noise S: S below the flat threshold = flat (no relief), above the sharp threshold = sharp, between = smooth
//   final_density = max(final_density_vanilla, term);  term = (sea_level - 0.5 - y + interpolated(dune_height in the y band)) * k, only for y at or above sea level (range_choice, evaluated lazily)
//   surface rule (first): y >= sea_level and y <= sea_level + max_height + 1 and above the preliminary surface and noise arrakis_dune_fields >= threshold - margin  ->  dune_sand
const SIGMA_STYLE = 0.336;       // standard deviation of the style noise (first octave -10, amplitudes 1, 1), measured with tools/model/NoiseStat.java
const FIELD_FIRST = s => -Math.round(Math.log2(s));
// inverse normal CDF (Acklam), only used to turn the style shares into noise thresholds
function invNorm(p) {
  const a = [-39.69683028665376, 220.9460984245205, -275.9285104469687, 138.357751867269, -30.66479806614716, 2.506628277459239];
  const b = [-54.47609879822406, 161.5858368580409, -155.6989798598866, 66.80131188771972, -13.28068155288572];
  const c = [-0.007784894002430293, -0.3223964580411365, -2.400758277161838, -2.549732539343734, 4.374664141464968, 2.938163982698783];
  const d = [0.007784695709041462, 0.3224671290700398, 2.445134137142996, 3.754408661907416];
  const lo = 0.02425; let q, r;
  if (p < lo) { q = Math.sqrt(-2 * Math.log(p)); return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1); }
  if (p > 1 - lo) { q = Math.sqrt(-2 * Math.log(1 - p)); return -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1); }
  q = p - 0.5; r = q * q;
  return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
}
const mul = (a, b) => ({ type: 'minecraft:mul', argument1: a, argument2: b });
const add = (a, b) => ({ type: 'minecraft:add', argument1: a, argument2: b });
const clamp = (x, lo, hi) => ({ type: 'minecraft:clamp', input: x, min: lo, max: hi });
const noise = (id, xz) => ({ type: 'minecraft:noise', noise: id, xz_scale: xz, y_scale: 0.0 });
const c2d = x => ({ type: "minecraft:cache_2d", argument: x });   // equal nodes share one NoiseChunk cache, so a noise used several times in dune_height is computed once per column
const grad = (fromY, toY, fromV, toV) => ({ type: 'minecraft:y_clamped_gradient', from_y: fromY, to_y: toY, from_value: fromV, to_value: toV });

function params(OC, sea) {
  const H = OC.dune_max_height, fieldFirst = FIELD_FIRST(OC.dune_field_scale);
  const tFlat = SIGMA_STYLE * invNorm(OC.dune_flat_share), tSharp = SIGMA_STYLE * invNorm(1 - OC.dune_sharp_share);
  // y band (multiples of the cell height 8 around the dunes) in which the interpolated height is evaluated; outside it the corner value is 0 without any noise
  const bandLo = Math.floor((sea - 1) / 8) * 8, bandHi = Math.ceil((sea + H + 1) / 8) * 8 + 8;
  return { H, fieldFirst, tFlat, tSharp, bandLo, bandHi };
}

function applyDunes(ns, OC, NS) {
  if (!OC.dune_enabled) return null;
  const sea = ns.sea_level, P = params(OC, sea), N = n => NS + ':' + n;
  const files = {
    noise: {
      arrakis_dunes: { firstOctave: -6, amplitudes: [1.0, 0.6, 0.3] },                  // zero crossings every ~72 blocks at xz_scale 1 (tools/model/NoiseStat.java)
      arrakis_dune_fields: { firstOctave: P.fieldFirst, amplitudes: [1.0, 1.0, 0.5] },   // -10: fields ~1200 across at the default threshold
      arrakis_dune_style: { firstOctave: -10, amplitudes: [1.0, 1.0] },
    },
    density: {},
  };
  // ---- 2D height
  let D = noise(N('arrakis_dunes'), 72 / OC.dune_wavelength);
  if (OC.dune_warp_blocks > 0) {   // optional warp of the dune shape only (vanilla shift_x / shift_z, which are cached per 4 blocks: keep it small)
    const k = OC.dune_warp_blocks * (72 / OC.dune_wavelength) / 2.0;
    D = { type: 'minecraft:shifted_noise', noise: N('arrakis_dunes'), shift_x: mul(k, 'minecraft:shift_x'), shift_y: 0.0, shift_z: mul(k, 'minecraft:shift_z'), xz_scale: 72 / OC.dune_wavelength, y_scale: 0.0 };
  }
  D = c2d(D);
  const S = c2d(noise(N('arrakis_dune_style'), 1042 / OC.dune_style_scale));
  const sharp = clamp(add(mul({ type: 'minecraft:abs', argument: D }, -2.8), 1.0), 0.0, 1.0);
  const sm = clamp(mul(D, 2.2), 0.0, 1.0);
  const smooth = mul({ type: 'minecraft:square', argument: sm }, add(3.0, mul(-2.0, sm)));            // smoothstep
  const ramp = (x, from) => clamp(mul(add(x, -from), 12.5), 0.0, 1.0);                                // 0.08 wide
  const sharpW = ramp(S, P.tSharp - 0.04), flatMult = ramp(S, P.tFlat - 0.04);
  const shape = mul(flatMult, add(mul(sharpW, sharp), mul(add(1.0, mul(-1.0, sharpW)), smooth)));
  const field = clamp(mul(add(c2d(noise(N('arrakis_dune_fields'), 1.0)), -OC.dune_field_threshold), 1 / OC.dune_field_soft), 0.0, 1.0);
  const gate = clamp(mul(add(N('overworld/continents'), -OC.dune_gate_end), -1 / (OC.dune_gate_end - OC.dune_gate_open)), 0.0, 1.0);
  files.density['overworld/dune_height'] = clamp(mul(P.H, mul(field, mul(gate, shape))), 0.0, P.H);
  // ---- router term, outermost, evaluated only for y >= sea level (range_choice computes only the chosen branch)
  const span = P.bandHi - P.bandLo + 2;
  // NOTE: interpolated(range_choice(y band, dune_height, 0)) returned 0 in the game (tested: constant, plain interpolated and un-interpolated variants all work), so the plain interpolated height is used.
  // Cost: dune_height is evaluated at the cell corners (4 x 4 blocks, all 49 corner y); its three noises are cache_2d, so the repeated y corners only repeat cheap arithmetic.
  const hBand = { type: "minecraft:interpolated", argument: N("overworld/dune_height") };
  const seaSpan = P.H + 3;
  const linear = grad(sea - 16, sea + P.H + 16, 15.5, -(P.H + 16.5));                                  // sea_level - 0.5 - y
  const term = { type: 'minecraft:range_choice', input: grad(sea - 1, sea + P.H + 2, 0.0, 1.0), min_inclusive: 0.5 / seaSpan, max_exclusive: 1.0,
    when_in_range: mul(OC.dune_term_slope, add(linear, hBand)), when_out_of_range: -1000000.0 };
  ns.noise_router.final_density = { type: 'minecraft:max', argument1: ns.noise_router.final_density, argument2: term };
  // ---- surface rule: dune blocks (stone above the sea fill inside a dune field) become Dune Sand
  const cond = (c, then) => ({ type: 'minecraft:condition', if_true: c, then_run: then });
  const yAbove = y => ({ type: 'minecraft:y_above', anchor: { absolute: y }, surface_depth_multiplier: 0, add_stone_depth: false });
  const rule = cond(yAbove(sea), cond({ type: 'minecraft:not', invert: yAbove(sea + P.H + 2) }, cond({ type: 'minecraft:above_preliminary_surface' }, cond({ type: 'minecraft:stone_depth', offset: P.H - 1, add_surface_depth: false, secondary_depth_range: 0, surface_type: 'floor' },
    cond({ type: 'minecraft:noise_threshold', noise: N('arrakis_dune_fields'), min_threshold: OC.dune_field_threshold - 0.01, max_threshold: 10.0 },
      { type: 'minecraft:block', result_state: { Name: NS + ':dune_sand' } })))));
  ns.surface_rule = { type: 'minecraft:sequence', sequence: [rule, ...ns.surface_rule.sequence] };
  files.info = { ...P, sea, tFlat: +P.tFlat.toFixed(3), tSharp: +P.tSharp.toFixed(3) };
  return files;
}
module.exports = { applyDunes, params, invNorm, SIGMA_STYLE };
