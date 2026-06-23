package com.aeronauticscml.bridge.config;

import com.aeronauticscml.bridge.mod.AeronauticsCmlBridge;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.List;

public final class BridgeConfig {
    public static final ModConfigSpec SERVER_SPEC;
    private static final BridgeConfig SERVER_INSTANCE;

    private final ModConfigSpec.ConfigValue<List<? extends String>> shipWhitelist;
    private final ModConfigSpec.IntValue recordIntervalTicks;
    private final ModConfigSpec.BooleanValue captureVelocity;
    private final ModConfigSpec.BooleanValue captureAABB;
    private final ModConfigSpec.ConfigValue<String> fallbackOutputDir;

    static {
        Pair<BridgeConfig, ModConfigSpec> serverPair = build(new ModConfigSpec.Builder()
                .comment("Aeronautics CML Bridge - server recording settings"));
        SERVER_INSTANCE = serverPair.getLeft();
        SERVER_SPEC = serverPair.getRight();
    }

    private BridgeConfig(ModConfigSpec.Builder b) {
        this.shipWhitelist = b
                .comment("Ship names or UUID prefixes to record. Empty list = record all ships.")
                .defineListAllowEmpty(List.of("shipWhitelist"),
                        ArrayList::new,
                        o -> o instanceof String);
        this.recordIntervalTicks = b
                .comment("Ticks between captured frames. 1 = 20 Hz. 5 = 4 Hz (lighter load).")
                .defineInRange("recordIntervalTicks", 1, 1, 100);
        this.captureVelocity = b
                .comment("Include linear + angular velocity in each frame.")
                .define("captureVelocity", true);
        this.captureAABB = b
                .comment("Include the ship's world-space AABB in each frame.")
                .define("captureAABB", false);
        this.fallbackOutputDir = b
                .comment("When BBS CML is not installed, dump frames to this directory as JSON.")
                .define("fallbackOutputDir", "aeronauticscml_recordings");
    }

    private static Pair<BridgeConfig, ModConfigSpec> build(ModConfigSpec.Builder b) {
        BridgeConfig cfg = new BridgeConfig(b);
        return Pair.of(cfg, b.build());
    }

    public static BridgeConfig load() {
        return SERVER_INSTANCE;
    }

    public List<String> shipWhitelist() {
        List<? extends String> raw = shipWhitelist.get();
        List<String> out = new ArrayList<>(raw.size());
        for (Object o : raw) out.add(String.valueOf(o).trim().toLowerCase());
        return out;
    }

    public int recordIntervalTicks() { return recordIntervalTicks.get(); }
    public boolean captureVelocity() { return captureVelocity.get(); }
    public boolean captureAABB() { return captureAABB.get(); }
    public String fallbackOutputDir() { return fallbackOutputDir.get(); }
}
