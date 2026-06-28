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
    /** Capture Aeronautics/Sable physics rope polylines per tick into a film side-file. */
    public boolean captureRopes = true;
    /**
     * Capture Create propeller-BEARING blade contraptions (the big propellers whose
     * blades are an assembled, spinning Create contraption rather than a single block).
     * Their blades are not part of the ship's block grid and are not turned into a Sable
     * sub-ship, so the normal ship snapshot misses them. When true, each propeller
     * bearing's blade contraption is captured as its own StructureForm replay and
     * keyframed to spin (and tilt) about the bearing axis, riding the same render path
     * as ships. Recording-side; re-record to apply. Fully guarded - a failure here never
     * affects ship/rope capture. Small single-block propellers are unaffected (they
     * render via the kinetic BER path).
     */
    public boolean captureContraptions = true;
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

    /**
     * EXPERIMENTAL (client, default off). When true, a non-Mixin world-render hook
     * draws each recorded ship replay during BBS film playback. Currently it draws
     * an alignment marker (oriented box + axes) to prove the hook tracks the ship;
     * it is the foundation for dynamic contraption/rope rendering. See
     * docs/RENDER_HOOK_INVESTIGATION.md. Safe to leave off - it only adds rendering
     * during playback and never touches recording.
     */
    public boolean experimentalDynamicRender = false;

    /** When the dynamic render hook is on, also draw the debug alignment marker (mast + axes). */
    public boolean showDebugMarker = true;

    /**
     * EXPERIMENTAL (client, default off). Render recorded Create kinetic block
     * entities - spinning shafts/cogs and Aeronautics propellers - during BBS film
     * playback. Each kinetic BE is reconstructed from the snapshot nbt and drawn
     * through its real renderer in a throwaway virtual world, so Create's kinetic
     * BlockEntityRenderer actually runs and animates from the recorded speed. Enables
     * the render hook on its own (independent of experimentalDynamicRender). Heavy /
     * still experimental - safe to leave off. See docs/RENDER_HOOK_INVESTIGATION.md.
     */
    public boolean experimentalKineticRender = false;

    /**
     * Multiplier for propeller blade spin at playback. Blades are advanced from their
     * recorded rotationSpeed; the units are approximate, so tune this if blades spin
     * too fast/slow (1.0 = as recorded, 0 = blades held). Does not affect shafts/cogs,
     * which spin from their own recorded kinetic speed.
     */
    public double propellerSpeedScale = 1.0;

    /**
     * Align the recorded structure to BBS's placement. BBS renders a StructureForm
     * with its horizontal-center and bottom at the keyframe position, but the ship's
     * recorded position is its physics origin (center of mass), so the replay sits
     * slightly off. When true, the recorder instead writes the world position of the
     * structure's center-bottom anchor (correct under any rotation). Set false to get
     * the old behavior (record the raw physics origin).
     */
    public boolean alignStructureToCenter = true;

    /**
     * Extra fine-tuning offset (in the ship's LOCAL frame, blocks) added to the
     * structure anchor, for zeroing out any residual misalignment (parity / snapshot
     * margin). Usually 0.
     */
    public double renderOffsetX = 0.0;
    public double renderOffsetY = 0.0;
    public double renderOffsetZ = 0.0;

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

    /**
     * Re-read the config file into the singleton WITHOUT rewriting it. Used by the
     * client render hook so editing {@code experimentalDynamicRender} takes effect
     * without restarting the game. No-op (keeps current values) if the file is
     * missing or unreadable.
     */
    public static void reloadFromDisk() {
        try {
            Path configFile = FabricLoader.getInstance().getConfigDir().resolve("aeronauticscml.json");
            if (!Files.exists(configFile)) {
                return;
            }
            try (Reader reader = Files.newBufferedReader(configFile)) {
                BridgeConfig loaded = GSON.fromJson(reader, BridgeConfig.class);
                if (loaded != null) {
                    if (loaded.shipWhitelist == null) loaded.shipWhitelist = new ArrayList<>();
                    INSTANCE = loaded;
                }
            }
        } catch (Exception ignored) {
            // keep whatever we had
        }
    }

    public List<String> shipWhitelist() {
        List<String> out = new ArrayList<>(shipWhitelist.size());
        for (Object o : shipWhitelist) out.add(String.valueOf(o).trim().toLowerCase());
        return out;
    }

    public int recordIntervalTicks() { return recordIntervalTicks; }
    public int rotationSubdivisions() { return rotationSubdivisions; }
    public boolean experimentalDynamicRender() { return experimentalDynamicRender; }
    public boolean showDebugMarker() { return showDebugMarker; }
    public boolean experimentalKineticRender() { return experimentalKineticRender; }
    public double propellerSpeedScale() { return propellerSpeedScale; }
    public boolean alignStructureToCenter() { return alignStructureToCenter; }
    public double renderOffsetX() { return renderOffsetX; }
    public double renderOffsetY() { return renderOffsetY; }
    public double renderOffsetZ() { return renderOffsetZ; }
    public boolean captureVelocity() { return captureVelocity; }
    public boolean captureAABB() { return captureAABB; }
    public boolean captureRopes() { return captureRopes; }
    public boolean captureContraptions() { return captureContraptions; }
    public String fallbackOutputDir() { return fallbackOutputDir; }
}
