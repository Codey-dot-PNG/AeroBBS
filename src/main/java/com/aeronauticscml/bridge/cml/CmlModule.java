package com.aeronauticscml.bridge.cml;

import com.aeronauticscml.bridge.api.ICmlRecorder;
import com.aeronauticscml.bridge.mod.AeronauticsCmlBridge;

/**
 * Discovers BBS CML Edition at mod-init time and produces an {@link ICmlRecorder}
 * bound to its real API.
 *
 * <p><b>Loader compatibility:</b> BBS CML is a Fabric mod. For it to coexist
 * with SPP (NeoForge) on the same server, the server must run NeoForge with
 * Sinytra Connector installed. This probe checks for the presence of
 * {@code mchorse.bbs_mod.BBSMod} (the CML entrypoint) and, if found,
 * instantiates {@link RealCmlRecorder}.</p>
 *
 * <p>When CML is absent, we fall back to {@link DefaultFileCmlRecorder} so
 * ship poses still land on disk as JSON for offline replay or post-processing.</p>
 */
public final class CmlModule {
    private final ICmlRecorder recorder;
    private final boolean cmlPresent;

    private CmlModule(ICmlRecorder recorder, boolean cmlPresent) {
        this.recorder = recorder;
        this.cmlPresent = cmlPresent;
    }

    public static CmlModule probe() {
        try {
            Class.forName("mchorse.bbs_mod.BBSMod",
                    false,
                    CmlModule.class.getClassLoader());
            Class.forName("mchorse.bbs_mod.film.replays.Replay",
                    false,
                    CmlModule.class.getClassLoader());
            RealCmlRecorder.validateRuntimeApi();
            AeronauticsCmlBridge.LOGGER.info("[aeronauticscml] BBS CML found. Using real Replay API.");
            return new CmlModule(new RealCmlRecorder(), true);
        } catch (ClassNotFoundException e) {
            AeronauticsCmlBridge.LOGGER.warn("[aeronauticscml] BBS CML not found on classpath. " +
                    "Falling back to file-based JSON recorder. Install BBS CML (with Sinytra Connector " +
                    "if running on NeoForge) to enable direct CML recording.");
            return new CmlModule(new DefaultFileCmlRecorder(), false);
        } catch (Throwable t) {
            AeronauticsCmlBridge.LOGGER.warn("[aeronauticscml] BBS CML probe failed: {}. Falling back to file recorder.",
                    t.toString());
            return new CmlModule(new DefaultFileCmlRecorder(), false);
        }
    }

    public boolean isAvailable() {
        return cmlPresent;
    }

    public ICmlRecorder recorder() {
        return recorder;
    }
}
