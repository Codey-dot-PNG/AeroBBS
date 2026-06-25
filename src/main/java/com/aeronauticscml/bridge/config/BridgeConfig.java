package com.aeronauticscml.bridge.config;

import com.aeronauticscml.bridge.mod.AeronauticsCmlBridge;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class BridgeConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static BridgeConfig INSTANCE = new BridgeConfig();

    public List<String> shipWhitelist = new ArrayList<>();
    public int recordIntervalTicks = 1;
    public boolean captureVelocity = true;
    public boolean captureAABB = false;
    public String fallbackOutputDir = "aeronauticscml_recordings";

    /**
     * How many CONST-hold rotation keyframes to emit per recorded tick interval.
     * Rotation is held (never Euler-interpolated) to avoid gimbal lock, so a value
     * of 1 produces stepped rotation at the 20 Hz record rate. Higher values slerp
     * the gap on the recording side and emit that many sub-keyframes per tick, which
     * makes the held rotation step finely enough to look smooth (4 = 80 Hz effective).
     * Clamped to 1..32. Does not affect translation, which is always smooth.
     */
    public int rotationSubdivisions = 4;

    public static void initialize() {
        Path configFile = FabricLoader.getInstance().getConfigDir().resolve("aeronauticscml.json");

        if (Files.exists(configFile)) {
            try (Reader reader = Files.newBufferedReader(configFile)) {
                BridgeConfig loaded = GSON.fromJson(reader, BridgeConfig.class);
                if (loaded != null) {
                    INSTANCE = loaded;
                }
            } catch (Exception e) {
                AeronauticsCmlBridge.LOGGER.warn("[aeronauticscml] Failed to load config, using defaults: {}", e.toString());
            }
        }

        if (INSTANCE.shipWhitelist == null) INSTANCE.shipWhitelist = new ArrayList<>();
        if (INSTANCE.recordIntervalTicks < 1) INSTANCE.recordIntervalTicks = 1;
        if (INSTANCE.rotationSubdivisions < 1) INSTANCE.rotationSubdivisions = 1;
        if (INSTANCE.rotationSubdivisions > 32) INSTANCE.rotationSubdivisions = 32;
        if (INSTANCE.fallbackOutputDir == null || INSTANCE.fallbackOutputDir.isBlank()) {
            INSTANCE.fallbackOutputDir = "aeronauticscml_recordings";
        }

        try {
            Files.createDirectories(configFile.getParent());
            try (Writer writer = Files.newBufferedWriter(configFile)) {
                GSON.toJson(INSTANCE, writer);
            }
        } catch (Exception e) {
            AeronauticsCmlBridge.LOGGER.warn("[aeronauticscml] Failed to save config: {}", e.toString());
        }
    }

    public static BridgeConfig load() {
        return INSTANCE;
    }

    public List<String> shipWhitelist() {
        List<String> out = new ArrayList<>(shipWhitelist.size());
        for (Object o : shipWhitelist) out.add(String.valueOf(o).trim().toLowerCase());
        return out;
    }

    public int recordIntervalTicks() { return recordIntervalTicks; }
    public int rotationSubdivisions() { return rotationSubdivisions; }
    public boolean captureVelocity() { return captureVelocity; }
    public boolean captureAABB() { return captureAABB; }
    public String fallbackOutputDir() { return fallbackOutputDir; }
}
