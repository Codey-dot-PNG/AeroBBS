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
 * then draw a textured tube ({@link RopeMesh}) so it reads like real cord.
 */
public final class RopeReplayRenderer {
    private RopeReplayRenderer() {}

    public static int renderInFormSpace(PoseStack stack, MultiBufferSource consumers, StructureForm form, int tick, int light) {
        if (!BridgeConfig.load().experimentalDynamicRender()) {
            return 0;
        }
        String uuid = extractUuid(safeFile(form));
        if (uuid == null) {
            return 0;
        }
        List<RopeDataStore.RopeSnap> ropes = RopeDataStore.ropesAt(uuid, tick);
        double[] pose = RopeDataStore.poseAt(uuid, tick);
        if (ropes == null || ropes.isEmpty() || pose == null) {
            return 0;
        }

        // form-local = inverse(orientation) * (worldPoint - anchorPos)
        Quaternionf invRot = new Quaternionf((float) pose[3], (float) pose[4], (float) pose[5], (float) pose[6]).invert();
        double ax = pose[0], ay = pose[1], az = pose[2];
        VertexConsumer vc = consumers.getBuffer(RenderType.entityCutoutNoCull(RopeMesh.ROPE));

        int count = 0;
        for (RopeDataStore.RopeSnap rope : ropes) {
            double[] p = rope.points;
            if (p == null || p.length < 6) {
                continue;
            }
            float radius = (float) Math.max(0.04, Math.min(0.5, rope.radius > 0 ? rope.radius : 0.08));
            Vector3f prev = toLocal(invRot, p[0] - ax, p[1] - ay, p[2] - az);
            RopeMesh.node(stack, vc, prev, light);
            for (int i = 3; i + 2 < p.length; i += 3) {
                Vector3f cur = toLocal(invRot, p[i] - ax, p[i + 1] - ay, p[i + 2] - az);
                RopeMesh.tube(stack, vc, prev, cur, radius, light);
                RopeMesh.node(stack, vc, cur, light);
                prev = cur;
            }
            count++;
        }
        return count;
    }

    private static Vector3f toLocal(Quaternionf invRot, double dx, double dy, double dz) {
        return invRot.transform(new Vector3f((float) dx, (float) dy, (float) dz));
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
