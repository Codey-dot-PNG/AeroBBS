# Render-hook investigation: dynamic contraption rendering in BBS replays

**Goal:** make Create kinetic blocks (shafts/cogs), Aeronautics propellers, and
ropes render/animate during BBS CML film playback — the things a static
`StructureForm` block snapshot can't show.

**Verdict: feasible without a Mixin on any BBS class and without an upstream BBS
change.** The render path is reachable through public API + a stock Fabric
world-render event. The remaining hard part is purely *what we draw*, not *how we
hook in*.

---

## 1. Why the current snapshot can't show these

BBS replays a ship as a `StructureForm`, which `StructureFormRenderer` draws as a
**baked static block mesh**. It runs **no `BlockEntityRenderer`s, no Flywheel, no
contraption/rope renderers**, and the `.nbt` is captured **once** when the ship is
first seen. Therefore:

| Thing | Why it's missing/static |
|---|---|
| Create shafts/cogs spinning | Drawn by `KineticBlockEntityRenderer`/Flywheel; shafts often render *only* via Flywheel → invisible in a static mesh. |
| Propeller blades | BER-rendered, not blocks; `BlockEntityPropeller` exposes only thrust/airflow/direction, no blade geometry or spin angle. |
| Ropes | Not blocks at all (`RopePhysicsObject`, a physics object). |
| Block updates mid-recording | The `.nbt` is snapshotted once and never refreshed. |

All of this is the **same wall** as the rotation Mixin: the clean fix is to run
the real renderers at playback time, and hooking BBS's own render path needs a
Mixin on a BBS class — which trips the `BBSRendering`/`L10n` init crash under
Sinytra (see `FIX_NOTES.md`).

## 2. The unlock (Part A — confirmed reachable, no Mixin)

BBS renders films **in the vanilla world-render pass**, not in an isolated UI
viewport. From `mchorse.bbs_mod.film.Films` (client):

```java
public void render(WorldRenderContext context) {       // Fabric WorldRenderContext!
    for (BaseFilmController controller : this.controllers) controller.render(context);
}
```

So we can register **our own** Fabric `WorldRenderEvents` callback in the same
pass and draw in the same camera space. Everything we need is public:

- `BBSModClient.getFilms().getControllers()` → active `BaseFilmController`s.
- `controller.getTick()` → current integer playback tick; `controller.paused`.
- `controller.film.replays.getList()` → the `Replay`s.
- Per replay (these are the channels **our recorder writes**):
  - position: `replay.keyframes.x/y/z.interpolate(tick)` (+ `replay.relativeOffset`),
  - rotation: the `"transform"` property channel interpolated at `tick`
    (`replay.properties` → `KeyframeChannel.interpolate`) → `Transform.rotate/rotate2`,
  - identity: `replay.uuid` / `replay.form.get()` (`StructureForm.structureFile`
    is `world:<uuid>.nbt`).
- replay-local fractional tick: `replay.getTick(controller.getTick()) + partialTick`.

Because we use the **same `WorldRenderContext` (camera + matrices) and the same
keyframes/tick** BBS uses, anything we draw lands exactly where BBS draws the
static ship. **No Mixin, no init crash.**

> The included build (`AeroBBSClient` + `ReplayContraptionRenderer`) implements
> exactly this and draws an **alignment marker** (oriented box + axes) at each
> ship replay. If the marker tracks the ship through a recorded flight, Part A is
> proven and Part B is just "swap the marker for real geometry."

### Identifying our replays

Best: have the recorder tag each ship replay (e.g. `replay.group.set("aeronauticscml")`)
so the hook filters cheaply and unambiguously. The PoC falls back to
"`form instanceof StructureForm` whose `structureFile` starts with `world:` and
resolves to a loadable `.nbt`."

## 3. Part B — what to draw (the real work)

At playback the live ship/SubLevel is gone; we have the film (keyframes) + the
`.nbt`. Two data sources:

- **Reconstructable from the static `.nbt`** (blocks + block-entity NBT): Create
  kinetics, most BER-driven blocks.
- **Must be captured per tick at record time and stored in the film**: rope
  polylines, and anything whose animation depends on live state we can't recover.

### 3a. Create kinetics & BER blocks — render block entities in a virtual world

Per frame, for each ship replay, set the `PoseStack` to the replay transform
(translate `worldPos − cameraPos`, then the recorded rotation) and render the
recorded block entities through `BlockEntityRenderDispatcher.render(be, partial,
poseStack, bufferSource)`.

**Key insight — kinetics animate "for free":** Create's kinetic BER derives blade
angle from `getSpeed()` × Create's *global* render clock
(`AnimationTickHolder.getRenderTime()`), not from a per-frame delta we own. So if
the BE is instantiated from the snapshot NBT (which carries its kinetic `speed`)
and rendered via its BER, **it spins on its own**. Constant-speed kinetics
(the common case) reproduce well; speed that varied mid-recording is approximate
(snapshot captures one speed).

**The hard sub-problem — the virtual `Level`.** A `BlockEntity` needs a `Level`
for `getLevel()`/`getBlockState()`/light lookups during BER rendering. Options,
cheapest-first:
1. A minimal `BlockAndTintGetter` + a thin `Level` stub backed by the snapshot's
   `Map<BlockPos,BlockState>` + `Map<BlockPos,BlockEntity>`, full-bright light.
   (Create ships exactly this idea as `VirtualRenderWorld` — worth mirroring.)
2. Reuse the real client `Level` but override neighbor/light reads — fragile.

This stub is the single biggest implementation + correctness risk and the reason
Part B is a separate phase: a `Level` has a large surface, and several Create
renderers read more than just their own BE. Recommend porting/adapting Create's
`VirtualRenderWorld` rather than writing one blind.

**Flywheel:** in-world Create may instance kinetics via Flywheel; the BER path is
the contraption/fallback path. Calling the BER directly invokes the animating
path. Verify Create isn't gating BER rendering behind "is in a real contraption."

**Double-render:** BBS still draws the static structure. Either (a) keyframe the
StructureForm `visible=false` in the film so only our dynamic render shows (clean,
but nothing renders if our hook is disabled/fails), or (b) accept overdraw for the
PoC. Recommend (a) once Part B is trusted, gated by config.

### 3b. Ropes — capture polyline per tick, draw segments

Ropes are fully capturable server-side:

```java
SubLevelPhysicsSystem sys = SubLevelPhysicsSystem.get(serverLevel);
for (ArbitraryPhysicsObject o : sys.getArbitraryObjects())
    if (o instanceof RopePhysicsObject rope) rope.getPoints(); // List<Vector3d> polyline
```

Plan: at record time, sample each rope's `getPoints()` per tick and store the
polyline in the film (a custom per-replay data channel, or a side file keyed to
the film id). At playback, the hook reads the polyline for the current tick and
draws it as camera-facing quad segments (or a thin tube) with
`rope.getCollisionRadius()` as thickness. Self-contained; no virtual `Level`
needed. **This is the most tractable Part B item and a good first dynamic feature.**

### 3c. Propellers — hardest

`BlockEntityPropeller` gives `getBlockDirection()`, `getAirflow()`,
`getThrust()`, `isActive()` — no blade mesh, no spin angle. If the blades are
real blocks they already appear (static) via the snapshot. If BER-rendered, they
fall under 3a (render the propeller BE via its BER; it may spin from its own
state + global clock). Exact spin matching needs Aeronautics' BER formula
(in the jarjar'd `dev.eriksonn.aeronautics` jar). Treat as approximate.

### 3d. Block updates mid-recording (issue #2)

Independent of the render hook. Watch `SubLevelPhysicsSystem.handleBlockChange` /
re-snapshot on change, write a new `.nbt`, and swap via visibility-keyframed
overlay `StructureForm`s (one per snapshot generation, `visible` keyframed so only
the current generation shows). Heavy for frequent changes; fine for occasional.

## 4. Risks / open questions

- **Virtual `Level` correctness** (3a) — biggest risk; port Create's `VirtualRenderWorld`.
- **Lighting** — full-bright is ugly; ideally sample world light at the ship pos.
- **Flywheel gating** of Create BER rendering — verify the BER animates when called directly.
- **Performance** — re-rendering N block entities per frame per ship; cache geometry where possible.
- **Multiplayer file access** — the `.nbt` lives in the server world folder; client-side load assumes singleplayer/integrated server (the common BBS workflow). Note the limitation or transfer the structure.
- **Untestable here** — none of the rendering was run in a modded client; every Part B step needs in-game iteration.

## 5. Recommended phased plan

1. **Phase 0 (in this build):** non-Mixin world-render hook + alignment marker.
   Verify the marker tracks a recorded ship. Proves Part A.
2. **Phase 1:** ropes (3b) — record-time polyline capture + segment rendering. No
   virtual `Level`; highest value-to-risk.
3. **Phase 2:** Create kinetics (3a) — port `VirtualRenderWorld`, render block
   entities via BER, gate behind config, keyframe the static form invisible.
4. **Phase 3:** propellers (3c) + block updates (3d).

Conclusion: the architecture is sound and **does not require modifying BBS**; the
work is a contraption/rope renderer driven by BBS playback, built incrementally
behind a config flag.
