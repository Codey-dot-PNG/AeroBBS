package com.aeronauticscml.bridge.cml;

import com.aeronauticscml.bridge.api.ICmlRecorder;
import com.aeronauticscml.bridge.api.ShipPose;
import com.aeronauticscml.bridge.config.BridgeConfig;
import com.aeronauticscml.bridge.mod.AeronauticsCmlBridge;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Default {@link ICmlRecorder} used when BBS CML is not installed.
 *
 * <p>Writes one JSON line per captured frame to a file under the configured
 * output directory. The schema is intentionally simple so external tools
 * (Blender, FFmpeg pipelines, custom CML importers) can consume it without
 * a parser dependency:</p>
 *
 * <pre>
 * {"shipId":"...","shipName":"...","tick":1234,"t":1234567890,
 *  "pos":[x,y,z],"rot":[qx,qy,qz,qw],"vel":[vx,vy,vz],"avel":[ax,ay,az],
 *  "scale":1.0,"aabbMin":[...],"aabbMax":[...]}
 * </pre>
 *
 * <p>One file per recording session, named {@code session-<timestamp>.jsonl}.
 * Files are flushed after each frame so an abrupt server stop still leaves
 * a usable partial recording.</p>
 */
public final class DefaultFileCmlRecorder implements ICmlRecorder {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Object lock = new Object();
    private BufferedWriter writer;
    private Path currentFile;
    private boolean recording;
    private long totalFrames;

    @Override
    public void start(String sessionName) {
        synchronized (lock) {
            if (recording) return;
            String dir = BridgeConfig.load() != null ? BridgeConfig.load().fallbackOutputDir() : "aeronauticscml_recordings";
            Path dirPath = Paths.get(dir);
            try {
                Files.createDirectories(dirPath);
                String safeName = sessionName == null || sessionName.isBlank()
                        ? LocalDateTime.now().format(TS)
                        : sessionName;
                currentFile = dirPath.resolve("session-" + safeName + ".jsonl");
                writer = Files.newBufferedWriter(currentFile, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                recording = true;
                AeronauticsCmlBridge.LOGGER.info("[aeronauticscml] Fallback recorder started. Writing to {}", currentFile);
            } catch (IOException e) {
                AeronauticsCmlBridge.LOGGER.error("[aeronauticscml] Could not open fallback recorder file", e);
                recording = false;
            }
        }
    }

    @Override
    public void recordFrame(ShipPose pose) {
        if (pose == null) return;
        synchronized (lock) {
            if (!recording || writer == null) return;
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("shipId", pose.shipId().toString());
            frame.put("shipName", pose.shipName());
            frame.put("tick", pose.tick());
            frame.put("t", pose.captureNanos());
            frame.put("pos", triple(pose.worldPosition().x, pose.worldPosition().y, pose.worldPosition().z));
            Quaternionf q = pose.worldRotation();
            frame.put("rot", new double[]{q.x, q.y, q.z, q.w});
            float[] rpy = toDegYawPitchRoll(q);
            frame.put("yaw", (double) rpy[0]);
            frame.put("pitch", (double) rpy[1]);
            frame.put("roll", (double) rpy[2]);
            if (pose.worldAabbMin() != null) {
                frame.put("aabbMin", triple(pose.worldAabbMin().x, pose.worldAabbMin().y, pose.worldAabbMin().z));
            }
            if (pose.worldAabbMax() != null) {
                frame.put("aabbMax", triple(pose.worldAabbMax().x, pose.worldAabbMax().y, pose.worldAabbMax().z));
            }
            try {
                writer.write(GSON.toJson(frame));
                writer.newLine();
                writer.flush();
                totalFrames++;
            } catch (IOException e) {
                AeronauticsCmlBridge.LOGGER.warn("[aeronauticscml] Fallback recorder write failed: {}", e.toString());
            }
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            recording = false;
            if (writer != null) {
                try { writer.flush(); writer.close(); }
                catch (IOException e) {
                    AeronauticsCmlBridge.LOGGER.warn("[aeronauticscml] Fallback recorder close failed: {}", e.toString());
                }
                writer = null;
            }
            if (currentFile != null) {
                AeronauticsCmlBridge.LOGGER.info("[aeronauticscml] Fallback recorder stopped. File: {}", currentFile);
                currentFile = null;
            }
        }
    }

    @Override public boolean isRecording() { synchronized (lock) { return recording; } }
    @Override public long totalFramesWritten() { synchronized (lock) { return totalFrames; } }
    @Override public String backendName() { return "file-json/fallback"; }

    private static double[] triple(double a, double b, double c) { return new double[]{a, b, c}; }
    private static double[] triple(float a, float b, float c) { return new double[]{a, b, c}; }

    private static float[] toDegYawPitchRoll(Quaternionf q) {
        org.joml.Vector3f euler = new org.joml.Vector3f();
        new Quaternionf(q).normalize().getEulerAnglesZYX(euler);
        return new float[]{
            (float) Math.toDegrees(euler.y),
            (float) Math.toDegrees(euler.x),
            (float) Math.toDegrees(euler.z)
        };
    }
}
