package com.aeronauticscml.bridge.client;

import com.aeronauticscml.bridge.config.BridgeConfig;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import mchorse.bbs_mod.forms.forms.StructureForm;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/**
 * Draws a ship's recorded ropes in the BBS editor preview (and in-world via the form
 * renderer), in the ship form's LOCAL space. The recorder stores ropes as absolute
 * world polylines plus the ship's center-bottom anchor pose per tick (keyed by ship
 * uuid); we transform each point into form-local space with the inverse of that pose,
 * then draw a textured tube ({@link RopeMesh}).
 *
 * <p>Ropes are sampled at the record cadence (≤20 Hz) but the form moves at the render
 * frame rate, so we interpolate the rope shape between the two bracketing recorded
 * snapshots at the FRACTIONAL playback tick. Doing the blend in form-local space (each
 * endpoint transformed by its own anchor pose, then lerped) keeps the rope glued to the
 * smoothly-interpolated ship instead of stepping/swimming.</p>
 */
public final class RopeReplayRenderer {
    private RopeReplayRenderer() {}

    /** Max form-local jump of a rope's first node between adjacent samples before we stop
     *  blending it (guards against two ropes swapping order across a frame). */
    private static final float MAX_LOCAL_JUMP = 8.0F;

    public static int renderInFormSpace(PoseStack stack, MultiBufferSource consumers, StructureForm form, float tickF, int light) {
        if (!BridgeConfig.load().experimentalDynamicRender()) {
            return 0;
        }
        String uuid = extractUuid(safeFile(form));
        if (uuid == null) {
            return 0;
        }
        RopeDataStore.Frame fr = RopeDataStore.frameAt(uuid, tickF);
        if (fr == null || fr.ropes0 == null || fr.ropes0.isEmpty() || fr.pose0 == null) {
            return 0;
        }

        Quaternionf invRot0 = quat(fr.pose0).invert();
        double ax0 = fr.pose0[0], ay0 = fr.pose0[1], az0 = fr.pose0[2];

        boolean canBlend = fr.alpha > 0F && fr.ropes1 != null && fr.pose1 != null;
        Quaternionf invRot1 = canBlend ? quat(fr.pose1).invert() : null;
        double ax1 = canBlend ? fr.pose1[0] : 0, ay1 = canBlend ? fr.pose1[1] : 0, az1 = canBlend ? fr.pose1[2] : 0;
        float alpha = fr.alpha;

        VertexConsumer vc = consumers.getBuffer(RenderType.entityCutoutNoCull(RopeMesh.ROPE));

        int count = 0;
        for (int r = 0; r < fr.ropes0.size(); r++) {
            RopeDataStore.RopeSnap s0 = fr.ropes0.get(r);
            double[] a = s0.points;
            if (a == null || a.length < 6) {
                continue;
            }
            float radius = (float) Math.max(0.04, Math.min(0.5, s0.radius > 0 ? s0.radius : 0.08));
            RopeDataStore.RopeSnap s1 = (canBlend && r < fr.ropes1.size()) ? fr.ropes1.get(r) : null;

            // The matching next-tick polyline, only if it lines up (same point count + the
            // first node didn't teleport in form-local space).
            double[] b = null;
            if (s1 != null) {
                double[] cand = s1.points;
                if (cand != null && cand.length == a.length) {
                    Vector3f l0 = local(invRot0, a[0] - ax0, a[1] - ay0, a[2] - az0);
                    Vector3f l1 = local(invRot1, cand[0] - ax1, cand[1] - ay1, cand[2] - az1);
                    if (l0.distance(l1) <= MAX_LOCAL_JUMP) {
                        b = cand;
                    }
                }
            }

            // Anchored start: pin the rope's first node to the rigid block tie point (form-local),
            // so it stays glued to its block (winch) instead of the slightly-drifting recorded
            // physics point. Falls back to the recorded point if no tie point was captured.
            Vector3f prev = startPin(s0, s1, alpha);
            if (prev == null) {
                prev = blendLocal(invRot0, a, ax0, ay0, az0, invRot1, b, ax1, ay1, az1, 0, alpha);
            }
            RopeMesh.node(stack, vc, prev, light);
            for (int i = 3; i + 2 < a.length; i += 3) {
                Vector3f cur = blendLocal(invRot0, a, ax0, ay0, az0, invRot1, b, ax1, ay1, az1, i, alpha);
                RopeMesh.tube(stack, vc, prev, cur, radius, light);
                RopeMesh.node(stack, vc, cur, light);
                prev = cur;
            }
            count++;
        }
        return count;
    }

    /** The rigid form-local start tie point (interpolated between samples), or null if not recorded. */
    private static Vector3f startPin(RopeDataStore.RopeSnap s0, RopeDataStore.RopeSnap s1, float alpha) {
        if (s0 == null || s0.startLocal == null || s0.startLocal.length < 3) {
            return null;
        }
        Vector3f l0 = new Vector3f((float) s0.startLocal[0], (float) s0.startLocal[1], (float) s0.startLocal[2]);
        if (s1 != null && s1.startLocal != null && s1.startLocal.length >= 3) {
            Vector3f l1 = new Vector3f((float) s1.startLocal[0], (float) s1.startLocal[1], (float) s1.startLocal[2]);
            return l0.lerp(l1, alpha);
        }
        return l0;
    }

    /** Form-local point at index {@code i}, blended toward the next sample when available. */
    private static Vector3f blendLocal(Quaternionf invRot0, double[] a, double ax0, double ay0, double az0,
                                       Quaternionf invRot1, double[] b, double ax1, double ay1, double az1,
                                       int i, float alpha) {
        Vector3f l0 = local(invRot0, a[i] - ax0, a[i + 1] - ay0, a[i + 2] - az0);
        if (b == null) {
            return l0;
        }
        Vector3f l1 = local(invRot1, b[i] - ax1, b[i + 1] - ay1, b[i + 2] - az1);
        return l0.lerp(l1, alpha);
    }

    private static Vector3f local(Quaternionf invRot, double dx, double dy, double dz) {
        return invRot.transform(new Vector3f((float) dx, (float) dy, (float) dz));
    }

    private static Quaternionf quat(double[] pose) {
        return new Quaternionf((float) pose[3], (float) pose[4], (float) pose[5], (float) pose[6]);
    }

    private static String safeFile(StructureForm form) {
        try {
            return form.structureFile.get();
        } catch (Throwable t) {
            return null;
        }
    }

    /** "world:&lt;uuid&gt;.nbt" -> "&lt;uuid&gt;". */
    private static String extractUuid(String file) {
        if (file == null || file.isEmpty()) {
            return null;
        }
        int colon = file.indexOf(':');
        String s = colon >= 0 ? file.substring(colon + 1) : file;
        if (s.endsWith(".nbt")) {
            s = s.substring(0, s.length() - 4);
        }
        return s.isEmpty() ? null : s;
    }
}
