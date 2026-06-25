# AeroBBS rotation fix — no-mixin build (v0.4.0)

**Built JAR:** `build/libs/aeronautics-cml-bridge-1.21.1-0.4.0-nomixin.jar`

This build fixes the ship-rotation gimbal lock **without any Mixin on a BBS CML
class**, so it loads cleanly under Sinytra Connector (no `BBSRendering`/`L10n`
init crash).

---

## TL;DR

- Rotation past ±90° pitch (and full 360° yaw/roll) now plays back correctly.
- No Mixin → BBS CML initialises normally. The mixin config and mixin classes
  were removed from this build.
- Rotation is recorded as **CONST-hold** keyframes (held per tick, never
  Euler-interpolated across the gimbal discontinuity), and smoothed by
  **record-time quaternion slerp** sub-keyframes. Translation stays smooth.
- One config knob: `rotationSubdivisions` (default `4` = smooth).

Drop the JAR in `mods/` alongside BBS CML, Sable/Aeronautics and the same
dependencies you already use. Re-record — **old films keep the old broken
rotation; you must record new ones.**

---

## Root cause (two compounding bugs, both confirmed against BBS CML 1.10.3 source)

The recorder writes the ship's world quaternion into the form's `"transform"`
property channel as ZYX-Euler. At render time BBS CML does, per form:

```
FormProperties.applyProperties(form, float tick)         // FRACTIONAL tick
  -> KeyframeChannel.find(tick) -> KeyframeSegment.createInterpolated()
  -> TransformKeyframeFactory.interpolate -> Transform.lerp(...)   // uses left keyframe's interp, default LINEAR
  -> form.transform runtime value
FormRenderer.render -> applyTransforms -> MatrixStackUtils.applyTransform(stack, transform)
  -> Rz(rotate.z) * Ry(rotate.y) * Rx(rotate.x) * Rz(rotate2.z) * Ry(rotate2.y) * Rx(rotate2.x)
```

**Bug 1 — wrong angles past 90°.** The old recorder used JOML's
`Quaternionf.getEulerAnglesZYX`. In JOML 1.10.5 those angles do **not** invert
BBS's `Rz·Ry·Rx` once you pass 90° or rotate off a single axis — so the *static*
orientation is already wrong past 90°, before any interpolation. (Proven: a 91°
yaw reconstructs 180° off; a 60°-yaw/80°-pitch pose reconstructs 44° off.)

**Bug 2 — interpolation across the gimbal discontinuity.** Even with correct
angles, BBS linearly interpolates Euler keyframes at fractional ticks. Across the
±90° singularity two axes flip 180°, so the lerp swings the ship wildly (the
"pitch flips twice during a 360° yaw" symptom). Per-tick keyframes do **not**
help, because BBS still interpolates *between* them at partial ticks.

## The fix

1. **Correct extraction.** `RealCmlRecorder.toBbsEulerZYX(q, out)` computes, in
   double precision straight from the quaternion, the ZYX angles that satisfy
   `Rz(out.z)·Ry(out.y)·Rx(out.x) == q` exactly — the true inverse of what BBS
   reconstructs. Verified over 1,000,000 random orientations: exact away from the
   poles, < 0.5° at the exact pole.

2. **CONST hold (no cross-keyframe interpolation).** Every rotation keyframe is
   marked `Interpolations.CONST`, which BBS evaluates as "hold the left
   keyframe's value across the whole segment" (`Easings.CONST -> 0`; BBS uses the
   same trick in `KeyframeChannel.insertSpace`). So at any render tick the ship
   shows the **exact recorded orientation** — there is never a lerp across the
   discontinuity, so gimbal lock cannot occur. Translation is unaffected (it lives
   on the separate `keyframes.x/y/z` channels and stays linear/smooth).

3. **Record-time slerp for smoothness.** CONST hold alone would step rotation at
   the 20 Hz record rate. So between consecutive ticks the recorder spherically
   interpolates (`Quaternionf.slerp`, shortest path) and emits
   `rotationSubdivisions` intermediate CONST keyframes. Each is an exact
   orientation, so smoothing happens with real quaternion interpolation on the
   recording side — where the quaternions exist — instead of at render time
   (which would need a Mixin). `4` ≈ 80 Hz effective, visually smooth.

### Why not the Mixin (the original approach)

The quaternion-at-render-time Mixin needs to hook a BBS CML class. Registering
**any** Mixin against a BBS class crashes BBS CML init under Sinytra Connector:
`client/BBSRendering.java` has a static field `replayHudMenu = new UIBaseMenu(){}`;
BBS's own `WindowMixin` touches `BBSRendering` early in `Minecraft.<init>`
(via `Window.getWidth`), which runs `BBSRendering.<clinit>` → `UIBaseMenu` →
`UIKeys`/`Keys` → `L10n.lang()` → `BBSModClient.getL10n()` returns **null**
because `onInitializeClient()` hasn't run yet. Adding our Mixin perturbs class
load order enough to expose this latent BBS bug. This build sidesteps it entirely
by doing the quaternion math at record time.

(If you ever truly need render-time quaternions — e.g. extreme slow-motion where
even 80 Hz stepping shows — the only path is a Mixin that *fixes BBS's init
order*, e.g. inject `BBSModClient.getL10n` HEAD to lazily create `l10n`. That is
unverified and fragile; the record-time approach here avoids it.)

---

## Config

`config/aeronauticscml.json`:

```json
{
  "rotationSubdivisions": 4
}
```

- `1` — pure per-tick hold. Correct orientation, rotation visibly steps at 20 Hz.
- `4` (default) — slerp 4 sub-keyframes per tick (~80 Hz). Smooth.
- up to `32` — extra smoothness for slow-motion edits (more keyframes per second).

Only affects rotation. Translation is always smooth.

---

## What was verified vs. what you should test

**Verified here (without launching Minecraft):**
- Compiles; the JAR contains **no** mixin config and **no** mixin classes, so it
  cannot trigger the BBS init crash (this is exactly the difference from the
  crashing `transform-only` build).
- The rotation math is correct: a standalone harness (`_analysis/verify/`) runs
  the exact record→reconstruct math against the same JOML 1.10.5 the game uses.
  A full 360° yaw sweep gives 0.000° keyframe error, shows the 180° LINEAR gimbal
  flip that this build removes, and ≤ 4.5° (subdiv 1) / ~1.1° (subdiv 4) CONST
  error. 1,000,000-orientation round-trip < 0.5° worst case (at the pole only).

**You should test in-game (I can't run the modded client here):**
1. Pitch loop 0°→360°: ship completes the flip, no bounce-back near 90°.
2. Yaw 360°: pitch stays put, no double-flip.
3. Barrel roll (combined): continuous.
4. Confirm BBS CML opens normally (no `L10n`/`BBSRendering` crash on launch).

---

## Build

```
JAVA_HOME=<JDK 21>
./gradlew build       # add --offline once dependencies are cached
```

Output: `build/libs/aeronautics-cml-bridge-1.21.1-0.4.0-nomixin.jar`.

The `libs/` jars (BBS CML, Sable, Aeronautics) are `compileOnly` — they are not
bundled, exactly as before.
