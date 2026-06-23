package com.aeronauticscml.bridge.aeronautics;

import com.aeronauticscml.bridge.api.IShipPoseProvider;
import com.aeronauticscml.bridge.mod.AeronauticsCmlBridge;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.server.level.ServerLevel;

/**
 * Discovers Sable's Physics Pipeline (SPP) at mod-init time and produces an
 * {@link IShipPoseProvider} bound to its real API.
 *
 * <p>Since the bridge now ships with a compile-time dependency on SPP
 * (the JAR is in {@code libs/}), we no longer need reflection. We simply
 * probe for the presence of a known SPP class to confirm the mod is loaded
 * at runtime, then instantiate {@link SppPoseProvider}.</p>
 *
 * <p>If SPP is absent (e.g. on a development server without Aeronautics
 * installed), we fall back to a {@link NoopPoseProvider} so the bridge
 * still loads cleanly and the recording service degrades to a no-op.</p>
 */
public final class AeronauticsModule {
    private final IShipPoseProvider provider;
    private final boolean available;

    private AeronauticsModule(IShipPoseProvider provider, boolean available) {
        this.provider = provider;
        this.available = available;
    }

    public static AeronauticsModule probe() {
        try {
            Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer",
                    false,
                    AeronauticsModule.class.getClassLoader());
            Class.forName("dev.ryanhcode.sable.sublevel.ServerSubLevel",
                    false,
                    AeronauticsModule.class.getClassLoader());
            validateRuntimeApi();
            AeronauticsCmlBridge.LOGGER.info("[aeronauticscml] SPP found. Using direct SubLevel API.");
            return new AeronauticsModule(new SppPoseProvider(), true);
        } catch (ClassNotFoundException e) {
            AeronauticsCmlBridge.LOGGER.warn("[aeronauticscml] SPP not found on classpath. " +
                    "Ship recording will be a no-op. Install Sable (SPP) + Aeronautics to enable.");
            return new AeronauticsModule(new NoopPoseProvider(), false);
        } catch (Throwable t) {
            AeronauticsCmlBridge.LOGGER.warn("[aeronauticscml] SPP probe failed: {}. Recording will be a no-op.",
                    t.toString());
            return new AeronauticsModule(new NoopPoseProvider(), false);
        }
    }

    public boolean isAvailable() {
        return available;
    }

    public IShipPoseProvider provider() {
        return provider;
    }

    private static void validateRuntimeApi() throws ReflectiveOperationException {
        SubLevelContainer.class.getMethod("getContainer", ServerLevel.class);
        ServerSubLevelContainer.class.getMethod("getAllSubLevels");
        ServerSubLevel.class.getMethod("getUniqueId");
        ServerSubLevel.class.getMethod("getName");
        ServerSubLevel.class.getMethod("getLevel");
        ServerSubLevel.class.getMethod("logicalPose");
        ServerSubLevel.class.getMethod("boundingBox");
        ServerSubLevel.class.getMethod("getPlot");
        ServerSubLevel.class.getMethod("updateBoundingBox");
        LevelPlot.class.getMethod("getBoundingBox");
        LevelPlot.class.getMethod("contains", net.minecraft.world.level.ChunkPos.class);
        RigidBodyHandle.class.getMethod("of", ServerSubLevel.class);
    }
}
