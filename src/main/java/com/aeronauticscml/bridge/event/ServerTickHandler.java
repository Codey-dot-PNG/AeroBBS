package com.aeronauticscml.bridge.event;

import com.aeronauticscml.bridge.recording.RecordingService;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter from NeoForge's {@link ServerTickEvent.Post} to our
 * {@link RecordingService#onServerTick(Iterable, long)}.
 *
 * <p>Kept separate from the service itself so the service remains testable
 * without NeoForge on the classpath.</p>
 */
public final class ServerTickHandler {
    private ServerTickHandler() {}

    public static void handle(ServerTickEvent.Post event, RecordingService service) {
        if (service == null) return;
        MinecraftServer server = event.getServer();
        if (server == null) return;
        // Iterate over all levels (overworld, nether, end, custom dimensions).
        // Aeronautics ships can live in any dimension.
        List<net.minecraft.server.level.ServerLevel> levels = new ArrayList<>();
        for (net.minecraft.server.level.ServerLevel lvl : server.getAllLevels()) {
            levels.add(lvl);
        }
        service.onServerTick(levels, server.getTickCount());
    }
}
