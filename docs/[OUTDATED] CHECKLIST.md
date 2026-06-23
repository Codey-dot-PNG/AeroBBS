# Editing checklist

When adapting this bridge to a real Aeronautics / CML build:

## Aeronautics side

- [ ] Confirm the actual package name for `AeronauticsShip`. Update the
      `candidates` array in `AeronauticsModule.probe()`.
- [ ] Confirm the actual package name for `ShipRegistry`. Update the
      `registryCandidates` array in `ReflectiveSppPoseProvider.Adapter.detect()`.
- [ ] Confirm method names for: UUID, name, position, rotation, velocities,
      AABB. Add your method names to the `find()` / `findOptional()` calls.
- [ ] Confirm `ShipRegistry.forLevel(Level).all()` is the correct entry
      point. If not, replace `invokeStatic(...)` in `shipsInLevel()`.

## BBS CML side

- [ ] Confirm the actual package name for `CmlRecorder`. Update the
      `candidates` array in `CmlModule.probe()`.
- [ ] Confirm the arity of `recordFrame(...)`. The reflective recorder
      currently invokes a 23-arg version. If CML takes fewer args, trim
      the `invoke(...)` call in `ReflectiveCmlRecorder.recordFrame()`.
- [ ] Confirm `start(String)` / `stop()` are the right entry points.

## Verifying

- [ ] Run a server with both mods installed.
- [ ] `/aeronauticscml status` should print `aeronautics=OK` and a
      recorder backend name (either `bbs-cml/reflective:...` or
      `file-json/fallback`).
- [ ] `/aeronauticscml start`, fly a ship around, then
      `/aeronauticscml stop`.
- [ ] Check the CML recording (or the JSONL fallback file) for frames.
