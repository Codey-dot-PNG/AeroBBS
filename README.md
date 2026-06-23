<<<<<<< HEAD
# Aeronautics CML Bridge

A NeoForge 1.21.1 mod that bridges **Sable's Physics Pipeline (SPP)** / **Create Aeronautics** ship movements into **BBS CML Edition** recordings, so BBS CML can capture ship motion frame-by-frame.

## ⚠️ Loader compatibility — read this first

| Mod | Loader | Mod ID |
|-----|--------|--------|
| Sable (SPP) 2.0.3 | **NeoForge** 21.1.228+ | `sable` |
| Create Aeronautics 1.3.0 (bundled) | **NeoForge** (lowcode) | `aeronautics_bundled` |
| BBS CML Edition 1.10.3 | **Fabric** (Fabric Loader 0.16.14+, Fabric API) | `bbs` |

BBS CML is a Fabric mod and cannot run on pure NeoForge. **You must install [Sinytra Connector](https://github.com/Sinytra/Connector) on your NeoForge server** to re-host BBS CML as a NeoForge-compatible mod. This is the only supported configuration for all three mods to coexist.

Once Sinytra Connector is installed, the bridge sees both SPP and BBS CML classes via the unified classpath and wires them together.

## What the bridge does

1. Every server tick (post-physics), enumerate all SPP **SubLevels** in every `ServerLevel` via `SubLevelContainer.getContainer(serverLevel).getAllSubLevels()`.
2. For each SubLevel, snapshot its pose (`Pose3dc` → position + quaternion + scale), linear/angular velocity (`RigidBodyHandle.of(subLevel).getLinearVelocity(new Vector3d())`), and AABB.
3. For each ship, allocate a fresh BBS CML `Replay` in a session-specific CML `Film` named `aeronautics-bridge-<session>`. Each tick, insert keyframes into the replay's `x`, `y`, `z`, `vX`, `vY`, `vZ` channels (entity position/velocity), `yaw`/`pitch`/`headYaw`/`bodyYaw` (entity facing — prevents Minecraft's auto-face), and `replay.properties.getOrCreate(form, "transform")` with only the roll component (the form's local Z rotation, applied on top of the entity facing).
4. On stop, save the film via `FilmManager.save(...)`. The resulting Replay appears in BBS CML's replay UI and can be played back like any player-recording.

## What the bridge does NOT do (yet)

- **Per-block replay**: CML replays a player-form camera. To replay a *ship* visually, you'd post-process the replay or pair it with a custom actor that imports the ship's mesh.

## Building the JAR

```bash
cd aeronautics-cml-bridge
./gradlew build
# Output: build/libs/aeronauticscml-1.21.1-0.2.0.jar
```

The build expects the following JARs in `libs/` (already included):

- `sable-neoforge-1.21.1-2.0.3.jar`
- `sable-companion-common-1.21.1-1.6.0.jar` (bundled inside SPP — extract from `META-INF/jarjar/`)
- `bbs-cml-edition-1.10.3-1.21.1.jar`
- `create-aeronautics-bundled-1.21.1-1.3.0.jar`

The Gradle build downloads the real NeoForge + Minecraft jars from the NeoForge maven on first run (~200 MB).

## Installation

Drop the built JAR into your server's `mods/` folder alongside:
- `sable-neoforge-1.21.1-2.0.3.jar`
- `create-aeronautics-bundled-1.21.1-1.3.0.jar`
- `bbs-cml-edition-1.10.3-1.21.1.jar`
- **Sinytra Connector** (latest for NeoForge 1.21.1)
- Fabric API (the version Sinytra Connector recommends for 1.21.1)

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

## Commands

| Command                   | Permission | Effect                                              |
|---------------------------|------------|-----------------------------------------------------|
| `/aeronauticscml start`   | 2 (op)     | Opens a new CML recording session.                  |
| `/aeronauticscml stop`    | 2 (op)     | Closes the current session, saves the CML film.     |
| `/aeronauticscml status`  | 2 (op)     | Prints active ships, frame count, recorder backend. |

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
|  (Film / Replay)  |                                     |  (creates Replays,     |
+-------------------+                                     |   inserts keyframes)   |
                                                          +------------------------+
                                                                      ^
                                                                      |
                                                        RecordingService (per-tick pump)
```

### Soft-degradation matrix

| Aeronautics/SPP | BBS CML | Behaviour                                            |
|-----------------|---------|------------------------------------------------------|
| yes             | yes     | Real recording into CML (production target)          |
| yes             | no      | Real recording into JSON-on-disk fallback            |
| no              | yes     | Loads, but does nothing each tick (no ships to record) |
| no              | no      | Loads, does nothing — useful for development servers  |

## API reference (confirmed against shipped JARs)

### SPP (read side)

| Need | Source | Returns |
|------|--------|---------|
| All ships in level | `SubLevelContainer.getContainer(serverLevel).getAllSubLevels()` | `List<ServerSubLevel>` |
| Ship UUID | `subLevel.getUniqueId()` | `UUID` |
| Ship name | `subLevel.getName()` | `String` |
| Pose | `subLevel.logicalPose()` | `Pose3dc` |
| Position | `pose.position()` | `Vector3dc` |
| Orientation | `pose.orientation()` | `Quaterniondc` |
| Scale | `pose.scale()` | `Vector3dc` |
| AABB | `subLevel.boundingBox()` | `BoundingBox3dc` |
| Linear velocity | `RigidBodyHandle.of(subLevel).getLinearVelocity(new Vector3d())` | `Vector3d` |
| Angular velocity | `RigidBodyHandle.of(subLevel).getAngularVelocity(new Vector3d())` | `Vector3d` |

### BBS CML (write side)

| Need | Source | Returns |
|------|--------|---------|
| Film manager | `BBSMod.getFilms()` | `FilmManager` |
| Load/create film | `films.load("name")` / `films.create("name", null)` | `Film` |
| Film's replays | `film.replays` | `Replays` |
| Add replay | `replays.addReplay()` | `Replay` |
| Replay keyframes | `replay.keyframes` | `ReplayKeyframes` |
| Position channels | `replay.keyframes.x/y/z/vX/vY/vZ` | `KeyframeChannel<Double>` |
| Insert double keyframe | `channel.insert(float tick, Double value)` | `int` |
| Form properties | `replay.properties` | `FormProperties` |
| Get/create form channel | `properties.getOrCreate(form, "transform")` | `KeyframeChannel<Transform>` |
| Insert transform keyframe | `channel.insert(float tick, Transform value)` | `int` |
| Save film | `films.save("name", film.toData() instanceof MapType mt ? mt : new MapType())` | `boolean` |

## License

LGPL-3.0-or-later.
=======
# AeroBBS
A BBS CML addon that allows BBS recordings of Sable sublevels
>>>>>>> 39b57b088a61a5bdcab757e309f273dee52204db
