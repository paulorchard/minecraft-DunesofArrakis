# Report — Prompt 23 Fix the DH sand generator

Status: partly (detail-level bug fixed and fallback reworked, built and deployed; no client run, so not seen working in game)   Commit / tag: 238b084

## What was built
- `DhSandGenerator.generateLod`: every `DhApiTerrainDataPoint` is now created at detail level 0, each column is gap-free (stone, then Dune Sand up to sea level where the surface is below it, then an air point up to the world top, as DH's own rough generator does). Column spacing follows the task (below). Still one `depth` evaluation per column, no chunk generation.
- Failure handling reworked: the first failure logs one WARN with the stack; that task and all later tasks end as `CancellationException`, which DH's queue and provider ignore without logging. A static session latch stops `DhSandSupport` registering a generator on any later level load in this session.
- No change to world generation, ocean knobs, Dune Sand, or the owner's DH config.

## Confirmed
(Read from the DH 3.3.3 jar, decompiled. No client run.)
- Cause of the first error: `LodDataBuilder.validateOrThrowApiDataColumn` rejects any data point whose detail level is not 0; we passed the task's detail level.
- The 22 follow-up errors came from our own stop flag, and DH's queue does not fall back: `ServerLevelModule.LodRequestState.createRetrievalQueue` takes `WorldGeneratorInjector.INSTANCE.get(level)`, builds DH's own `DhWorldGenerator` only if nothing is bound, and the queue keeps that generator for its life.
- `ExceptionUtil.isInterruptOrReject` (Cancellation/Rejected/interrupt) is the only class of failure DH does not log as an ERROR (`GeneratedFullDataSourceProvider.onWorldGenTaskComplete` and `WorldGenerationQueue`).
- Build passed; jar deployed to `ArrakisTest01/mods` (this mod's jar only).

## Inferred
- Under `SURFACE_ONLY` the "chunks left" counter should fall quickly, because our tasks are cheap and no real chunks are requested. Not observed.
- That DH re-requests a cancelled position later, but with the cancelled tasks failing instantly this is cheap. Not observed.

## Answers to the prompt's questions
**Meaning of the arguments.** In `WorldGenerationQueue.startApiDataSourceGenerationEvent`: `detailLevel = sectionDetail − 6`; `posX/posZ` are the section coordinates at the section's detail; `chunkPosMinX/Z` is the section's minimum chunk (block = ×16). `FullDataSourceV2.getWidthInDataColumns()` is always 64. So a task covers 64 × 2^detailLevel blocks per side and each column is 2^detailLevel blocks wide, starting at block (chunkPosMinX × 16, chunkPosMinZ × 16):

| section detail | detailLevel passed | block columns' width | area per call | columns |
|---|---|---|---|---|
| 7 | 1 | 2 | 128 × 128 | 64 × 64 |
| 12 | 6 | 64 | 4096 × 4096 | 64 × 64 |
| 18 | 12 | 4096 | 262,144 × 262,144 | 64 × 64 |

Column (x, z) is sampled at its centre, block (chunkPosMinX × 16 + x × 2^d + 2^(d−1)); DH's own rough generator samples the corner. The data points inside a column are always block-sized (detail 0); DH aggregates upward itself.
**Fallback used: (c), with improvements.** (a) is not possible: the public API has no unregister, `WorldGeneratorInjector` only offers `bind` and a global `clear()`, and the queue already holds the generator it was built with. (b) is not possible: DH's `DhWorldGenerator` needs an internal `IDhServerLevel` that the API does not expose. So: one WARN with the stack, then silent cancellations (no per-task ERROR or stack traces; this is by code reading of `isInterruptOrReject`), plus the session latch so a world reload in the same session is served by DH's own generator (code reading: no registration → `createRetrievalQueue` builds `DhWorldGenerator`). After a restart our generator is tried again; the manual switch is `dh_sand_generator = false`.

## Numbers
- Detail range answered by our generator: 0 to 12. Per call: 64 × 64 = 4096 depth evaluations at any level.

## Surprises, problems, deviations from the prompt
- No client run: a CurseForge client cannot be launched and driven from this session, so no log check and no forced-failure test. No test hooks were added or left. The owner's `DistantHorizons.toml` was not touched.
- True "DH behaves as before" after a failure is impossible without a restart or reload of the world; the report says so plainly.
- The owner's `SURFACE_THEN_CHUNKS` plan makes DH generate real chunks too ("330,384 left"); that is the owner's setting.

## How the owner can see it
1. Install the jar (already in `ArrakisTest01/mods`).
2. Delete `saves/<world>/dimensions/minecraft/overworld/data/DistantHorizons.sqlite` (it holds rough or failed LODs).
3. Set `generatorPlan = "SURFACE_ONLY"` in the DH settings or config, then load.
You look: far terrain at the horizon is sand-coloured with no water ripples; the "chunks left" counter is small and falling; no `Dune Sand` or `Uncaught Gen Task` error spam in `latest.log`; the near/far join. If it fails: set `dh_sand_generator = false` in `config/islandcraft_dunesofarrakis-common.toml` and reload.

## Suggested next steps (your view)
- Send the log lines mentioning "Dune Sand" after the next run. If the counter stays huge, the log should show whether DH keeps asking for real chunks.

## Owner observations (human)

