package com.aeronauticscml.bridge.recording;

import com.aeronauticscml.bridge.aeronautics.AeronauticsModule;
import com.aeronauticscml.bridge.api.ICmlRecorder;
import com.aeronauticscml.bridge.api.IShipPoseProvider;
import com.aeronauticscml.bridge.api.ShipPose;
import com.aeronauticscml.bridge.cml.CmlModule;
import com.aeronauticscml.bridge.config.BridgeConfig;
import com.aeronauticscml.bridge.mod.AeronauticsCmlBridge;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-tick pump that pulls ship poses from Aeronautics and pushes them into
 * the configured CML recorder.
 *
 * <p>Lifecycle:</p>
 * <ol>
 *   <li>Created on server start and kept idle until {@code /aeronauticscml start}
 *       opens a recorder session.</li>
 *   <li>Each server tick (post-phase), if recording, iterates all
 *       {@link ServerLevel}s and asks the {@link IShipPoseProvider} for
 *       every ship. Each ship that passes the whitelist filter is
 *       snapshot and forwarded to {@link ICmlRecorder#recordFrame(ShipPose)}.</li>
 *   <li>On server stop, flushes the recorder.</li>
 * </ol>
 *
 * <h2>Whitelist semantics</h2>
 * <p>An empty whitelist means "record every ship". A non-empty whitelist is
 * matched case-insensitively against the ship's name, OR as a UUID-prefix
 * match against the ship's UUID. So entries can be either friendly names
 * ("HMS Relentless") or UUID prefixes ("550e8400-e29b-").</p>
 */
public final class RecordingService {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final BridgeConfig config;
    private final IShipPoseProvider provider;
    private final ICmlRecorder recorder;

    private final Map<UUID, Long> framesPerShip = new HashMap<>();
    private long totalFramesThisSession;
    private long sessionStartTick = -1;
    private long lastCapturedTick = -1;

    public RecordingService(BridgeConfig config, AeronauticsModule aero, CmlModule cml) {
        this.config = config;
        this.provider = aero != null ? aero.provider() : null;
        this.recorder = cml != null ? cml.recorder() : null;
    }

    public boolean isReady() {
        return provider != null && recorder != null && provider.isAvailable();
    }

    public synchronized void start() {
        start("auto-" + LocalDateTime.now().format(TS));
    }

    public synchronized void start(String sessionName) {
        if (!isReady()) {
            AeronauticsCmlBridge.LOGGER.warn("[aeronauticscml] Cannot start recording: provider.ready={}, recorder={}",
                    provider != null && provider.isAvailable(),
                    recorder != null ? recorder.backendName() : "null");
            return;
        }
        if (recorder.isRecording()) return;
        String safeSessionName = sessionName == null || sessionName.isBlank()
                ? "auto-" + LocalDateTime.now().format(TS)
                : sessionName;
        recorder.start(safeSessionName);
        sessionStartTick = -1;
        totalFramesThisSession = 0;
        framesPerShip.clear();
        AeronauticsCmlBridge.LOGGER.info("[aeronauticscml] Recording session '{}' started.", safeSessionName);
    }

    public synchronized void stop() {
        if (recorder != null && recorder.isRecording()) {
            recorder.stop();
            AeronauticsCmlBridge.LOGGER.info("[aeronauticscml] Recording session stopped. Frames this session: {}", totalFramesThisSession);
        }
    }

    public synchronized void shutdown() {
        stop();
    }

    public synchronized void onServerTick(Iterable<ServerLevel> levels, long tick) {
        if (!isReady()) return;
        if (recorder == null || !recorder.isRecording()) return;

        if (sessionStartTick < 0) sessionStartTick = tick;
        // Skip ticks to enforce the configured capture cadence.
        int interval = Math.max(1, config.recordIntervalTicks());
        if ((tick - sessionStartTick) % interval != 0) return;

        List<String> whitelist = config.shipWhitelist();
        boolean captureVelocity = config.captureVelocity();
        boolean captureAABB = config.captureAABB();

        int capturedThisTick = 0;
        for (Level level : levels) {
            List<Object> ships;
            try {
                ships = provider.shipsInLevel(level);
            } catch (Throwable t) {
                AeronauticsCmlBridge.LOGGER.debug("[aeronauticscml] shipsInLevel threw for {}: {}", level, t.toString());
                continue;
            }
            for (Object shipHandle : ships) {
                ShipPose pose = provider.snapshot(shipHandle, tick, captureVelocity, captureAABB);
                if (pose == null) continue;
                if (!passesWhitelist(pose, whitelist)) continue;
                recorder.recordFrame(pose);
                framesPerShip.merge(pose.shipId(), 1L, Long::sum);
                totalFramesThisSession++;
                capturedThisTick++;
            }

            // Ropes are level-wide physics objects, not per-ship: capture once per level.
            if (config.captureRopes() && level instanceof ServerLevel serverLevel) {
                try {
                    recorder.recordRopes(serverLevel, tick);
                } catch (Throwable t) {
                    AeronauticsCmlBridge.LOGGER.debug("[aeronauticscml] recordRopes threw for {}: {}", level, t.toString());
                }
            }
        }
        if (capturedThisTick > 0) {
            lastCapturedTick = tick;
            if (tick % 100 == 0) {
                AeronauticsCmlBridge.LOGGER.info("[aeronauticscml] tick {} captured {} frames (session total {})",
                        tick, capturedThisTick, totalFramesThisSession);
            }
        }
    }

    public synchronized RecordingStatus status() {
        return new RecordingStatus(
                recorder != null && recorder.isRecording(),
                framesPerShip.size(),
                totalFramesThisSession,
                recorder != null ? recorder.totalFramesWritten() : 0L,
                lastCapturedTick,
                recorder != null ? recorder.backendName() : "none",
                provider != null && provider.isAvailable()
        );
    }

    private static boolean passesWhitelist(ShipPose pose, List<String> whitelist) {
        if (whitelist == null || whitelist.isEmpty()) return true;
        String name = pose.shipName() == null ? "" : pose.shipName().toLowerCase();
        String uuid = pose.shipId().toString().toLowerCase();
        for (String entry : whitelist) {
            if (entry.isEmpty()) continue;
            if (name.contains(entry)) return true;
            if (uuid.startsWith(entry)) return true;
        }
        return false;
    }

    public record RecordingStatus(
            boolean recording,
            int activeShips,
            long framesThisSession,
            long totalFramesLifetime,
            long lastCapturedTick,
            String recorderBackend,
            boolean aeronauticsAvailable
    ) {}
}
