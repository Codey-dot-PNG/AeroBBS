# Integration notes

## Sable's Physics Pipeline (SPP)

SPP is the physics engine underneath Aeronautics. It exposes per-ship
rigid-body state through a `PhysicsShip` (or similarly-named) object. The
bridge reflects on whichever class `AeronauticsModule.probe()` finds on
the classpath.

### Confirmed method signatures (when Aeronautics is on the classpath)

These are the method names the bridge looks for. If your Aeronautics build
uses different names, add them to the candidate list in
`ReflectiveSppPoseProvider.Adapter.detect()`.

| Field              | Tried method names                                       | Returns          |
|--------------------|----------------------------------------------------------|------------------|
| UUID               | `getUuid`, `getId`, `getShipId`                          | `java.util.UUID` |
| Name               | `getName`, `getShipName`                                 | `String`         |
| Position           | `getPosition`, `getWorldPosition`, `getTransformPosition`| `Vector3d`       |
| Rotation           | `getRotation`, `getOrientation`, `getWorldRotation`      | `Quaternionf`    |
| Linear velocity    | `getLinearVelocity`, `getVelocity`                       | `Vector3d`       |
| Angular velocity   | `getAngularVelocity`, `getOmega`                         | `Vector3f`       |
| Scale              | `getScale`                                               | `float` / `double` |
| AABB min           | `getAabbMin`, `getWorldAabbMin`                          | `Vector3d`       |
| AABB max           | `getAabbMax`, `getWorldAabbMax`                          | `Vector3d`       |
| Level              | `getLevel`, `getWorld`                                   | `Level`          |

### Ship registry

Ships are enumerated via a per-level registry. The bridge looks for:

```
<registryClass>.forLevel(Level) -> Registry
Registry.all() -> List<Ship>
```

Registry class candidates (extend in `Adapter.detect()` if yours differs):

- `dev.sb.aeronautics.ship.ShipRegistry`
- `com.sable.aeronautics.ship.ShipRegistry`
- `org.valkyrienskies.aeronautics.ship.ShipRegistry`

## BBS CML

The bridge expects a static recorder class with these signatures:

```java
public final class CmlRecorder {
    public static void start(String sessionName);
    public static void recordFrame(UUID actorId, String actorName, long tick,
                                   double x, double y, double z,
                                   float qx, float qy, float qz, float qw,
                                   double vx, double vy, double vz,
                                   float ax, float ay, float az,
                                   float scale,
                                   double aabbMinX, double aabbMinY, double aabbMinZ,
                                   double aabbMaxX, double aabbMaxY, double aabbMaxZ);
    public static void stop();
}
```

When the real CML API is published, replace the candidate class names in
`CmlModule.probe()` and adjust the argument list in
`ReflectiveCmlRecorder.recordFrame()` to match.

## Soft-dependency philosophy

The bridge is designed to install cleanly in any combination of
"Aeronautics present / absent" x "CML present / absent". The matrix is:

| Aeronautics | CML | Behaviour                                            |
|-------------|-----|------------------------------------------------------|
| yes         | yes | Real recording into CML (production target)          |
| yes         | no  | Real recording into JSON-on-disk fallback            |
| no          | yes | Loads, but does nothing each tick (no ships to record) |
| no          | no  | Loads, does nothing - useful for development servers  |

This means players can install the mod ahead of either dependency
upgrading to a compatible build, and the server will simply not record
until both are present.
