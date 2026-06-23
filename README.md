# AeroBBS — Aeronautics CML Bridge

Disclosure: This mod was primarily AI generated, I'm sorry :(
It was specifically made for my WIP animated series, so I will most likely not be supporting or maintaining it. Feel free to fork and modify to your hearts content tho!

A NeoForge 1.21.1 mod that bridges **Sable's Physics Pipeline (SPP)** / **Create Aeronautics** ship movements into **BBS CML Edition** recordings, so BBS CML can capture ship motion frame-by-frame and replay ships visually inside the BBS CML cinematic editor.

> Repository name: `AeroBBS`. Mod ID: `aeronauticscml`. Built artifact: `aeronautics-cml-bridge-1.21.1-0.2.0.jar`.

---

## ⚠️ Loader compatibility — read this first

| Mod | Loader | Mod ID |
|-----|--------|--------|
| Sable (SPP) 2.0.3 | **NeoForge** 21.1.228 | `sable` |
| Create Aeronautics 1.3.0 (bundled) | **NeoForge** (lowcode) | `aeronautics_bundled` |
| BBS CML Edition 1.10.3 | **Fabric** (Fabric Loader 0.16.14+, Fabric API) | `bbs` |

BBS CML is a Fabric mod and cannot run on pure NeoForge. **You must install [Sinytra Connector](https://github.com/Sinytra/Connector) on your NeoForge server** to re-host BBS CML as a NeoForge-compatible mod. This is the only supported configuration for all three mods to coexist.

Once Sinytra Connector is installed, the bridge sees both SPP and BBS CML classes via the unified classpath and wires them together. All three dependencies are declared `optional` in `neoforge.mods.toml`, so the bridge itself loads cleanly even when one or more of them is missing — see the [soft-degradation matrix](#soft-degradation-matrix) below.

---

## What the bridge does

Every server tick (post-physics phase), when a recording session is active:

1. **Enumerate ships.** Iterate every `ServerLevel` and pull all SPP `ServerSubLevel`s via `SubLevelContainer.getContainer(serverLevel).getAllSubLevels()`. Create Aeronautics contraptions are SPP SubLevels under the hood, so a single enumeration captures both raw SPP ships and Aeronautics ships.
2. **Snapshot each ship's pose.** For each SubLevel, read its `Pose3dc` (position + quaternion + scale), its `RigidBodyHandle` linear/angular velocity, and optionally its world-space AABB. Wrap everything in an immutable `ShipPose` record.
3. **Apply the whitelist.** An empty `shipWhitelist` means "record everything". A non-empty whitelist is matched case-insensitively against the ship's name (substring) **or** as a UUID-prefix match against the ship's UUID.
4. **Forward to the recorder.** Each surviving `ShipPose` is handed to the active `ICmlRecorder`. There are two implementations:
   - **`RealCmlRecorder`** (production target, used when BBS CML is on the classpath):
     - On first sight of a ship, snapshots its blocks to `<world>/generated/minecraft/structures/<uuid>.nbt` via `StructureSnapshotService`, then creates a BBS CML `StructureForm` pointing at it with the `world:` prefix. The form is assigned to a fresh `Replay` on the session's `Film`, so BBS CML will render the actual ship blocks during playback — not just a camera.
     - Every tick, inserts `x`/`y`/`z` keyframes into `replay.keyframes` (entity-level position), and inserts a `Transform` keyframe into the form's `"transform"` property channel (`replay.properties.getOrCreate(form, "transform")`). The transform stores Euler angles (ZYX order) decomposed from the ship's world quaternion, unwrapped against the previous frame so linear interpolation doesn't take the long way around at the ±π boundary.
     - On stop, persists the film via `FilmManager.save(name, film.toData())`. The replays then appear in BBS CML's replay UI and can be played back like any player-recording.
   - **`DefaultFileCmlRecorder`** (fallback, used when BBS CML is absent): writes one JSON line per frame to `session-<name>.jsonl` under the configured `fallbackOutputDir`. Useful for offline replay or post-processing in external tools. See `docs/RECORDING_FORMAT.md` for the schema.

### Optional Mixin: `FormPropertiesMixin`

A client-side Mixin (`aeronauticscml.mixins.json`, `required: false`) targets BBS CML's `FormProperties.applyProperties(...)`. After BBS CML applies its own properties, the Mixin looks for four optional channels — `ac_qx`, `ac_qy`, `ac_qz`, `ac_qw` — on the form, interpolates them at the current tick, normalises the resulting quaternion, decomposes it to ZYX Euler angles, and writes them into `form.transform.get().rotate` as `(roll, pitch, yaw)`.

This is an alternative quaternion-based path. `RealCmlRecorder` does **not** populate `ac_q*` channels (it writes Euler angles directly to the `"transform"` property channel), so the Mixin is dormant when recording with the built-in recorder. It exists for users who want to drive a form's rotation from external `ac_q*` keyframes (e.g. imported from the JSONL fallback) and have BBS CML apply them at render time.

---

## What the bridge does NOT do (yet)

- **Material/Block-entity state in replays.** Ship blocks are captured as a vanilla `StructureTemplate` (.nbt) snapshot taken at recording start. Block-entity state (signs, fluid tanks, Create contraption internals) is not re-sampled mid-recording, so changes to those during the recording will not be reflected in the replay.

---

## Building the JAR

```bash
cd AeroBBS
./gradlew build
# Output: build/libs/aeronautics-cml-bridge-1.21.1-0.2.0.jar
```

The build expects the following JARs in `libs/` (already included in the repo):

- `sable-neoforge-1.21.1-2.0.3.jar`
- `sable-companion-common-1.21.1-1.6.0.jar` (bundled inside SPP — extract from `META-INF/jarjar/`)
- `bbs-cml-edition-1.10.3-1.21.1.jar`
- `create-aeronautics-bundled-1.21.1-1.3.0.jar`

All four are declared `compileOnly` in `build.gradle` — they are **not** bundled into the bridge's JAR, they must be present in the server's `mods/` folder at runtime.

The Gradle build downloads the real NeoForge + Minecraft jars from the NeoForge maven on first run (~200 MB). Java 21 toolchain is required.

> **Version note:** `gradle.properties` still lists `mod_version=1.21.1-0.1.0`, but `build.gradle` overrides the project version to `1.21.1-0.2.0`. The 0.2.0 number is what ends up in the artifact name.

---

## Installation

Drop the built JAR into your server's `mods/` folder alongside:

- `sable-neoforge-1.21.1-2.0.3.jar`
- `create-aeronautics-bundled-1.21.1-1.3.0.jar`
- `bbs-cml-edition-1.10.3-1.21.1.jar`
- **Sinytra Connector** (latest for NeoForge 1.21.1)
- Fabric API (the version Sinytra Connector recommends for 1.21.1)

---

## Configuration

`saves/<world>/serverconfig/aeronauticscml-server.toml` in singleplayer, or `world/serverconfig/aeronauticscml-server.toml` on a dedicated server:

```toml
#Ship names or UUID prefixes to record. Empty list = record all ships.
shipWhitelist = []
#Ticks between captured frames. 1 = 20 Hz. 5 = 4 Hz (lighter load).
recordIntervalTicks = 1
#Include linear + angular velocity in each frame (recommended for motion-blur replay).
captureVelocity = true
#Include the ship's world-space AABB in each frame.
captureAABB = false
#When BBS CML is not installed, dump frames to this directory as JSON.
fallbackOutputDir = "aeronauticscml_recordings"
```

- **`shipWhitelist`**: each entry is matched case-insensitively. Name match is a *substring contains* test; UUID match is a *prefix* test. So `"550e8400-e29b-"` and `"HMS Relentless"` are both valid entries.
- **`recordIntervalTicks`**: clamped to `[1, 100]`. The first tick of the session anchors the cadence (`(tick - sessionStartTick) % interval == 0`).
- **`captureVelocity` / `captureAABB`**: only affect the `ShipPose` snapshot and the JSONL fallback recorder. `RealCmlRecorder` ignores both — it only writes position + rotation keyframes to BBS CML.

---

## Commands

All commands require permission level 2 (op). The root literal is `aeronauticscml`; `session start` / `session stop` are aliases of `start` / `stop` for muscle-memory compatibility with BBS CML's own `/bbs session` flow.

| Command                          | Effect                                                                                  |
|----------------------------------|-----------------------------------------------------------------------------------------|
| `/aeronauticscml start`          | Opens a new recording session. Session name is auto-generated as `auto-<yyyyMMdd-HHmmss>`. |
| `/aeronauticscml stop`           | Closes the current session, saves the CML film (or flushes the JSONL file). Reports frames captured and active ship count. |
| `/aeronauticscml status`         | Prints: `recording`, `backend`, `aeronautics` (OK/MISSING), `activeShips`, `sessionFrames`, `lifetimeFrames`, `lastTick`. |
| `/aeronauticscml session start`  | Alias of `start`.                                                                       |
| `/aeronauticscml session stop`   | Alias of `stop`.                                                                        |

If the bridge is not ready (e.g. SPP not installed), `start` will fail with a clear in-game message naming the missing component.

---

## Architecture

```
+-------------------+        getAllSubLevels()           +------------------------+
|  SPP runtime      |  --------------------------------> | SppPoseProvider        |
|  (SubLevels)      |                                     |  (direct API calls)    |
+-------------------+                                     +-----------+------------+
                                                                      |
                                                          ShipPose (record)
                                                                      v
+-------------------+        recordFrame(ShipPose)        +------------------------+
|  BBS CML runtime  |  <-------------------------------- | RealCmlRecorder        |
|  (Film / Replay)  |                                     |  - per-ship: snapshot  |
+-------------------+                                     |    .nbt + StructureForm|
                                                          |  - per-tick: x/y/z +   |
                                                          |    form "transform"    |
                                                          |    Euler keyframes     |
                                                          +------------------------+
                                                                      ^
                                                                      |
                                                        RecordingService (per-tick pump, whitelist, cadence)
                                                                      ^
                                                                      |
                                                              BridgeConfig (server.toml)
```

### Module layout

| Package                                       | Responsibility                                                                                       |
|-----------------------------------------------|------------------------------------------------------------------------------------------------------|
| `bridge.mod`                                  | `@Mod` entrypoint. Wires config + modules + service; subscribes NeoForge events.                     |
| `bridge.config`                               | `BridgeConfig` — NeoForge `ModConfigSpec` for `server.toml`.                                          |
| `bridge.api`                                  | `IShipPoseProvider`, `ICmlRecorder`, `ShipPose` record. The abstraction layer both sides plug into.  |
| `bridge.aeronautics`                          | `AeronauticsModule.probe()` (presence check), `SppPoseProvider` (direct SPP API), `NoopPoseProvider`, `StructureSnapshotService` (.nbt capture). |
| `bridge.cml`                                  | `CmlModule.probe()` (presence check), `RealCmlRecorder` (direct BBS CML API), `DefaultFileCmlRecorder` (JSONL fallback). |
| `bridge.recording`                            | `RecordingService` — per-tick pump. Whitelist, cadence, session lifecycle, status.                   |
| `bridge.event`                                | `ServerTickHandler` — adapter from `ServerTickEvent.Post` to `RecordingService.onServerTick`.         |
| `bridge.command`                              | `BridgeCommands` — `/aeronauticscml start|stop|status|session ...`.                                  |
| `bridge.mixin`                                | `FormPropertiesMixin` — optional client-side Mixin for `ac_q*`-driven form rotation.                  |

### Soft-degradation matrix

| Aeronautics/SPP | BBS CML | Behaviour                                            |
|-----------------|---------|------------------------------------------------------|
| yes             | yes     | Real recording into CML (production target)          |
| yes             | no      | Real recording into JSON-on-disk fallback            |
| no              | yes     | Loads, but does nothing each tick (no ships to record) |
| no              | no      | Loads, does nothing — useful for development servers  |

This is achieved by `AeronauticsModule.probe()` and `CmlModule.probe()` returning no-op implementations when their target classes are missing — the recording pipeline still runs but produces/accepts no data.

---

## API reference (confirmed against shipped JARs)

### SPP — read side (`SppPoseProvider`)

| Need              | Source                                                                     | Returns              |
|-------------------|----------------------------------------------------------------------------|----------------------|
| All ships in level| `SubLevelContainer.getContainer(serverLevel).getAllSubLevels()`            | `List<ServerSubLevel>` |
| Ship UUID         | `subLevel.getUniqueId()`                                                   | `UUID`               |
| Ship name         | `subLevel.getName()`                                                       | `String`             |
| Pose              | `subLevel.logicalPose()`                                                   | `Pose3dc`            |
| Position          | `pose.position()`                                                          | `Vector3dc`          |
| Orientation       | `pose.orientation()`                                                       | `Quaterniondc`       |
| Scale             | `pose.scale().x()` (uniform assumption)                                    | `double`             |
| AABB              | `subLevel.boundingBox()`                                                   | `BoundingBox3dc`     |
| Linear velocity   | `RigidBodyHandle.of(subLevel).getLinearVelocity(new Vector3d())`          | `Vector3d`           |
| Angular velocity  | `RigidBodyHandle.of(subLevel).getAngularVelocity(new Vector3d())`         | `Vector3d`           |
| Plot bounds       | `subLevel.getPlot().getBoundingBox()`                                      | `BoundingBox3ic`     |

`AeronauticsModule.validateRuntimeApi()` re-checks every method above at startup and logs a warning + falls back to `NoopPoseProvider` if any signature has drifted.

### BBS CML — write side (`RealCmlRecorder`)

| Need                      | Source                                                                  | Returns                    |
|---------------------------|-------------------------------------------------------------------------|----------------------------|
| Film manager              | `BBSMod.getFilms()`                                                     | `FilmManager`              |
| World folder              | `BBSMod.getWorldFolder()`                                               | `File`                     |
| Form architect            | `BBSMod.getForms()`                                                     | `FormArchitect`            |
| Load/create film          | `films.load("name")` / `films.create("name", null)`                     | `Film`                     |
| Film's replays            | `film.replays`                                                          | `Replays`                  |
| Add replay                | `replays.addReplay()`                                                   | `Replay`                   |
| Replay label              | `replay.label`                                                          | `StringValue`              |
| Replay UUID               | `replay.uuid`                                                           | `StringValue`              |
| Replay form               | `replay.form`                                                           | `ValueForm`                |
| Replay keyframes          | `replay.keyframes`                                                      | `ReplayKeyframes`          |
| Position channels         | `replay.keyframes.x` / `.y` / `.z`                                      | `KeyframeChannel<Double>`  |
| Insert double keyframe    | `channel.insert(float tick, Double value)`                              | `int`                      |
| Form properties           | `replay.properties`                                                     | `FormProperties`           |
| Get/create form channel   | `properties.getOrCreate(form, "transform")`                             | `KeyframeChannel<Transform>` |
| Insert transform keyframe | `channel.insert(float tick, Transform value)`                           | `int`                      |
| Create structure form     | `forms.create(Link.bbs("structure"))` (cast to `StructureForm`)         | `Form`                     |
| Point form at world .nbt  | `structureForm.structureFile.set("world:<filename>.nbt")`               | —                          |
| Save film                 | `films.save("name", film.toData() instanceof MapType mt ? mt : new MapType())` | `boolean`            |

`RealCmlRecorder.validateRuntimeApi()` re-checks every method/field above at startup.

### Structure snapshot — `StructureSnapshotService`

| Need                       | Source                                              | Returns                |
|----------------------------|-----------------------------------------------------|------------------------|
| Force-refresh ship AABB    | `subLevel.updateBoundingBox()`                      | —                      |
| Plot bounds                | `subLevel.getPlot().getBoundingBox()`               | `BoundingBox3ic`       |
| Plot chunk containment     | `plot.contains(ChunkPos)`                           | `boolean`              |
| Capture blocks             | `new StructureTemplate().fillFromWorld(level, pos, size, false, null)` | `StructureTemplate` |
| Serialise                  | `template.save(new CompoundTag())`                  | `CompoundTag`          |
| Write to disk              | `NbtIo.writeCompressed(tag, FileOutputStream)`      | —                      |
| Output location            | `<world>/generated/minecraft/structures/<uuid>.nbt` | `Path`                 |

Using Sable's plot bounds directly (without re-adding the plot chunk origin) is intentional — those bounds are already expressed in the embedded plot world's block coordinates. Ships of any size are handled correctly.

---

## Project layout

```
AeroBBS/
├── build.gradle                # NeoForge moddev, Java 21, deps from libs/
├── gradle.properties           # MC/NeoForge versions (note: build.gradle overrides mod_version to 0.2.0)
├── settings.gradle             # rootProject.name = 'aeronautics-cml-bridge'
├── libs/                       # compileOnly JARs (SPP, Aeronautics, BBS CML, Sable Companion)
├── docs/
│   ├── INTEGRATION.md          # Background on SPP / CML integration. ⚠ Partially stale: still references the old ReflectiveSppPoseProvider / ReflectiveCmlRecorder classes that have been replaced by direct API calls. Trust the source code in src/main/java/ first.
│   ├── CHECKLIST.md            # Same caveat — references the reflective implementation that no longer exists.
│   └── RECORDING_FORMAT.md     # JSONL schema for the fallback recorder. Still accurate.
└── src/main/
    ├── java/com/aeronauticscml/bridge/
    │   ├── api/                # IShipPoseProvider, ICmlRecorder, ShipPose
    │   ├── aeronautics/        # SppPoseProvider, NoopPoseProvider, StructureSnapshotService, AeronauticsModule
    │   ├── cml/                # RealCmlRecorder, DefaultFileCmlRecorder, CmlModule
    │   ├── command/            # BridgeCommands
    │   ├── config/             # BridgeConfig
    │   ├── event/              # ServerTickHandler
    │   ├── mixin/              # FormPropertiesMixin (client, optional)
    │   ├── mod/                # AeronauticsCmlBridge (entrypoint)
    │   └── recording/          # RecordingService
    └── resources/
        ├── META-INF/neoforge.mods.toml
        ├── assets/aeronauticscml/lang/en_us.json
        ├── aeronauticscml.mixins.json
        └── pack.mcmeta
```

---

## Troubleshooting

- **`/aeronauticscml start` says "Bridge is not ready. Aeronautics provider=MISSING"** — SPP isn't on the classpath. Make sure `sable-neoforge-*.jar` and `create-aeronautics-bundled-*.jar` are in `mods/` and that NeoForge actually loaded them (check `latest.log` for their mod-loading messages).
- **`status` shows `backend=file-json/fallback`** — BBS CML wasn't detected. Either BBS CML isn't installed, or Sinytra Connector isn't re-hosting it. Check that `mchorse.bbs_mod.BBSMod` is reachable on the classpath.
- **No `StructureForm` is created for a ship** — check the log for `[aeronauticscml] Ship <uuid> has no structure snapshot - replay will be camera-only`. The most common cause is the SubLevel's plot being null or its bounds failing the chunk-containment check (see `StructureSnapshotService`).
- **Replay rotation looks wrong (ship spins the long way around)** — the recorder unwraps Euler angles per ship per frame against the previous frame. If you manually edit the keyframes afterwards, re-introduce discontinuities and the unwrap invariant breaks. Either edit only with continuous curves or re-record.
- **Lots of `[aeronauticscml] Failed to snapshot SubLevel` warnings** — usually means SPP's API has changed. Check `AeronauticsModule.validateRuntimeApi()` in the log for which method signature drifted.

---

## License

AeroBBS is shared under the MIT License.
See LICENSE for details.

Copyright © 2026 Codey-dot-PNG
