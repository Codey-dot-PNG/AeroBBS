# JSON line schema (fallback recorder)

Each line in `session-<timestamp>.jsonl` is a single JSON object representing
one captured ship at one server tick.

## Fields

| Field     | Type           | Always present? | Notes                                              |
|-----------|----------------|-----------------|----------------------------------------------------|
| shipId    | string (UUID)  | yes             | stable across ticks                                |
| shipName  | string         | yes             | may be `"unnamed"`                                 |
| tick      | integer        | yes             | server tick                                        |
| t         | integer (nanos)| yes             | `System.nanoTime()` at capture - monotonic, useful for delta-t |
| pos       | [x,y,z] doubles| yes             | ship origin in world coords (metres)               |
| rot       | [x,y,z,w]      | yes             | quaternion, ship-local -> world                    |
| vel       | [x,y,z] doubles| if `captureVelocity` | linear velocity, m/s in world frame           |
| avel      | [x,y,z] doubles| if `captureVelocity` | angular velocity, rad/s in ship-local frame   |
| scale     | number         | yes             | 1.0 for unscaled SPP ships                         |
| aabbMin   | [x,y,z] doubles| if `captureAABB`     | world-space AABB min corner                   |
| aabbMax   | [x,y,z] doubles| if `captureAABB`     | world-space AABB max corner                   |

## Example line

```json
{"shipId":"550e8400-e29b-41d4-a716-446655440000","shipName":"HMS Relentless","tick":18342,"t":8372946150213,"pos":[124.5,71.0,-318.2],"rot":[0.0,0.7071,0.0,0.7071],"vel":[3.1,0.0,-1.2],"avel":[0.0,0.05,0.0],"scale":1.0}
```

## Converting to BBS CML sessions offline

The fallback JSONL file is the same shape BBS CML consumes via its file
importer (adapt the wrapper in `ReflectiveCmlRecorder` if your CML build
expects a slightly different argument list).
