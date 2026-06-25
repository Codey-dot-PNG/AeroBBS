package com.aeronauticscml.bridge;

import com.aeronauticscml.bridge.command.BridgeCommands;
import com.aeronauticscml.bridge.mod.AeronauticsCmlBridge;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

public class AeroBBSInitializer implements ModInitializer {
    @Override
    public void onInitialize() {
        AeronauticsCmlBridge.get();

        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                AeronauticsCmlBridge.get().onServerStarting(server));

        ServerLifecycleEvents.SERVER_STOPPING.register(server ->
                AeronauticsCmlBridge.get().onServerStopping(server));

        ServerTickEvents.END_SERVER_TICK.register(server ->
                AeronauticsCmlBridge.get().onServerTick(server));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                BridgeCommands.register(dispatcher));
    }
}
