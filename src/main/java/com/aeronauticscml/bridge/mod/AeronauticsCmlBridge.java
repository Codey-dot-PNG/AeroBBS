package com.aeronauticscml.bridge.mod;

import com.aeronauticscml.bridge.aeronautics.AeronauticsModule;
import com.aeronauticscml.bridge.cml.CmlModule;
import com.aeronauticscml.bridge.command.BridgeCommands;
import com.aeronauticscml.bridge.config.BridgeConfig;
import com.aeronauticscml.bridge.event.ServerTickHandler;
import com.aeronauticscml.bridge.recording.RecordingService;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * Root entrypoint for the Aeronautics-CML bridge mod.
 *
 * <p>Wires together four concerns:</p>
 * <ol>
 *   <li>{@link BridgeConfig} - user-tunable whitelist and recording cadence.</li>
 *   <li>{@link AeronauticsModule} - reflection-safe accessors to Aeronautics / SPP ships.</li>
 *   <li>{@link CmlModule} - reflection-safe accessors to BBS CML's recorder API.</li>
 *   <li>{@link RecordingService} - per-tick pump that pulls ship poses from Aeronautics
 *       and pushes them to CML.</li>
 * </ol>
 *
 * <p>All external integrations are wrapped behind interfaces so that the mod
 * still loads (and does nothing harmful) when one or both dependencies are
 * missing. This makes installation painless on servers where CML is present
 * but Aeronautics is not, or vice versa.</p>
 */
@Mod(AeronauticsCmlBridge.MOD_ID)
public final class AeronauticsCmlBridge {
    public static final String MOD_ID = "aeronauticscml";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static AeronauticsCmlBridge instance;

    private BridgeConfig config;
    private AeronauticsModule aeronauticsModule;
    private CmlModule cmlModule;
    private RecordingService recordingService;

    public AeronauticsCmlBridge(ModContainer container) {
        instance = this;

        // Recording is server-side, including in singleplayer's integrated server.
        container.registerConfig(ModConfig.Type.SERVER, BridgeConfig.SERVER_SPEC);

        // Game events on the NeoForge event bus (formerly @SubscribeEvent).
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
    }

    public static AeronauticsCmlBridge get() {
        return instance;
    }

    private void onServerStarting(ServerStartingEvent event) {
        if (aeronauticsModule == null) {
            aeronauticsModule = AeronauticsModule.probe();
        }
        if (cmlModule == null) {
            cmlModule = CmlModule.probe();
        }
        this.config = BridgeConfig.load();
        this.recordingService = new RecordingService(config, aeronauticsModule, cmlModule);
        LOGGER.info("[aeronauticscml] Recording service initialised. Use /aeronauticscml start to begin recording.");
    }

    private void onServerStopping(ServerStoppingEvent event) {
        if (recordingService != null) {
            recordingService.shutdown();
        }
    }

    private void onServerTick(ServerTickEvent.Post event) {
        if (recordingService != null) {
            ServerTickHandler.handle(event, recordingService);
        }
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        BridgeCommands.register(event.getDispatcher());
    }

    public BridgeConfig config() {
        return config;
    }

    public RecordingService recordingService() {
        return recordingService;
    }

    public AeronauticsModule aeronautics() {
        return aeronauticsModule;
    }

    public CmlModule cml() {
        return cmlModule;
    }
}
