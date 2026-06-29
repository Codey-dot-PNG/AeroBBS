package com.aeronauticscml.bridge.cml;

import com.aeronauticscml.bridge.aeronautics.StructureSnapshotService;
import com.aeronauticscml.bridge.api.ICmlRecorder;
import com.aeronauticscml.bridge.api.ShipPose;
import com.aeronauticscml.bridge.config.BridgeConfig;
import com.aeronauticscml.bridge.mod.AeronauticsCmlBridge;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.FilmManager;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.film.replays.Replays;
import mchorse.bbs_mod.forms.FormArchitect;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.StructureForm;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.pose.Transform;

import com.google.gson.Gson;
import dev.ryanhcode.sable.api.physics.object.ArbitraryPhysicsObject;
import dev.ryanhcode.sable.api.physics.object.rope.RopePhysicsObject;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;

import java.io.File;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Real ICmlRecorder backed by BBS CML Edition.
 *
 * For each ship:
 *   1. Snapshot blocks to .nbt (via StructureSnapshotService)
 *   2. Create a StructureForm pointing at the .nbt file (using "world:" prefix)
 *   3. Assign the form to replay.form
 *   4. Each tick, write actor position/rotation keyframes to the replay.
 *
 * <h2>Rotation: why we use CONST-hold keyframes instead of Euler lerp</h2>
 *
 * BBS CML interpolates the {@code "transform"} property channel at fractional
 * render ticks ({@code FormProperties.applyProperties(form, float tick)} -&gt;
 * {@code KeyframeChannel.find(float)} -&gt; {@code Transform.lerp(...)}). Storing a
 * ZYX-Euler decomposition of the ship quaternion and letting BBS lerp it
 * <em>linearly</em> across the +/-90 deg pitch singularity is exactly what
 * produces gimbal-lock artifacts (pitch reflects, yaw/roll flip 180 deg).
 *
 * The render path turns {@code Transform.rotate} back into a matrix as
 * {@code Rz(rotate.z) * Ry(rotate.y) * Rx(rotate.x)} (see
 * {@code MatrixStackUtils.applyTransform}). That reconstruction is exact for the
 * stored angles regardless of how far past 90 deg the ship is pitched - the
 * <em>only</em> thing that breaks is interpolation <em>between</em> keyframes.
 *
 * So we mark every rotation keyframe {@link Interpolations#CONST} ("hold left
 * value across the whole segment", as BBS itself uses in
 * {@code KeyframeChannel.insertSpace}). The renderer then reproduces the exact
 * recorded orientation at each tick with no cross-keyframe blending - no gimbal
 * lock, ever, and no Mixin on a BBS class (which crashes BBS CML init under
 * Sinytra Connector).
 *
 * Position stays on the separate {@code replay.keyframes.x/y/z} channels with
 * normal linear interpolation, so translation remains perfectly smooth.
 *
 * To recover smooth-looking <em>rotation</em> (CONST hold alone steps at the
 * 20 Hz record rate), we optionally subdivide each tick interval with quaternion
 * <em>slerp</em> at record time and emit several CONST sub-keyframes. Each
 * sub-keyframe is an exact orientation, so there is still never any interpolation
 * across the Euler discontinuity - the smoothing is done with real spherical
 * interpolation on the recording side, where we have the quaternions, instead of
 * at render time (where it would need a Mixin). Controlled by
 * {@link BridgeConfig#rotationSubdivisions()} (1 = pure per-tick hold).
 */
public final class RealCmlRecorder implements ICmlRecorder {
    private static final String BRIDGE_FILM_NAME = "aeronautics-bridge";

    private final Object lock = new Object();
    private final Map<UUID, Replay> replayPerShip = new HashMap<>();
    private final Map<UUID, StructureForm> formPerShip = new HashMap<>();
    private final Map<UUID, Path> structureFilePerShip = new HashMap<>();
    /** Previous tick's world rotation per ship, used to slerp sub-keyframes. */
    private final Map<UUID, Quaternionf> lastRotationPerShip = new HashMap<>();
    /** Relative tick of the last rotation keyframe written per ship. */
    private final Map<UUID, Float> lastRotationTickPerShip = new HashMap<>();
    /** Body-frame offset from the ship physics origin to the structure center-bottom anchor. */
    private final Map<UUID, Vector3d> centerOffsetPerShip = new HashMap<>();
    /** Cached ServerSubLevel per ship (for per-tick contraption capture without re-scanning). */
    private final Map<UUID, ServerSubLevel> subLevelPerShip = new HashMap<>();
    /** Propeller-bearing block positions per ship, scanned once on first capture. */
    private final Map<UUID, List<BlockPos>> bearingPositionsPerShip = new HashMap<>();
    /** Per blade-contraption (keyed by its entity uuid): its replay/form + local anchor. */
    private final Map<UUID, ContraptionRec> contraptionRecs = new HashMap<>();
    /** Block-update generations: last content hash, generation count, and last check tick per ship. */
    private final Map<UUID, Long> genHashPerShip = new HashMap<>();
    private final Map<UUID, Integer> genCountPerShip = new HashMap<>();
    private final Map<UUID, Long> lastGenCheckPerShip = new HashMap<>();
    private final StructureSnapshotService snapshotService = new StructureSnapshotService();

    /** A captured propeller blade contraption: its BBS replay/form and center-bottom anchor (contraption-local frame). */
    private static final class ContraptionRec {
        final Replay replay;
        final StructureForm form;
        final double anchorX, anchorY, anchorZ;
        ContraptionRec(Replay replay, StructureForm form, double anchorX, double anchorY, double anchorZ) {
            this.replay = replay; this.form = form;
            this.anchorX = anchorX; this.anchorY = anchorY; this.anchorZ = anchorZ;
        }
    }

    // Reflective handles for Create/Aeronautics types (jarjar'd / not on the compile
    // classpath). Resolved lazily from the live runtime classes and cached.
    private static volatile boolean propBearingClassResolved;
    private static volatile Class<?> propBearingClassCached;
    private static Method mGetMovedContraption;
    private static Method mGetContraption, mGetBlocks, mGetAngle, mGetRotationAxis;
    private static Field fTiltQuat, fDirection;

    private static final Gson GSON = new Gson();
    /** Captured rope polylines per relative tick (written to a film side-file on stop). */
    /** Rope side-files, one per ship uuid (so the BBS editor can load by structure uuid). */
    private final Map<UUID, RopeFile> ropeFilesPerShip = new HashMap<>();
    private long ropeFrames;
    /** This tick's center-bottom anchor pose per ship [x,y,z,qx,qy,qz,qw]; set in recordFrame, read in recordRopes. */
    private final Map<UUID, double[]> anchorPoseThisTick = new HashMap<>();
    /** First ship recorded this session - fallback owner for ropes we can't attribute. */
    private UUID primaryShipId;
    private static java.lang.reflect.Field ropeSubLevelField;

    /**
     * Serialized side-file form, one file per ship uuid: relativeTick -> ropes (absolute
     * world points) AND relativeTick -> that ship's center-bottom anchor pose
     * [x,y,z,qx,qy,qz,qw] at that tick. In-world playback draws the absolute points; the
     * BBS editor uses the pose to transform them into the ship form's local space.
     */
    static final class RopeFile {
        Map<String, List<RopeSnap>> ticks = new LinkedHashMap<>();
        Map<String, double[]> poses = new LinkedHashMap<>();
    }
    static final class RopeSnap {
        double radius;
        double[] points;
    }

    private Film film;
    private String currentFilmName;
    private boolean recording;
    private long totalFrames;
    private long firstTick = Long.MIN_VALUE;
    private int rotationSubdivisions = 1;
    private boolean alignStructureToCenter = true;
    private boolean captureContraptions = true;
    private boolean captureBlockUpdates;
    private int blockUpdateIntervalTicks = 10;
    private double renderOffsetX, renderOffsetY, renderOffsetZ;
    private int debugCounter;

    @Override
    public void start(String sessionName) {
        synchronized (lock) {
            if (recording) return;
            try {
                FilmManager films = BBSMod.getFilms();
                if (films == null) {
                    AeronauticsCmlBridge.LOGGER.warn("[aeronauticscml] BBSMod.getFilms() returned null.");
                    return;
                }
                File worldFolder = BBSMod.getWorldFolder();
                if (worldFolder == null) {
                    AeronauticsCmlBridge.LOGGER.warn("[aeronauticscml] BBS world folder is not ready yet; recording was not started.");
                    return;
                }
                currentFilmName = filmName(sessionName);
                film = films.load(currentFilmName);
                if (film == null) {
                    AeronauticsCmlBridge.LOGGER.info("[aeronauticscml] Creating new CML film '{}'", currentFilmName);
                    film = films.create(currentFilmName, null);
                }
                replayPerShip.clear();
                formPerShip.clear();
                structureFilePerShip.clear();
                lastRotationPerShip.clear();
                lastRotationTickPerShip.clear();
                centerOffsetPerShip.clear();
                subLevelPerShip.clear();
                bearingPositionsPerShip.clear();
                contraptionRecs.clear();
                genHashPerShip.clear();
                genCountPerShip.clear();
                lastGenCheckPerShip.clear();
                ropeFilesPerShip.clear();
                anchorPoseThisTick.clear();
                primaryShipId = null;
                ropeFrames = 0;
                debugCounter = 0;
                firstTick = Long.MIN_VALUE;
                BridgeConfig cfg = BridgeConfig.load();
                rotationSubdivisions = clampSubdivisions(cfg.rotationSubdivisions());
                alignStructureToCenter = cfg.alignStructureToCenter();
                captureContraptions = cfg.captureContraptions();
                captureBlockUpdates = cfg.captureBlockUpdates();
                blockUpdateIntervalTicks = cfg.blockUpdateIntervalTicks();
                renderOffsetX = cfg.renderOffsetX();
                renderOffsetY = cfg.renderOffsetY();
                renderOffsetZ = cfg.renderOffsetZ();
                recording = true;
                AeronauticsCmlBridge.LOGGER.info("[aeronauticscml] CML recording session started. Film='{}', rotationSubdivisions={}",
                        currentFilmName, rotationSubdivisions);
            } catch (Throwable t) {
                AeronauticsCmlBridge.LOGGER.error("[aeronauticscml] Failed to start CML recording", t);
                recording = false;
                currentFilmName = null;
            }
        }
    }

    @Override
    public void recordFrame(ShipPose pose) {
        if (pose == null) return;
        synchronized (lock) {
            if (!recording || film == null) return;
            try {
                if (firstTick == Long.MIN_VALUE) firstTick = pose.tick();
                long relativeTick = pose.tick() - firstTick;

                Replay replay = replayPerShip.get(pose.shipId());
                if (replay == null) {
                    StructureForm form = snapshotAndCreateForm(pose);

                    Replays replays = film.replays;
                    replay = replays.addReplay();
                    replay.label.set(pose.shipName());
                    replay.uuid.set(pose.shipId().toString());
                    if (form != null) {
                        replay.form.set(form);
                        formPerShip.put(pose.shipId(), form);
                    }
                    replayPerShip.put(pose.shipId(), replay);
                    AeronauticsCmlBridge.LOGGER.info("[aeronauticscml] Allocated CML Replay for ship {} ({}) with form {}",
                            pose.shipName(), pose.shipId(),
                            form != null ? "StructureForm" : "(none)");
                }

                // Position (entity-level, works for StructureForm) - linear interpolation is
                // correct for translation, so we keep one keyframe per tick here. We record
                // the world position of the structure's center-bottom anchor (what BBS places
                // at the form origin), not the raw physics origin, so the replay lines up.
                float tickF = (float) relativeTick;

                // Block updates: if the ship's blocks changed, roll to a new generation form
                // (old one keyframed hidden from here, new one shown). Must run before this
                // tick's keyframes so they are written to the new generation. No-op unless
                // captureBlockUpdates is on.
                if (captureBlockUpdates) {
                    Replay rolled = maybeRollGeneration(pose, tickF, relativeTick);
                    if (rolled != null) replay = rolled;
                }

                Vec3 worldPos = computeAnchorWorldPosition(pose);
                replay.keyframes.x.insert(tickF, worldPos.x);
                replay.keyframes.y.insert(tickF, worldPos.y);
                replay.keyframes.z.insert(tickF, worldPos.z);

                // Work on a copy so we never mutate the (documented-immutable) pose quaternion.
                Quaternionf q = new Quaternionf(pose.worldRotation()).normalize();

                // Stash this tick's center-bottom anchor pose so recordRopes can attach it to
                // the ship's ropes (lets the BBS editor transform them into form-local space).
                anchorPoseThisTick.put(pose.shipId(),
                        new double[]{worldPos.x, worldPos.y, worldPos.z, q.x, q.y, q.z, q.w});
                if (primaryShipId == null) primaryShipId = pose.shipId();

                StructureForm form = formPerShip.get(pose.shipId());
                if (form != null) {
                    KeyframeChannel transformChannel = replay.properties.getOrCreate(form, "transform");
                    writeRotationKeyframes(pose.shipId(), transformChannel, tickF, q);
                }

                // Propeller-bearing blade contraptions are not part of the ship's block grid
                // (Create moves them into a separate contraption entity) and are not turned
                // into a Sable sub-ship, so the ship snapshot misses them. Capture each as its
                // own spinning StructureForm replay. Fully guarded - never breaks ship capture.
                if (captureContraptions) {
                    ServerSubLevel sl = findSubLevel(pose);
                    if (sl != null) recordContraptions(pose, sl, tickF);
                }

                if (debugCounter++ % 100 == 0) {
                    AeronauticsCmlBridge.LOGGER.info("[aeronauticscml] ship={} tick={} q=({},{},{},{}) subdiv={}",
                            pose.shipName(), pose.tick(),
                            q.x, q.y, q.z, q.w, rotationSubdivisions);
                }

                totalFrames++;
            } catch (Throwable t) {
                AeronauticsCmlBridge.LOGGER.error("[aeronauticscml] CML recordFrame failed for ship {}: {}", pose.shipId(), t.toString(), t);
            }
        }
    }

    @Override
    public void recordRopes(ServerLevel level, long tick) {
        synchronized (lock) {
            if (!recording || level == null) return;
            if (firstTick == Long.MIN_VALUE) firstTick = tick;
            long relativeTick = tick - firstTick;
            try {
                SubLevelPhysicsSystem sys = SubLevelPhysicsSystem.get(level);
                if (sys == null) return;

                String tickKey = Long.toString(relativeTick);
                boolean any = false;
                for (ArbitraryPhysicsObject o : sys.getArbitraryObjects()) {
                    if (!(o instanceof RopePhysicsObject rope)) continue;
                    var pts = rope.getPoints();
                    if (pts == null || pts.size() < 2) continue;

                    UUID owner = ropeOwnerUuid(rope);
                    if (owner == null) owner = primaryShipId;
                    if (owner == null) continue; // no ship to anchor this rope to

                    double[] arr = new double[pts.size() * 3];
                    int i = 0;
                    for (org.joml.Vector3d p : pts) {
                        arr[i++] = p.x;
                        arr[i++] = p.y;
                        arr[i++] = p.z;
                    }
                    RopeSnap snap = new RopeSnap();
                    snap.radius = rope.getCollisionRadius();
                    snap.points = arr;

                    RopeFile rf = ropeFilesPerShip.computeIfAbsent(owner, k -> new RopeFile());
                    rf.ticks.computeIfAbsent(tickKey, k -> new ArrayList<>()).add(snap);
                    // Compute the owner ship's anchor pose LIVE for this tick (ropes can be
                    // sampled on ticks where recordFrame didn't run); fall back to the last
                    // frame's stashed pose.
                    double[] pose = computeAnchorPoseNow(owner);
                    if (pose == null) pose = anchorPoseThisTick.get(owner);
                    if (pose != null) rf.poses.put(tickKey, pose);
                    any = true;
                }
                if (any) ropeFrames++;
            } catch (Throwable t) {
                if (debugCounter % 200 == 0) {
                    AeronauticsCmlBridge.LOGGER.debug("[aeronauticscml] rope capture failed: {}", t.toString());
                }
            }
        }
    }

    private void writeRopeFiles() {
        if (ropeFilesPerShip.isEmpty()) return;
        try {
            File worldFolder = BBSMod.getWorldFolder();
            if (worldFolder == null) return;
            Path dir = worldFolder.toPath().resolve("aeronauticscml").resolve("ropes");
            Files.createDirectories(dir);
            for (Map.Entry<UUID, RopeFile> e : ropeFilesPerShip.entrySet()) {
                RopeFile rf = e.getValue();
                if (rf.ticks.isEmpty()) continue;
                Path out = dir.resolve(e.getKey().toString() + ".json");
                try (Writer w = Files.newBufferedWriter(out)) {
                    GSON.toJson(rf, w);
                }
                AeronauticsCmlBridge.LOGGER.info("[aeronauticscml] Wrote rope side-file {} ({} ticks)", out, rf.ticks.size());
            }
        } catch (Throwable t) {
            AeronauticsCmlBridge.LOGGER.warn("[aeronauticscml] Failed to write rope side-files: {}", t.toString());
        }
    }

    /** The ship a rope is attached to (reflective: Sable's startAttachmentSubLevel), or null. */
    private static UUID ropeOwnerUuid(RopePhysicsObject rope) {
        try {
            if (ropeSubLevelField == null) {
                ropeSubLevelField = RopePhysicsObject.class.getDeclaredField("startAttachmentSubLevel");
                ropeSubLevelField.setAccessible(true);
            }
            Object sub = ropeSubLevelField.get(rope);
            if (sub instanceof ServerSubLevel ssl) {
                return ssl.getUniqueId();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Write CONST-hold rotation keyframe(s) for this tick. When
     * {@code rotationSubdivisions > 1} and we have a previous orientation one
     * small step back, we slerp the gap and emit intermediate CONST keyframes so
     * the held rotation steps finely (and therefore looks smooth) without any
     * Euler interpolation across the gimbal singularity.
     */
    private void writeRotationKeyframes(UUID shipId, KeyframeChannel channel, float tick, Quaternionf current) {
        Quaternionf previous = lastRotationPerShip.get(shipId);
        Float prevTickObj = lastRotationTickPerShip.get(shipId);

        if (rotationSubdivisions > 1 && previous != null && prevTickObj != null) {
            float prevTick = prevTickObj;
            float span = tick - prevTick;

            // Only densify across a normal, small forward gap. Large/zero/negative
            // gaps (ship paused, re-detected, clock weirdness) fall back to a single
            // exact keyframe at this tick.
            if (span > 0F && span <= 5F) {
                // Take the shortest great-circle path: flip the target into the
                // same hemisphere as the source if their dot product is negative.
                Quaternionf target = new Quaternionf(current);
                if (previous.dot(target) < 0F) {
                    target.set(-target.x, -target.y, -target.z, -target.w);
                }

                for (int s = 1; s <= rotationSubdivisions; s++) {
                    float f = (float) s / (float) rotationSubdivisions;
                    float subTick = prevTick + f * span;
                    Quaternionf interpolated = new Quaternionf(previous).slerp(target, f).normalize();
                    insertConstRotation(channel, subTick, interpolated);
                }

                lastRotationPerShip.put(shipId, new Quaternionf(current));
                lastRotationTickPerShip.put(shipId, tick);
                return;
            }
        }

        // First sample for this ship, or a gap we won't subdivide: one exact keyframe.
        insertConstRotation(channel, tick, current);
        lastRotationPerShip.put(shipId, new Quaternionf(current));
        lastRotationTickPerShip.put(shipId, tick);
    }

    /**
     * World position to record for this tick. When alignment is on and we have a
     * center-bottom offset for the ship, return the world position of that anchor
     * (physics origin + worldRotation * (D + residual offset)); otherwise the raw
     * physics origin. This makes the StructureForm sit where the ship actually is.
     */
    private Vec3 computeAnchorWorldPosition(ShipPose pose) {
        Vector3dc p = pose.worldPosition();
        Vector3d d = centerOffsetPerShip.get(pose.shipId());

        if (!alignStructureToCenter || d == null) {
            return new Vec3(p.x(), p.y(), p.z());
        }

        Vector3d local = new Vector3d(d.x + renderOffsetX, d.y + renderOffsetY, d.z + renderOffsetZ);
        Quaternionf wr = pose.worldRotation();
        Quaterniond q = new Quaterniond(wr.x, wr.y, wr.z, wr.w).normalize();
        q.transform(local); // body-frame -> world-frame
        return new Vec3(p.x() + local.x, p.y() + local.y, p.z() + local.z);
    }

    /**
     * Compute a ship's center-bottom anchor world pose {@code [x,y,z, qx,qy,qz,qw]} from its
     * LIVE sublevel pose + cached center offset, for rope ticks that don't coincide with a
     * recorded frame. Returns null if the ship's sublevel/offset isn't known yet (then the
     * caller falls back to the last frame's pose).
     */
    private double[] computeAnchorPoseNow(UUID owner) {
        try {
            ServerSubLevel sl = subLevelPerShip.get(owner);
            if (sl == null) return null;
            Pose3dc lp = sl.logicalPose();
            if (lp == null) return null;
            Vector3dc p = lp.position();
            Quaterniondc r = lp.orientation();
            double wx = p.x(), wy = p.y(), wz = p.z();
            Vector3d d = centerOffsetPerShip.get(owner);
            if (alignStructureToCenter && d != null) {
                Vector3d local = new Vector3d(d.x + renderOffsetX, d.y + renderOffsetY, d.z + renderOffsetZ);
                new Quaterniond(r.x(), r.y(), r.z(), r.w()).normalize().transform(local);
                wx += local.x; wy += local.y; wz += local.z;
            }
            return new double[]{wx, wy, wz, r.x(), r.y(), r.z(), r.w()};
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Insert a single rotation keyframe at {@code tick} that holds the exact
     * orientation {@code q}, and mark it CONST so BBS CML does not blend it with
     * its neighbours.
     *
     * <p>{@code MatrixStackUtils.applyTransform} rebuilds the matrix as
     * {@code Rz(rotate.z)*Ry(rotate.y)*Rx(rotate.x)}, so we store the ZYX
     * (Tait-Bryan) angles that invert exactly that product - see
     * {@link #toBbsEulerZYX}. We deliberately do <em>not</em> use JOML's
     * {@code Quaternionf.getEulerAnglesZYX}: in JOML 1.10.5 its result does not
     * round-trip through {@code Rz*Ry*Rx} past 90 deg or for mixed-axis rotations,
     * which is itself a source of the "wrong past 90 deg" symptom.</p>
     */
    private static void insertConstRotation(KeyframeChannel channel, float tick, Quaternionf q) {
        Transform t = new Transform();
        toBbsEulerZYX(q, t.rotate);

        int index = channel.insert(tick, t);
        Keyframe kf = channel.get(index);
        if (kf != null) {
            kf.getInterpolation().setInterp(Interpolations.CONST);
        }
    }

    /**
     * Decompose a unit quaternion into the ZYX Euler angles (radians) that satisfy
     * {@code Rz(out.z) * Ry(out.y) * Rx(out.x) == q} - i.e. exactly what BBS CML's
     * {@code MatrixStackUtils.applyTransform} reconstructs from {@code Transform.rotate}.
     *
     * <p>Done in double precision straight from the quaternion (rather than via a
     * float matrix) to keep the reconstruction error at the gimbal poles well below
     * a degree. Verified against JOML 1.10.5 over 1,000,000 random orientations:
     * exact away from the poles, &lt; 0.5 deg at the exact pole.</p>
     */
    static void toBbsEulerZYX(Quaternionf q, Vector3f out) {
        double x = q.x, y = q.y, z = q.z, w = q.w;

        // Rotation-matrix elements (column-vector convention R*v), in double precision.
        double r20 = 2.0 * (x * z - w * y);           // R[2][0] = -sin(ry)
        double r21 = 2.0 * (y * z + w * x);           // R[2][1] =  cos(ry)*sin(rx)
        double r22 = 1.0 - 2.0 * (x * x + y * y);     // R[2][2] =  cos(ry)*cos(rx)
        double r10 = 2.0 * (x * y + w * z);           // R[1][0] =  sin(rz)*cos(ry)
        double r00 = 1.0 - 2.0 * (y * y + z * z);     // R[0][0] =  cos(rz)*cos(ry)

        double ry = Math.asin(Math.max(-1.0, Math.min(1.0, -r20)));
        double rx, rz;

        if (Math.abs(r20) < 0.99999) {
            rx = Math.atan2(r21, r22);
            rz = Math.atan2(r10, r00);
        } else {
            // Gimbal pole: ry = +/-90 deg couples rx and rz; pin rz = 0 and fold the
            // residual into rx using the still-well-conditioned R[0][1], R[1][1].
            double r01 = 2.0 * (x * y - w * z);       // R[0][1]
            double r11 = 1.0 - 2.0 * (x * x + z * z); // R[1][1]
            rz = 0.0;
            rx = (r20 < 0.0) ? Math.atan2(r01, r11) : Math.atan2(-r01, r11);
        }

        out.set((float) rx, (float) ry, (float) rz);
    }

    private static int clampSubdivisions(int value) {
        if (value < 1) return 1;
        if (value > 32) return 32;
        return value;
    }

    /**
     * Compute and store the body-frame offset D from the ship's physics origin to the
     * structure's center-bottom anchor (the point BBS places at the form origin), using
     * Sable's pose to transform the local anchor to world. Per tick we then record
     * {@code physicsOrigin + worldRotation * D}.
     */
    private void computeAndStoreCenterOffset(ShipPose pose, dev.ryanhcode.sable.sublevel.ServerSubLevel sl,
                                             StructureSnapshotService.Result snap) {
        try {
            Pose3dc lp = sl.logicalPose();
            if (lp == null) {
                centerOffsetPerShip.put(pose.shipId(), new Vector3d());
                return;
            }
            Vec3 anchorWorld = lp.transformPosition(new Vec3(snap.anchorX(), snap.anchorY(), snap.anchorZ()));
            Vector3dc posSnap = lp.position();
            Quaterniondc rotSnap = lp.orientation();

            double relx = anchorWorld.x - posSnap.x();
            double rely = anchorWorld.y - posSnap.y();
            double relz = anchorWorld.z - posSnap.z();
            double dist = Math.sqrt(relx * relx + rely * rely + relz * relz);

            if (dist > 256.0) {
                // Anchor is implausibly far from the physics origin: the local-frame
                // assumption is probably wrong for this build, so skip the correction.
                AeronauticsCmlBridge.LOGGER.warn("[aeronauticscml] Ship {} anchor sanity failed (dist={}); center alignment disabled for it.",
                        pose.shipId(), dist);
                centerOffsetPerShip.put(pose.shipId(), new Vector3d());
                return;
            }

            Quaterniond inv = new Quaterniond(rotSnap.x(), rotSnap.y(), rotSnap.z(), rotSnap.w()).normalize().conjugate();
            Vector3d d = inv.transform(new Vector3d(relx, rely, relz));
            centerOffsetPerShip.put(pose.shipId(), d);
            AeronauticsCmlBridge.LOGGER.info("[aeronauticscml] Ship {} structure center-bottom offset D=({}, {}, {})",
                    pose.shipId(), String.format("%.3f", d.x), String.format("%.3f", d.y), String.format("%.3f", d.z));
        } catch (Throwable t) {
            AeronauticsCmlBridge.LOGGER.warn("[aeronauticscml] Ship {} center-offset computation failed: {}", pose.shipId(), t.toString());
            centerOffsetPerShip.put(pose.shipId(), new Vector3d());
        }
    }

    private StructureForm snapshotAndCreateForm(ShipPose pose) {
        StructureSnapshotService.Result snap = null;
        ServerSubLevel sl = findSubLevel(pose);
        if (sl != null) {
            try {
                snap = snapshotService.snapshotShip(sl);
                if (snap != null) {
                    computeAndStoreCenterOffset(pose, sl, snap);
                }
            } catch (Throwable t) {
                AeronauticsCmlBridge.LOGGER.error("[aeronauticscml] Failed to snapshot ship {}: {}",
                        pose.shipId(), t.toString(), t);
            }
        }

        if (snap == null) {
            AeronauticsCmlBridge.LOGGER.warn("[aeronauticscml] Ship {} has no structure snapshot - replay will be camera-only", pose.shipId());
            return null;
        }

        Path structureFile = snap.file();
        structureFilePerShip.put(pose.shipId(), structureFile);
        AeronauticsCmlBridge.LOGGER.info("[aeronauticscml] Ship {} structure snapshot: {}", pose.shipId(), structureFile);

        StructureForm form = createStructureForm(structureFile);
        if (form != null) {
            AeronauticsCmlBridge.LOGGER.info("[aeronauticscml] Created StructureForm for ship {} pointing at world:{}",
                    pose.shipId(), structureFile.getFileName());
        }
        return form;
    }

    /**
     * Look up (and cache) the Sable {@link ServerSubLevel} backing a ship, so we can read
     * its live pose and scan its plot every tick without re-enumerating the container.
     */
    private ServerSubLevel findSubLevel(ShipPose pose) {
        ServerSubLevel cached = subLevelPerShip.get(pose.shipId());
        if (cached != null) return cached;
        if (!(pose.level() instanceof ServerLevel serverLevel)) return null;
        try {
            dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container =
                    dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(serverLevel);
            if (container == null) return null;
            for (ServerSubLevel sl : container.getAllSubLevels()) {
                if (sl.getUniqueId() != null && sl.getUniqueId().equals(pose.shipId())) {
                    subLevelPerShip.put(pose.shipId(), sl);
                    return sl;
                }
            }
        } catch (Throwable t) {
            AeronauticsCmlBridge.LOGGER.debug("[aeronauticscml] findSubLevel failed for {}: {}", pose.shipId(), t.toString());
        }
        return null;
    }

    /**
     * Create a BBS {@link StructureForm} pointing at a snapshot .nbt via the "world:"
     * prefix (resolved by BBS CML's WorldStructuresSourcePack). Shared by ships and
     * propeller blade contraptions.
     */
    private StructureForm createStructureForm(Path structureFile) {
        try {
            FormArchitect forms = BBSMod.getForms();
            if (forms == null) {
                AeronauticsCmlBridge.LOGGER.warn("[aeronauticscml] BBSMod.getForms() returned null - cannot create StructureForm");
                return null;
            }
            Form form = forms.create(Link.bbs("structure"));
            if (!(form instanceof StructureForm structureForm)) {
                AeronauticsCmlBridge.LOGGER.warn("[aeronauticscml] FormArchitect.create(structure) returned {} (expected StructureForm)",
                        form == null ? "null" : form.getClass().getName());
                return null;
            }
            String fileName = structureFile.getFileName().toString().replace('\\', '/');
            structureForm.structureFile.set("world:" + fileName);
            return structureForm;
        } catch (Throwable t) {
            AeronauticsCmlBridge.LOGGER.error("[aeronauticscml] Failed to create StructureForm for {}: {}",
                    structureFile, t.toString(), t);
            return null;
        }
    }

    // ---- Block-update generations ---------------------------------------------------

    /**
     * If enough ticks have passed and the ship's blocks have changed since the active
     * generation, snapshot a NEW generation form, keyframe the old one hidden (scale 0)
     * from this tick and the new one shown from this tick (hidden before), repoint the
     * ship's replay/form to the new generation, and return it. Returns null if no roll.
     *
     * <p>Visibility uses the transform property's scale: BBS applies it (scaleStack), so
     * a scale-0 keyframe collapses the form to nothing - a reliable per-tick hide without
     * relying on alpha. Each generation's own .nbt keeps the same ship uuid prefix so its
     * ropes/kinetics still resolve (the rope store strips the "-g&lt;n&gt;" suffix).</p>
     */
    private Replay maybeRollGeneration(ShipPose pose, float tickF, long relativeTick) {
        UUID shipId = pose.shipId();
        Long lastCheck = lastGenCheckPerShip.get(shipId);
        if (lastCheck != null && (relativeTick - lastCheck) < blockUpdateIntervalTicks) {
            return null;
        }
        lastGenCheckPerShip.put(shipId, relativeTick);

        try {
            ServerSubLevel sl = findSubLevel(pose);
            if (sl == null) return null;
            long hash = snapshotService.contentHash(sl);
            if (hash == 0L) return null; // hashing failed - don't roll on bad data
            Long prev = genHashPerShip.get(shipId);
            if (prev == null) { genHashPerShip.put(shipId, hash); return null; } // baseline
            if (hash == prev) return null; // unchanged
            genHashPerShip.put(shipId, hash);

            Replay oldReplay = replayPerShip.get(shipId);
            StructureForm oldForm = formPerShip.get(shipId);
            if (oldReplay == null || oldForm == null) return null;

            int gen = genCountPerShip.merge(shipId, 1, Integer::sum);
            String fileId = shipId + "-g" + gen;
            StructureSnapshotService.Result snap = snapshotService.snapshotShip(sl, fileId);
            if (snap == null) return null;
            StructureForm newForm = createStructureForm(snap.file());
            if (newForm == null) return null;

            // Hide the outgoing generation from this tick on.
            insertHiddenKeyframe(oldReplay.properties.getOrCreate(oldForm, "transform"), tickF);

            // New generation: recompute the anchor offset for the changed geometry, hide it
            // before now; the normal rotation keyframe written right after shows it (scale 1).
            computeAndStoreCenterOffset(pose, sl, snap);
            Replay newReplay = film.replays.addReplay();
            newReplay.label.set(pose.shipName() + " g" + gen);
            newReplay.uuid.set(fileId);
            newReplay.form.set(newForm);
            insertHiddenKeyframe(newReplay.properties.getOrCreate(newForm, "transform"), 0F);

            replayPerShip.put(shipId, newReplay);
            formPerShip.put(shipId, newForm);
            structureFilePerShip.put(shipId, snap.file());
            // Start the new transform channel's rotation-subdivision tracking clean.
            lastRotationPerShip.remove(shipId);
            lastRotationTickPerShip.remove(shipId);

            AeronauticsCmlBridge.LOGGER.info("[aeronauticscml] Ship {} blocks changed -> generation {} ({})",
                    shipId, gen, snap.file().getFileName());
            return newReplay;
        } catch (Throwable t) {
            AeronauticsCmlBridge.LOGGER.warn("[aeronauticscml] block-update generation roll failed for {}: {}", shipId, t.toString());
            return null;
        }
    }

    /** Insert a CONST keyframe that hides a form by collapsing its transform scale to 0. */
    private static void insertHiddenKeyframe(KeyframeChannel channel, float tick) {
        Transform t = new Transform();
        t.scale.set(0F, 0F, 0F);
        int index = channel.insert(tick, t);
        Keyframe kf = channel.get(index);
        if (kf != null) {
            kf.getInterpolation().setInterp(Interpolations.CONST);
        }
    }

    // ---- Propeller-bearing blade contraption capture --------------------------------

    /**
     * Capture/refresh this ship's propeller-bearing blade contraptions for this tick.
     *
     * <p>A propeller bearing's blades are a Create contraption (assembled blocks rotating
     * about the bearing axis) held in a {@code PropellerBearingContraptionEntity}, NOT in
     * the ship's block grid and NOT a Sable sub-ship - so the ship snapshot misses them.
     * We snapshot the blocks once as their own StructureForm, then each tick keyframe the
     * form's world transform = ship_pose o tilt o spin(axis, angle), so the blades render
     * and spin on exactly the same path as a ship form.</p>
     *
     * <p>The transform mirrors {@code PropellerBearingContraptionEntity.applyLocalTransforms}
     * (decoded from bytecode): a blade-local point {@code p} maps to
     * {@code entityPos + 0.5 - o + R_tilt*( o + R_spin*(p - 0.5) )} in the sublevel, with
     * {@code o = 0.75 * facing}, {@code R_spin = rotate(angle deg, axis)}, {@code R_tilt =
     * tiltQuat}; the sublevel point is then mapped to the world by the ship's logical pose.
     * BBS rotates a form about its center-bottom anchor, so we record that anchor's world
     * position as the position keyframe and the full rotation as the transform keyframe.</p>
     *
     * <p>Everything Create/Aeronautics is reflective (jarjar'd, off the compile classpath);
     * any failure is swallowed so ship/rope capture is never affected.</p>
     */
    private void recordContraptions(ShipPose pose, ServerSubLevel sl, float tickF) {
        try {
            Class<?> bearingClass = propBearingClass();
            if (bearingClass == null) return; // Aeronautics not present
            if (!(sl.getLevel() instanceof ServerLevel plotLevel)) return;
            Pose3dc lp = sl.logicalPose();
            if (lp == null) return;

            List<BlockPos> bearings = bearingPositionsPerShip.get(pose.shipId());
            if (bearings == null) {
                bearings = scanBearingPositions(plotLevel, sl, bearingClass);
                bearingPositionsPerShip.put(pose.shipId(), bearings);
                if (!bearings.isEmpty()) {
                    AeronauticsCmlBridge.LOGGER.info("[aeronauticscml] Ship {} has {} propeller bearing(s) for contraption capture",
                            pose.shipId(), bearings.size());
                }
            }
            if (bearings.isEmpty()) return;

            Quaternionf shipRot = new Quaternionf(pose.worldRotation()).normalize();

            for (BlockPos bp : bearings) {
                try {
                    BlockEntity be = plotLevel.getBlockEntity(bp);
                    if (be == null || !bearingClass.isInstance(be)) continue;
                    Object contraption = getMovedContraption(be);
                    if (!(contraption instanceof Entity ce)) continue; // null = bearing not assembled
                    UUID cid = ce.getUUID();

                    ContraptionRec rec = contraptionRecs.get(cid);
                    if (rec == null) {
                        rec = createContraptionReplay(plotLevel, contraption, cid, pose);
                        if (rec == null) continue;
                        contraptionRecs.put(cid, rec);
                    }

                    float angle = getAngle(contraption);            // spin, degrees
                    Direction dir = getDirection(contraption);      // bearing facing
                    Direction.Axis axis = getRotationAxis(contraption);
                    if (axis == null) axis = dir.getAxis();
                    Quaternionf tilt = getTiltQuat(contraption);    // identity for non-gyro
                    Vec3 entityPos = ce.position();

                    Quaternionf spin = spinQuat(axis, angle);
                    // Local (contraption-frame) offsets are small, so float precision is ample;
                    // entityPos stays double. subPos(anchor) = entityPos + 0.5 - o + R_tilt*( o + R_spin*(anchor - 0.5) )
                    Vector3f o = new Vector3f(dir.getStepX() * 0.75f, dir.getStepY() * 0.75f, dir.getStepZ() * 0.75f);
                    Vector3f hp = new Vector3f((float) (rec.anchorX - 0.5), (float) (rec.anchorY - 0.5), (float) (rec.anchorZ - 0.5));
                    Vector3f spun = spin.transform(hp, new Vector3f());
                    spun.add(o);
                    Vector3f tilted = tilt.transform(spun, new Vector3f());
                    Vec3 subPos = new Vec3(
                            entityPos.x + 0.5 - o.x + tilted.x,
                            entityPos.y + 0.5 - o.y + tilted.y,
                            entityPos.z + 0.5 - o.z + tilted.z);
                    Vec3 world = lp.transformPosition(subPos);

                    rec.replay.keyframes.x.insert(tickF, world.x);
                    rec.replay.keyframes.y.insert(tickF, world.y);
                    rec.replay.keyframes.z.insert(tickF, world.z);

                    // R_keyframe = ship_rotation o tilt o spin (spin applied first).
                    Quaternionf q = new Quaternionf(shipRot).mul(tilt).mul(spin).normalize();
                    KeyframeChannel tc = rec.replay.properties.getOrCreate(rec.form, "transform");
                    writeRotationKeyframes(cid, tc, tickF, q);
                } catch (Throwable inner) {
                    if (debugCounter % 200 == 0) {
                        AeronauticsCmlBridge.LOGGER.debug("[aeronauticscml] contraption tick failed at {}: {}", bp, inner.toString());
                    }
                }
            }
        } catch (Throwable t) {
            if (debugCounter % 200 == 0) {
                AeronauticsCmlBridge.LOGGER.debug("[aeronauticscml] recordContraptions failed for {}: {}", pose.shipId(), t.toString());
            }
        }
    }

    /** Snapshot a contraption's blocks to a structure .nbt and allocate its BBS replay/form. */
    private ContraptionRec createContraptionReplay(ServerLevel plotLevel, Object contraption, UUID cid, ShipPose pose) {
        try {
            Map<BlockPos, StructureTemplate.StructureBlockInfo> blocks = getContraptionBlocks(contraption);
            if (blocks == null || blocks.isEmpty()) return null;
            StructureSnapshotService.Result snap = snapshotService.snapshotBlockMap(plotLevel, blocks, cid);
            if (snap == null) return null;
            StructureForm form = createStructureForm(snap.file());
            if (form == null) return null;
            Replay replay = film.replays.addReplay();
            replay.label.set("propeller-" + cid.toString().substring(0, 8));
            replay.uuid.set(cid.toString());
            replay.form.set(form);
            AeronauticsCmlBridge.LOGGER.info("[aeronauticscml] Captured propeller contraption {} ({} blocks) on ship {}",
                    cid, blocks.size(), pose.shipId());
            return new ContraptionRec(replay, form, snap.anchorX(), snap.anchorY(), snap.anchorZ());
        } catch (Throwable t) {
            AeronauticsCmlBridge.LOGGER.warn("[aeronauticscml] Failed to create contraption replay {}: {}", cid, t.toString());
            return null;
        }
    }

    /** Scan a ship's plot chunks once for propeller-bearing block entities. */
    private List<BlockPos> scanBearingPositions(ServerLevel plotLevel, ServerSubLevel sl, Class<?> bearingClass) {
        List<BlockPos> out = new ArrayList<>();
        try {
            LevelPlot plot = sl.getPlot();
            if (plot == null) return out;
            ChunkPos cmin = plot.getChunkMin();
            ChunkPos cmax = plot.getChunkMax();
            ServerChunkCache scc = plotLevel.getChunkSource();
            for (int cx = cmin.x; cx <= cmax.x; cx++) {
                for (int cz = cmin.z; cz <= cmax.z; cz++) {
                    LevelChunk chunk = scc.getChunkNow(cx, cz);
                    if (chunk == null) continue;
                    for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
                        if (bearingClass.isInstance(e.getValue())) out.add(e.getKey().immutable());
                    }
                }
            }
        } catch (Throwable t) {
            AeronauticsCmlBridge.LOGGER.debug("[aeronauticscml] scanBearingPositions failed: {}", t.toString());
        }
        return out;
    }

    /** Quaternion for a spin of {@code angleDeg} degrees about a block axis (matches catnip VecHelper.rotate). */
    private static Quaternionf spinQuat(Direction.Axis axis, float angleDeg) {
        float rad = (float) Math.toRadians(angleDeg);
        if (axis == Direction.Axis.X) return new Quaternionf().rotationX(rad);
        if (axis == Direction.Axis.Y) return new Quaternionf().rotationY(rad);
        if (axis == Direction.Axis.Z) return new Quaternionf().rotationZ(rad);
        return new Quaternionf();
    }

    // ---- Reflection into Create/Aeronautics (jarjar'd, not on the compile classpath) ----

    private static Class<?> propBearingClass() {
        if (!propBearingClassResolved) {
            synchronized (RealCmlRecorder.class) {
                if (!propBearingClassResolved) {
                    try {
                        propBearingClassCached = Class.forName(
                                "dev.eriksonn.aeronautics.content.blocks.propeller.bearing.propeller_bearing.PropellerBearingBlockEntity");
                    } catch (Throwable t) {
                        propBearingClassCached = null;
                    }
                    propBearingClassResolved = true;
                }
            }
        }
        return propBearingClassCached;
    }

    private static Object getMovedContraption(Object bearing) throws Exception {
        Method m = mGetMovedContraption;
        if (m == null) {
            m = propBearingClass().getMethod("getMovedContraption");
            mGetMovedContraption = m;
        }
        return m.invoke(bearing);
    }

    private static void ensureContraptionReflection(Object contraption) throws Exception {
        if (mGetContraption == null) {
            Class<?> c = contraption.getClass();
            mGetContraption = c.getMethod("getContraption");
            mGetAngle = c.getMethod("getAngle", float.class);
            mGetRotationAxis = c.getMethod("getRotationAxis");
            fTiltQuat = c.getField("tiltQuat");
            fDirection = c.getField("direction");
            mGetBlocks = mGetContraption.getReturnType().getMethod("getBlocks");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<BlockPos, StructureTemplate.StructureBlockInfo> getContraptionBlocks(Object contraption) throws Exception {
        ensureContraptionReflection(contraption);
        Object contraptionObj = mGetContraption.invoke(contraption);
        if (contraptionObj == null) return null;
        return (Map<BlockPos, StructureTemplate.StructureBlockInfo>) mGetBlocks.invoke(contraptionObj);
    }

    private static float getAngle(Object contraption) throws Exception {
        ensureContraptionReflection(contraption);
        return (float) mGetAngle.invoke(contraption, 1.0f);
    }

    private static Direction.Axis getRotationAxis(Object contraption) {
        try {
            ensureContraptionReflection(contraption);
            Object a = mGetRotationAxis.invoke(contraption);
            if (a instanceof Direction.Axis ax) return ax;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Quaternionf getTiltQuat(Object contraption) {
        try {
            ensureContraptionReflection(contraption);
            Object q = fTiltQuat.get(contraption);
            if (q instanceof Quaternionf qf) return new Quaternionf(qf);
        } catch (Throwable ignored) {
        }
        return new Quaternionf();
    }

    private static Direction getDirection(Object contraption) {
        try {
            ensureContraptionReflection(contraption);
            Object d = fDirection.get(contraption);
            if (d instanceof Direction dir) return dir;
        } catch (Throwable ignored) {
        }
        return Direction.UP;
    }

    @Override
    public void stop() {
        synchronized (lock) {
            if (!recording) return;
            try {
                if (film != null) {
                    FilmManager films = BBSMod.getFilms();
                    if (films != null) {
                        MapType data = film.toData() instanceof MapType mt ? mt : new MapType();
                        String filmName = currentFilmName != null ? currentFilmName : BRIDGE_FILM_NAME;
                        films.save(filmName, data);
                        AeronauticsCmlBridge.LOGGER.info("[aeronauticscml] CML film '{}' saved with {} ship replays ({} with structure forms)",
                                filmName,
                                replayPerShip.size(), formPerShip.size());
                    }
                    writeRopeFiles();
                }
            } catch (Throwable t) {
                AeronauticsCmlBridge.LOGGER.error("[aeronauticscml] Failed to save CML film", t);
            } finally {
                recording = false;
                film = null;
                currentFilmName = null;
                replayPerShip.clear();
                formPerShip.clear();
                structureFilePerShip.clear();
                lastRotationPerShip.clear();
                lastRotationTickPerShip.clear();
                centerOffsetPerShip.clear();
                subLevelPerShip.clear();
                bearingPositionsPerShip.clear();
                contraptionRecs.clear();
                genHashPerShip.clear();
                genCountPerShip.clear();
                lastGenCheckPerShip.clear();
            }
        }
    }

    @Override public boolean isRecording() {
        synchronized (lock) { return recording; }
    }

    @Override public long totalFramesWritten() {
        synchronized (lock) { return totalFrames; }
    }

    @Override public String backendName() {
        return "bbs-cml/real:" + BBSMod.MOD_ID;
    }

    public static void validateRuntimeApi() throws ReflectiveOperationException {
        BBSMod.class.getMethod("getFilms");
        BBSMod.class.getMethod("getForms");
        BBSMod.class.getMethod("getWorldFolder");
        FilmManager.class.getMethod("load", String.class);
        FilmManager.class.getMethod("create", String.class, MapType.class);
        FilmManager.class.getMethod("save", String.class, MapType.class);
        Replays.class.getMethod("addReplay");
        FormArchitect.class.getMethod("create", Link.class);
        Replay.class.getField("form");
        Replay.class.getField("keyframes");
        Replay.class.getField("label");
        Replay.class.getField("uuid");
        Replay.class.getField("properties");
        StructureForm.class.getField("structureFile");
    }

    private static String filmName(String sessionName) {
        String suffix = sessionName == null || sessionName.isBlank() ? "session" : sessionName.trim();
        suffix = suffix.replaceAll("[^A-Za-z0-9._-]+", "-");
        suffix = suffix.replaceAll("^-+|-+$", "");
        if (suffix.isEmpty()) suffix = "session";
        return BRIDGE_FILM_NAME + "-" + suffix;
    }
}
