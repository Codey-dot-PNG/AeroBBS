package com.aeronauticscml.bridge.aeronautics;

import com.aeronauticscml.bridge.api.IShipPoseProvider;
import com.aeronauticscml.bridge.api.ShipPose;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * No-op {@link IShipPoseProvider} used when Aeronautics is not installed.
 * Returns an empty ship list and never produces a {@link ShipPose}.
 *
 * <p>This keeps the recording pipeline happy on installations that have
 * BBS CML but no Aeronautics - the bridge will start up, register its
 * commands, and politely do nothing each tick.</p>
 */
public final class NoopPoseProvider implements IShipPoseProvider {
    @Override public boolean isAvailable() { return false; }
    @Override public List<Object> shipsInLevel(Level level) { return List.of(); }
    @Override public ShipPose snapshot(Object shipHandle, long tick, boolean captureVelocity, boolean captureAABB) {
        return null;
    }
}
