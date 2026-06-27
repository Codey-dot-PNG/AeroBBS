package com.aeronauticscml.bridge.client;

import com.aeronauticscml.bridge.mod.AeronauticsCmlBridge;
import com.google.gson.Gson;
import mchorse.bbs_mod.BBSMod;

import java.io.File;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-side reader/cache for the rope side-files written by the recorder
 * ({@code <worldFolder>/aeronauticscml/ropes/<filmId>.json}). Keyed by film id
 * (which equals the recorder's film name). Re-reads when the file changes on disk
 * so re-recording the same film is picked up.
 */
public final class RopeDataStore {
    private RopeDataStore() {}

    private static final Gson GSON = new Gson();

    public static final class RopeFile {
        public Map<String, List<RopeSnap>> ticks;
        /** relativeTick -> the owning ship's center-bottom anchor pose [x,y,z,qx,qy,qz,qw]. */
        public Map<String, double[]> poses;
    }
    public static final class RopeSnap {
        public double radius;
        public double[] points; // flat absolute world [x0,y0,z0, x1,y1,z1, ...]
    }

    private static final class Entry {
        RopeFile file;
        long mtime;
        long lastCheck;
    }

    private static final Map<String, Entry> CACHE = new HashMap<>();

    /** Ropes recorded at the given (integer) tick for this film, or null. Holds the last tick if exact is missing. */
    public static List<RopeSnap> ropesAt(String filmId, int tick) {
        RopeFile f = load(filmId);
        if (f == null || f.ticks == null || f.ticks.isEmpty()) {
            return null;
        }
        List<RopeSnap> exact = f.ticks.get(Integer.toString(tick));
        if (exact != null) {
            return exact;
        }
        // Fall back to the nearest recorded tick <= requested (CONST hold).
        int bestTick = Integer.MIN_VALUE;
        for (String key : f.ticks.keySet()) {
            try {
                int t = Integer.parseInt(key);
                if (t <= tick && t > bestTick) bestTick = t;
            } catch (NumberFormatException ignored) {}
        }
        return bestTick == Integer.MIN_VALUE ? null : f.ticks.get(Integer.toString(bestTick));
    }

    /** The owning ship's anchor pose [x,y,z,qx,qy,qz,qw] at the given tick (nearest &lt;=), or null. */
    public static double[] poseAt(String key, int tick) {
        RopeFile f = load(key);
        if (f == null || f.poses == null || f.poses.isEmpty()) {
            return null;
        }
        double[] exact = f.poses.get(Integer.toString(tick));
        if (exact != null) {
            return exact;
        }
        int bestTick = Integer.MIN_VALUE;
        for (String k : f.poses.keySet()) {
            try {
                int t = Integer.parseInt(k);
                if (t <= tick && t > bestTick) bestTick = t;
            } catch (NumberFormatException ignored) {}
        }
        return bestTick == Integer.MIN_VALUE ? null : f.poses.get(Integer.toString(bestTick));
    }

    private static RopeFile load(String filmId) {
        long now = System.currentTimeMillis();
        Entry e = CACHE.get(filmId);
        if (e != null && now - e.lastCheck < 1000L) {
            return e.file; // throttle disk checks to once/sec
        }
        try {
            File wf = BBSMod.getWorldFolder();
            if (wf == null) {
                return e != null ? e.file : null;
            }
            Path p = wf.toPath().resolve("aeronauticscml").resolve("ropes").resolve(filmId + ".json");
            if (!Files.exists(p)) {
                if (e == null) { e = new Entry(); CACHE.put(filmId, e); }
                e.lastCheck = now;
                e.file = null;
                return null;
            }
            long mtime = Files.getLastModifiedTime(p).toMillis();
            if (e != null && e.file != null && e.mtime == mtime) {
                e.lastCheck = now;
                return e.file;
            }
            try (Reader r = Files.newBufferedReader(p)) {
                RopeFile f = GSON.fromJson(r, RopeFile.class);
                Entry ne = new Entry();
                ne.file = f;
                ne.mtime = mtime;
                ne.lastCheck = now;
                CACHE.put(filmId, ne);
                int count = (f != null && f.ticks != null) ? f.ticks.size() : 0;
                AeronauticsCmlBridge.LOGGER.info("[aeronauticscml] Loaded rope side-file for film '{}' ({} ticks)", filmId, count);
                return f;
            }
        } catch (Throwable t) {
            AeronauticsCmlBridge.LOGGER.debug("[aeronauticscml] Failed to read rope side-file for '{}': {}", filmId, t.toString());
            return e != null ? e.file : null;
        }
    }
}
