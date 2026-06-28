package com.aeronauticscml.bridge.client;

import com.aeronauticscml.bridge.mod.AeronauticsCmlBridge;
import com.google.gson.Gson;
import mchorse.bbs_mod.BBSMod;

import java.io.File;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-side reader/cache for the rope side-files written by the recorder
 * ({@code <worldFolder>/aeronauticscml/ropes/<shipUuid>.json}). Re-reads when the file
 * changes on disk so re-recording is picked up.
 *
 * <p>Ropes are sampled at the (≤20 Hz) record cadence but played back at the render
 * frame rate, so {@link #frameAt} resolves a FRACTIONAL playback tick to the two
 * bracketing recorded snapshots plus a blend factor; the renderers interpolate between
 * them (in form-local or world space) so the rope animates smoothly instead of stepping.</p>
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

    /**
     * The two recorded snapshots bracketing a fractional tick, plus a blend factor.
     * {@code ropes1}/{@code pose1} are null (and {@code alpha == 0}) when the requested
     * tick is at/after the last recorded sample - then the renderer just uses sample 0.
     */
    public static final class Frame {
        public final List<RopeSnap> ropes0;
        public final List<RopeSnap> ropes1;
        public final double[] pose0;
        public final double[] pose1;
        public final float alpha;
        Frame(List<RopeSnap> ropes0, List<RopeSnap> ropes1, double[] pose0, double[] pose1, float alpha) {
            this.ropes0 = ropes0; this.ropes1 = ropes1;
            this.pose0 = pose0; this.pose1 = pose1; this.alpha = alpha;
        }
    }

    private static final class Entry {
        RopeFile file;
        int[] sortedTicks;
        long mtime;
        long lastCheck;
    }

    private static final Map<String, Entry> CACHE = new HashMap<>();

    /**
     * Resolve a fractional playback tick to the bracketing recorded snapshots for a ship,
     * or null if there is no rope data. The renderer interpolates {@code ropes0 -> ropes1}
     * by {@code alpha}.
     */
    public static Frame frameAt(String shipUuid, float tickF) {
        shipUuid = stripGeneration(shipUuid); // block-update generation forms keep the ship's rope file
        Entry e = entry(shipUuid);
        if (e == null || e.file == null || e.file.ticks == null || e.sortedTicks == null || e.sortedTicks.length == 0) {
            return null;
        }
        RopeFile f = e.file;
        int[] sorted = e.sortedTicks;

        // Largest recorded tick <= tickF (clamp to first when before the start).
        int i0 = 0;
        for (int i = 0; i < sorted.length; i++) {
            if (sorted[i] <= tickF) i0 = i; else break;
        }
        int t0 = sorted[i0];
        List<RopeSnap> r0 = f.ticks.get(Integer.toString(t0));
        if (r0 == null) return null;
        double[] p0 = f.poses == null ? null : f.poses.get(Integer.toString(t0));

        if (i0 + 1 < sorted.length) {
            int t1 = sorted[i0 + 1];
            List<RopeSnap> r1 = f.ticks.get(Integer.toString(t1));
            if (r1 != null && t1 > t0) {
                double[] p1 = f.poses == null ? null : f.poses.get(Integer.toString(t1));
                float alpha = (tickF - t0) / (float) (t1 - t0);
                if (alpha < 0F) alpha = 0F; else if (alpha > 1F) alpha = 1F;
                return new Frame(r0, r1, p0, p1, alpha);
            }
        }
        return new Frame(r0, null, p0, null, 0F);
    }

    private static Entry entry(String shipUuid) {
        long now = System.currentTimeMillis();
        Entry e = CACHE.get(shipUuid);
        if (e != null && now - e.lastCheck < 1000L) {
            return e; // throttle disk checks to once/sec
        }
        try {
            File wf = BBSMod.getWorldFolder();
            if (wf == null) {
                return e;
            }
            Path p = wf.toPath().resolve("aeronauticscml").resolve("ropes").resolve(shipUuid + ".json");
            if (!Files.exists(p)) {
                if (e == null) { e = new Entry(); CACHE.put(shipUuid, e); }
                e.lastCheck = now;
                e.file = null;
                e.sortedTicks = null;
                return e;
            }
            long mtime = Files.getLastModifiedTime(p).toMillis();
            if (e != null && e.file != null && e.mtime == mtime) {
                e.lastCheck = now;
                return e;
            }
            try (Reader r = Files.newBufferedReader(p)) {
                RopeFile f = GSON.fromJson(r, RopeFile.class);
                Entry ne = new Entry();
                ne.file = f;
                ne.mtime = mtime;
                ne.lastCheck = now;
                ne.sortedTicks = computeSortedTicks(f);
                CACHE.put(shipUuid, ne);
                int count = ne.sortedTicks == null ? 0 : ne.sortedTicks.length;
                AeronauticsCmlBridge.LOGGER.info("[aeronauticscml] Loaded rope side-file for '{}' ({} ticks)", shipUuid, count);
                return ne;
            }
        } catch (Throwable t) {
            AeronauticsCmlBridge.LOGGER.debug("[aeronauticscml] Failed to read rope side-file for '{}': {}", shipUuid, t.toString());
            return e;
        }
    }

    /**
     * Block-update generation forms are keyed "&lt;shipUuid&gt;-g&lt;n&gt;" but share the ship's
     * single rope side-file (&lt;shipUuid&gt;.json). Strip a trailing "-g&lt;digits&gt;" so they
     * resolve to it. A UUID never contains "-g", so this is unambiguous.
     */
    private static String stripGeneration(String uuid) {
        if (uuid == null) return null;
        int i = uuid.lastIndexOf("-g");
        if (i > 0 && i + 2 < uuid.length()) {
            for (int k = i + 2; k < uuid.length(); k++) {
                if (!Character.isDigit(uuid.charAt(k))) return uuid;
            }
            return uuid.substring(0, i);
        }
        return uuid;
    }

    private static int[] computeSortedTicks(RopeFile f) {
        if (f == null || f.ticks == null || f.ticks.isEmpty()) {
            return new int[0];
        }
        int[] out = new int[f.ticks.size()];
        int n = 0;
        for (String key : f.ticks.keySet()) {
            try {
                out[n++] = Integer.parseInt(key);
            } catch (NumberFormatException ignored) {}
        }
        if (n != out.length) {
            out = Arrays.copyOf(out, n);
        }
        Arrays.sort(out);
        return out;
    }
}
