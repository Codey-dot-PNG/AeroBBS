package com.aeronauticscml.bridge.aeronautics;

import com.aeronauticscml.bridge.api.IShipPoseProvider;
import com.aeronauticscml.bridge.api.ShipPose;
import com.aeronauticscml.bridge.mod.AeronauticsCmlBridge;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.List;

/**
 * Talks to Sable's Physics Pipeline (SPP) directly using its public API.
 *
 * <p>SPP doesn't have a "Ship" concept — its equivalent is a {@code SubLevel}
 * (a sub-world that holds a contraption and is rigged to a rigid body). Each
 * SubLevel has a 3D pose (position + orientation + scale), an AABB, and an
 * associated {@link RigidBodyHandle} that exposes linear/angular velocity.</p>
 *
 * <p>This provider iterates all SubLevels in a {@link ServerLevel} via
 * {@link SubLevelContainer#getContainer(ServerLevel)} and snapshots each one
 * into a {@link ShipPose}. The mapping is:</p>
 *
 * <table>
 *   <tr><th>ShipPose field</th><th>SPP source</th></tr>
 *   <tr><td>shipId</td><td>subLevel.getUniqueId()</td></tr>
 *   <tr><td>shipName</td><td>subLevel.getName() (or "unnamed")</td></tr>
 *   <tr><td>worldPosition</td><td>subLevel.logicalPose().position()</td></tr>
 *   <tr><td>worldRotation</td><td>subLevel.logicalPose().orientation() (as Quaternionf)</td></tr>
 *   <tr><td>scale</td><td>subLevel.logicalPose().scale().x (uniform assumption)</td></tr>
 *   <tr><td>linearVelocity</td><td>RigidBodyHandle.of(subLevel).getLinearVelocity()</td></tr>
 *   <tr><td>angularVelocity</td><td>RigidBodyHandle.of(subLevel).getAngularVelocity()</td></tr>
 *   <tr><td>worldAabbMin/Max</td><td>subLevel.boundingBox().minX()/maxX()/...</td></tr>
 *   <tr><td>level</td><td>subLevel.getLevel()</td></tr>
 * </table>
 *
 * <p><b>Create Aeronautics</b> ships are SubLevels under the hood — Aeronautics
 * builds its contraptions on top of SPP's SubLevel system, so iterating
 * SubLevels captures both "raw" SPP ships and Aeronautics ships.</p>
 */
public final class SppPoseProvider implements IShipPoseProvider {
    private static final Class<?> SERVER_SUB_LEVEL_CLASS = ServerSubLevel.class;

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public List<Object> shipsInLevel(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) {
            // SPP only enumerates SubLevels on the server side. Client
            // callers get an empty list — the bridge records on the server
            // and replays on the client via CML.
            return List.of();
        }
        try {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(serverLevel);
            if (container == null) {
                return List.of();
            }
            List<ServerSubLevel> all = container.getAllSubLevels();
            if (all == null) return List.of();
            List<Object> out = new ArrayList<>(all.size());
            out.addAll(all);
            return out;
        } catch (Throwable t) {
            AeronauticsCmlBridge.LOGGER.debug("[aeronauticscml] shipsInLevel threw for {}: {}", level, t.toString());
            return List.of();
        }
    }

    @Override
    public ShipPose snapshot(Object shipHandle, long tick, boolean captureVelocity, boolean captureAABB) {
        if (!(shipHandle instanceof ServerSubLevel subLevel)) {
            return null;
        }
        try {
            Pose3dc pose = subLevel.logicalPose();
            if (pose == null) return null;

            Vector3dc pos = pose.position();
            Quaterniondc rot = pose.orientation();
            Vector3dc scaleVec = pose.scale();
            float scale = scaleVec != null ? (float) scaleVec.x() : 1.0f;

            Vector3d position = new Vector3d(pos.x(), pos.y(), pos.z());
            // Pose3dc exposes a Quaterniondc; convert to Quaternionf for the
            // ShipPose record (which uses Quaternionf for JOML interop with
            // client-side rendering).
            org.joml.Quaternionf rotation = new org.joml.Quaternionf(
                    (float) rot.x(), (float) rot.y(), (float) rot.z(), (float) rot.w()
            );

            Vector3d linVel = null;
            org.joml.Vector3f angVel = null;
            if (captureVelocity) {
                try {
                    RigidBodyHandle body = RigidBodyHandle.of(subLevel);
                    if (body != null && body.isValid()) {
                        // Use the out-param variants (the no-arg overloads are deprecated).
                        Vector3d lv = body.getLinearVelocity(new Vector3d());
                        if (lv != null) {
                            linVel = new Vector3d(lv.x(), lv.y(), lv.z());
                        }
                        Vector3d av = body.getAngularVelocity(new Vector3d());
                        if (av != null) {
                            angVel = new org.joml.Vector3f((float) av.x(), (float) av.y(), (float) av.z());
                        }
                    }
                } catch (Throwable t) {
                    // Velocities are optional — fall through with nulls.
                    AeronauticsCmlBridge.LOGGER.debug("[aeronauticscml] velocity lookup failed for {}: {}",
                            subLevel.getUniqueId(), t.toString());
                }
            }

            Vector3d aabbMin = null;
            Vector3d aabbMax = null;
            if (captureAABB) {
                BoundingBox3dc bb = subLevel.boundingBox();
                if (bb != null) {
                    aabbMin = new Vector3d(bb.minX(), bb.minY(), bb.minZ());
                    aabbMax = new Vector3d(bb.maxX(), bb.maxY(), bb.maxZ());
                }
            }

            return new ShipPose(
                    subLevel.getUniqueId(),
                    subLevel.getName() != null ? subLevel.getName() : "unnamed",
                    tick,
                    System.nanoTime(),
                    position,
                    rotation,
                    linVel,
                    angVel,
                    scale,
                    aabbMin,
                    aabbMax,
                    subLevel.getLevel()
            );
        } catch (Throwable t) {
            AeronauticsCmlBridge.LOGGER.warn("[aeronauticscml] Failed to snapshot SubLevel: {}", t.toString());
            return null;
        }
    }
}
