package com.aeronauticscml.bridge.cml;

import com.aeronauticscml.bridge.aeronautics.StructureSnapshotService;
import com.aeronauticscml.bridge.api.ICmlRecorder;
import com.aeronauticscml.bridge.api.ShipPose;
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
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.pose.Transform;

import org.joml.Vector3f;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
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
 */
public final class RealCmlRecorder implements ICmlRecorder {
    private static final String BRIDGE_FILM_NAME = "aeronautics-bridge";

    private final Object lock = new Object();
    private final Map<UUID, Replay> replayPerShip = new HashMap<>();
    private final Map<UUID, StructureForm> formPerShip = new HashMap<>();
    private final Map<UUID, Path> structureFilePerShip = new HashMap<>();
    private final Map<UUID, Vector3f> prevEulerPerShip = new HashMap<>();
    private final StructureSnapshotService snapshotService = new StructureSnapshotService();

    private Film film;
    private String currentFilmName;
    private boolean recording;
    private long totalFrames;
    private int firstTick = Integer.MIN_VALUE;
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
                prevEulerPerShip.clear();
                debugCounter = 0;
                firstTick = Integer.MIN_VALUE;
                recording = true;
                AeronauticsCmlBridge.LOGGER.info("[aeronauticscml] CML recording session started. Film='{}'", currentFilmName);
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
                if (firstTick == Integer.MIN_VALUE) firstTick = (int) pose.tick();
                int relativeTick = (int) (pose.tick() - firstTick);

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

                // Position (entity-level, works for StructureForm)
                replay.keyframes.x.insert(relativeTick, pose.worldPosition().x);
                replay.keyframes.y.insert(relativeTick, pose.worldPosition().y);
                replay.keyframes.z.insert(relativeTick, pose.worldPosition().z);

                // Decompose the ship quaternion to Euler angles and store on the
                // form-level Transform via the "transform" property channel.
                // Transform.rotate stores radians — JOML's rotateZ/Y/X and
                // Axis.rotation all expect radians. The non-colon "transform"
                // key does absolute setRuntimeValue (confirmed by bytecode),
                // so NO rotation compounding occurs.
                // We unwrap each angle against the previous frame to prevent
                // sign flips at the ±π boundary from causing the linear
                // interpolation to rotate the wrong way during playback.
                Vector3f euler = new Vector3f();
                pose.worldRotation().normalize().getEulerAnglesZYX(euler);

                Vector3f prev = prevEulerPerShip.get(pose.shipId());
                if (prev != null) {
                    float dx = euler.x - prev.x;
                    if (dx > (float) Math.PI)  euler.x -= (float) (2 * Math.PI);
                    if (dx < (float)-Math.PI)  euler.x += (float) (2 * Math.PI);
                    float dy = euler.y - prev.y;
                    if (dy > (float) Math.PI)  euler.y -= (float) (2 * Math.PI);
                    if (dy < (float)-Math.PI)  euler.y += (float) (2 * Math.PI);
                    float dz = euler.z - prev.z;
                    if (dz > (float) Math.PI)  euler.z -= (float) (2 * Math.PI);
                    if (dz < (float)-Math.PI)  euler.z += (float) (2 * Math.PI);
                }
                prevEulerPerShip.put(pose.shipId(), new Vector3f(euler));

                StructureForm form = formPerShip.get(pose.shipId());
                if (form != null) {
                    KeyframeChannel transformChannel = replay.properties.getOrCreate(form, "transform");
                    Transform t = new Transform();
                    t.rotate.set(euler.x, euler.y, euler.z);
                    transformChannel.insert((float) relativeTick, t);
                }

                if (debugCounter++ % 20 == 0) {
                    AeronauticsCmlBridge.LOGGER.info("[aeronauticscml] ship={} tick={} pitch={} yaw={} roll={} q=({},{},{},{})",
                            pose.shipName(), pose.tick(),
                            euler.x, euler.y, euler.z,
                            pose.worldRotation().x, pose.worldRotation().y,
                            pose.worldRotation().z, pose.worldRotation().w);
                }

                totalFrames++;
            } catch (Throwable t) {
                AeronauticsCmlBridge.LOGGER.error("[aeronauticscml] CML recordFrame failed for ship {}: {}", pose.shipId(), t.toString(), t);
            }
        }
    }

    private StructureForm snapshotAndCreateForm(ShipPose pose) {
        Path structureFile = null;
        if (pose.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            try {
                dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container =
                        dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(serverLevel);
                if (container != null) {
                    for (dev.ryanhcode.sable.sublevel.ServerSubLevel sl : container.getAllSubLevels()) {
                        if (sl.getUniqueId() != null && sl.getUniqueId().equals(pose.shipId())) {
                            structureFile = snapshotService.snapshotShip(sl);
                            break;
                        }
                    }
                }
            } catch (Throwable t) {
                AeronauticsCmlBridge.LOGGER.error("[aeronauticscml] Failed to find SubLevel for ship {} during snapshot: {}",
                        pose.shipId(), t.toString(), t);
            }
        }

        if (structureFile == null) {
            AeronauticsCmlBridge.LOGGER.warn("[aeronauticscml] Ship {} has no structure snapshot - replay will be camera-only", pose.shipId());
            return null;
        }

        structureFilePerShip.put(pose.shipId(), structureFile);
        AeronauticsCmlBridge.LOGGER.info("[aeronauticscml] Ship {} structure snapshot: {}", pose.shipId(), structureFile);

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

            // Use "world:" prefix so BBS CML's WorldStructuresSourcePack resolves
            // it to <world>/generated/minecraft/structures/<filename>.nbt
            String fileName = structureFile.getFileName().toString().replace('\\', '/');
            String bbsPath = "world:" + fileName;
            structureForm.structureFile.set(bbsPath);

            AeronauticsCmlBridge.LOGGER.info("[aeronauticscml] Created StructureForm for ship {} pointing at {}",
                    pose.shipId(), bbsPath);

            return structureForm;
        } catch (Throwable t) {
            AeronauticsCmlBridge.LOGGER.error("[aeronauticscml] Failed to create StructureForm for ship {}: {}",
                    pose.shipId(), t.toString(), t);
            return null;
        }
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
                prevEulerPerShip.clear();
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
