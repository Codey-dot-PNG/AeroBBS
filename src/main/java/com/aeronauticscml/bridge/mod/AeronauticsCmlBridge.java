package com.aeronauticscml.bridge.mod;

import com.aeronauticscml.bridge.aeronautics.AeronauticsModule;
import com.aeronauticscml.bridge.cml.CmlModule;
import com.aeronauticscml.bridge.config.BridgeConfig;
import com.aeronauticscml.bridge.event.ServerTickHandler;
import com.aeronauticscml.bridge.recording.RecordingService;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

public final class AeronauticsCmlBridge {
    public static final String MOD_ID = "aeronauticscml";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static AeronauticsCmlBridge instance;

    private BridgeConfig config;
    private AeronauticsModule aeronauticsModule;
    private CmlModule cmlModule;
    private RecordingService recordingService;

    private AeronauticsCmlBridge() {}

    public static AeronauticsCmlBridge get() {
        if (instance == null) {
            instance = new AeronauticsCmlBridge();
        }
        return instance;
    }

    public void onServerStarting(MinecraftServer server) {
        if (aeronauticsModule == null) {
            aeronauticsModule = AeronauticsModule.probe();
        }
        if (cmlModule == null) {
            cmlModule = CmlModule.probe();
        }
        BridgeConfig.initialize();
        this.config = BridgeConfig.load();
        this.recordingService = new RecordingService(config, aeronauticsModule, cmlModule);
        LOGGER.info("[aeronauticscml] Recording service initialised. Use /aeronauticscml start to begin recording.");
    }

    public void onServerStopping(MinecraftServer server) {
        if (recordingService != null) {
            recordingService.shutdown();
        }
    }

    public void onServerTick(MinecraftServer server) {
        if (recordingService != null) {
            ServerTickHandler.handle(server, recordingService);
        }
    }

    public BridgeConfig config() { return config; }
    public RecordingService recordingService() { return recordingService; }
    public AeronauticsModule aeronautics() { return aeronauticsModule; }
    public CmlModule cml() { return cmlModule; }
}
