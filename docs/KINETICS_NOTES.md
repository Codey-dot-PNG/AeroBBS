# Create kinetics in BBS replays — renders in the editor (v0.5.0-renderhook)

**Built JAR:** `build/libs/aeronautics-cml-bridge-1.21.1-0.5.0-renderhook.jar`

Recorded **Create kinetic blocks** (spinning shafts/cogs/gearboxes) and **Aeronautics
propellers** now animate during BBS CML film playback **in the editor preview AND
in-world** — not just in-world. No Mixin on any BBS class.

Still **experimental**; compiles and the JAR is clean, but not run in a client by me.

---

## Architecture (why it renders in the replay editor now)

The first kinetics build drew via a Fabric `WorldRenderEvents` hook, which only fires
during **in-world** playback — never in the BBS editor's preview viewport. The fix is
to render **through BBS's own form system**, which BBS runs in *both* contexts:

- We declare a `bbs-addon-client` Fabric entrypoint (`AeroBBSAddon`). BBS discovers it,
  registers it on `BBSMod.events`, and posts `RegisterFormsRenderersEvent`.
- In that event we **replace BBS's `StructureForm` renderer** with
  `AeroStructureFormRenderer` (a subclass of BBS's `StructureFormRenderer`).
- Its `render3D` calls `super.render3D` (BBS's normal static ship) and then draws the
  ship's Create kinetic block-entities on top, in the form's own local space, reusing
  the proven virtual-`Level` + BER path (`KineticReplayRenderer` /
  `VirtualStructureLevel`).
- Because BBS calls form renderers in the editor preview and in-world, the kinetics
  render in **both**.

> Mapping note: the BBS jar uses intermediary MC names (`class_4587`) while we compile
> against mojmap, so `render3D` reaches the render context's `stack` reflectively; at
> runtime (under Sinytra) it is a mojmap `PoseStack`. This was verified to compile.

This is cleaner than spawning separate anchored block-forms: no film mutation, no
anchor bookkeeping, and it applies to **existing** films automatically.

## How to test

1. Drop this JAR in `mods/` (replacing the previous `…-renderhook.jar`).
2. In `config/aeronauticscml.json` set:
   ```json
   { "experimentalKineticRender": true, "propellerSpeedScale": 1.0 }
   ```
   (Live-reloaded ~once/sec.) The renderer is always registered; this flag gates
   whether it actually draws kinetics. With it `false`, ship structures render exactly
   as stock BBS.
3. Open a film containing a recorded ship (a `StructureForm`) **in the BBS film
   editor**, and scrub/play the timeline. The shafts/cogs/propellers should spin **in
   the preview viewport**. Playing the film in-world should also show them.

### Diagnostics (in the log)
- On startup: `Registered AeroStructureFormRenderer - ship kinetics will render in the
  BBS editor + in-world.` → the addon loaded and the renderer override is active.
- First time a ship renders: `Built kinetic model '<file>': N kinetic BEs / M blocks`.
- While drawing (throttled ~1/sec): `kinetic render: N BE(s) for '<file>'`.

If you see the first log but never `Built kinetic model …`, the structure `.nbt` isn't
resolving. If you see `Built … N kinetic BEs` with `N>0` but nothing spins, see below.

## Config

| Key | Default | Meaning |
|---|---|---|
| `experimentalKineticRender` | `false` | Draw ship Create kinetics (editor + world). |
| `propellerSpeedScale` | `1.0` | Multiplier for propeller **blade** spin (shafts/cogs ignore it). Tune if blades spin too fast/slow; `0` holds them. |

## What renders where

| Feature | In-world | Editor preview |
|---|---|---|
| Static ship structure | ✅ (BBS) | ✅ (BBS) |
| Create kinetics (shafts/cogs) | ✅ | ✅ **(new)** |
| Aeronautics propellers | ✅ | ✅ **(new)** |
| Ropes | ✅ | ✅ **(new)** — needs a re-record |
| Debug marker | ✅ (if enabled) | ❌ |

## Known limitations / remaining phases

- **Untested in a client by me.** Reasoned + compiled only.
- **Ropes now render in the editor (re-record required).** The recorder stores ropes
  per **ship uuid** (`ropes/<uuid>.json`) as absolute polylines plus the ship's
  center-bottom anchor pose per tick. `RopeReplayRenderer` (called from the form
  renderer) transforms them into the form's local space using the inverse anchor pose;
  the tick comes from `FormRenderingContext.entity.getAge()`. In-world still draws them
  absolute. Gated by `experimentalDynamicRender`. *Unverified:* whether `getAge()`
  equals the playback tick — if ropes appear frozen at one frame, that's the cause.
- **Block updates mid-recording (#2) not implemented.** Still a single snapshot. A
  naive "draw changed blocks on top" only handles *additions* — it can't hide *removed*
  or *changed* blocks from BBS's static mesh, so the real fix is BBS's
  visibility-keyframed overlay-snapshot approach (capture per-tick block deltas →
  emit snapshot generations keyframed visible/invisible). Heavier; its own change.
- **Lighting** uses BBS's per-form light (`FormRenderingContext.light`), so kinetics
  respect day/night and roughly match the static hull. It's one light value for the
  whole form, not per-block, so deep-interior shading is approximate.
- **Constant-speed** kinetics reproduce well; speed that changed mid-recording is
  approximate.
- **Propeller *bearings*** (big assembled propellers) don't render: the blades are a
  rotating *contraption* of blocks, not a block-entity, so they're outside the BER
  path. Small single-block propellers do go through the BER. Bearings would need the
  contraption-as-forms approach (normal blocks anchored + rotation-keyframed).

## If kinetics don't render (`N>0` but invisible)

The Flywheel visualization gate is confirmed *not* the issue (our virtual level is a
separate instance → `supportsVisualization` is `false` → the BER runs). Likely causes:
- A silent per-BE render exception (each is caught so one bad block can't kill the
  rest) — temporarily log the `Throwable` in `KineticReplayRenderer.renderInFormSpace`'s
  inner catch and send the trace.
- The reflective `stack` cast returned null (the addon log would still appear but
  nothing draws) — check that `Registered AeroStructureFormRenderer` printed.
- Alignment/scale: kinetics draw but offset, or the `StructureForm` scale isn't 1.

Send a log with those three diagnostic lines (+ any trace) and we'll iterate.
