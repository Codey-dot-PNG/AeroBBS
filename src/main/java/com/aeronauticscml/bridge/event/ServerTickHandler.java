package com.aeronauticscml.bridge.event;

import com.aeronauticscml.bridge.recording.RecordingService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

public final class ServerTickHandler {
    private ServerTickHandler() {}

    public static void handle(MinecraftServer server, RecordingService service) {
        if (service == null || server == null) return;
        List<ServerLevel> levels = new ArrayList<>();
        for (ServerLevel lvl : server.getAllLevels()) {
            levels.add(lvl);
        }
        service.onServerTick(levels, server.getTickCount());
    }
}
