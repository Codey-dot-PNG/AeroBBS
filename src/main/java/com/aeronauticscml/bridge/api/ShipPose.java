package com.aeronauticscml.bridge.api;

import net.minecraft.world.level.Level;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.UUID;

/**
 * Immutable snapshot of a single ship's pose at a single server tick.
 *
 * <p>The fields deliberately match the concepts BBS CML needs to replay
 * motion in a cinematic editor:</p>
 * <ul>
 *   <li>{@link #shipId} - stable identifier across ticks (UUID or Aeronautics ship name).</li>
 *   <li>{@link #shipName} - human-readable, useful for filter expressions in CML.</li>
 *   <li>{@link #tick} - server tick at which the sample was taken.</li>
 *   <li>{@link #worldPosition} - ship origin in world coordinates (meters).</li>
 *   <li>{@link #worldRotation} - quaternion taking ship-local axes to world axes.</li>
 *   <li>{@link #linearVelocity} - metres per second in world space (or null when not captured).</li>
 *   <li>{@link #angularVelocity} - radians per second, ship-local axes (or null when not captured).</li>
 *   <li>{@link #scale} - ship scale multiplier (1.0 for unscaled SPP ships).</li>
 *   <li>{@link #worldAabbMin/max} - world-space bounding-box corners (or null when not captured).</li>
 * </ul>
 */
public record ShipPose(
        UUID shipId,
        String shipName,
        long tick,
        long captureNanos,
        Vector3d worldPosition,
        Quaternionf worldRotation,
        Vector3d linearVelocity,
        Vector3f angularVelocity,
        float scale,
        Vector3d worldAabbMin,
        Vector3d worldAabbMax,
        Level level
) {
    public ShipPose {
        if (shipId == null) {
            throw new IllegalArgumentException("shipId must not be null");
        }
        if (worldPosition == null) {
            throw new IllegalArgumentException("worldPosition must not be null");
        }
        if (worldRotation == null) {
            throw new IllegalArgumentException("worldRotation must not be null");
        }
    }
}
