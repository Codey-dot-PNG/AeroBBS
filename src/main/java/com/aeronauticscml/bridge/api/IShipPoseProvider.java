package com.aeronauticscml.bridge.api;

import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Read-side abstraction over Aeronautics / Sable's Physics Pipeline.
 *
 * <p>Implementations are responsible for enumerating ships currently loaded
 * in a {@link Level} and producing a {@link ShipPose} for each one.</p>
 *
 * <p>The bridge intentionally depends on this interface (and not on Aeronautics
 * classes directly) so that:</p>
 * <ol>
 *   <li>The mod loads cleanly when Aeronautics is not installed (we just
 *       register a no-op provider).</li>
 *   <li>The exact SPP API can change between versions without touching the
 *       recording pipeline - only the implementation of this interface needs
 *       updating.</li>
 * </ol>
 */
public interface IShipPoseProvider {
    /**
     * @return {@code true} if the underlying Aeronautics/SPP runtime is
     *         present and the provider can enumerate ships.
     */
    boolean isAvailable();

    /**
     * Enumerate all ships currently loaded in the given level.
     *
     * @param level the Minecraft level (ServerLevel on dedicated server)
     * @return a list (possibly empty) of opaque ship handles. Never null.
     */
    List<Object> shipsInLevel(Level level);

    /**
     * Build a {@link ShipPose} snapshot from an opaque ship handle returned
     * by {@link #shipsInLevel(Level)}.
     *
     * @param shipHandle the handle (e.g. an Aeronautics {@code Ship} instance)
     * @param tick       the current server tick
     * @param captureVelocity  whether to populate the velocity fields
     * @param captureAABB      whether to populate the AABB fields
     * @return the pose snapshot, or null if the ship was no longer valid
     */
    ShipPose snapshot(Object shipHandle, long tick, boolean captureVelocity, boolean captureAABB);
}
