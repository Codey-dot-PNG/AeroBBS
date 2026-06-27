package com.aeronauticscml.bridge.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

import org.joml.Vector3f;

/**
 * Shared geometry for Aeronautics-style ropes: a textured RECTANGULAR box for each
 * polyline segment ({@link #tube}) plus a small 4x4 textured cube at every node
 * ({@link #node}) - matching the launched rope ("every node is connected by a small
 * cube"). Uses the user's real {@code launched_rope.png}.
 *
 * <p>Shared by the in-world hook ({@link ReplayContraptionRenderer}) and the BBS editor
 * path ({@link RopeReplayRenderer}); the caller supplies points in its PoseStack space.</p>
 */
public final class RopeMesh {
    private RopeMesh() {}

    public static final ResourceLocation ROPE = ResourceLocation.fromNamespaceAndPath("aeronauticscml", "textures/launched_rope.png");

    private static final int SIDES = 4;                       // rectangular cross-section
    private static final float PHASE = (float) (Math.PI / 4); // corners -> flat faces (axis-aligned box)
    private static final int NO = OverlayTexture.NO_OVERLAY;

    /** The node is a fixed 4x4(x4) px cube; its texture samples a native 4x4 region. */
    private static final float NODE_HALF = 2F / 16F;          // 4 px across
    private static final float NODE_UV = 4F / 16F;            // 4 texels

    /** A textured rectangular box between {@code a} and {@code b} (V along length). */
    public static void tube(PoseStack stack, VertexConsumer vc, Vector3f a, Vector3f b, float radius, int light) {
        float dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-4f) {
            return;
        }
        float ax = dx / len, ay = dy / len, az = dz / len;
        Vector3f u = perpU(ax, ay, az);
        Vector3f w = new Vector3f(ay * u.z - az * u.y, az * u.x - ax * u.z, ax * u.y - ay * u.x).normalize();

        PoseStack.Pose pose = stack.last();
        for (int i = 0; i < SIDES; i++) {
            Vector3f o0 = ring(u, w, PHASE + (2 * Math.PI * i) / SIDES);
            Vector3f o1 = ring(u, w, PHASE + (2 * Math.PI * (i + 1)) / SIDES);
            float uu0 = (float) i / SIDES;
            float uu1 = (float) (i + 1) / SIDES;
            emit(vc, pose, a.x + o0.x * radius, a.y + o0.y * radius, a.z + o0.z * radius, uu0, 0F, o0, light);
            emit(vc, pose, a.x + o1.x * radius, a.y + o1.y * radius, a.z + o1.z * radius, uu1, 0F, o1, light);
            emit(vc, pose, b.x + o1.x * radius, b.y + o1.y * radius, b.z + o1.z * radius, uu1, 1F, o1, light);
            emit(vc, pose, b.x + o0.x * radius, b.y + o0.y * radius, b.z + o0.z * radius, uu0, 1F, o0, light);
        }
    }

    /** A small 4x4 textured cube centred at a rope node. */
    public static void node(PoseStack stack, VertexConsumer vc, Vector3f at, int light) {
        PoseStack.Pose pose = stack.last();
        float h = NODE_HALF;
        float x0 = at.x - h, y0 = at.y - h, z0 = at.z - h;
        float x1 = at.x + h, y1 = at.y + h, z1 = at.z + h;
        face(vc, pose, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0, 0, 1, light);   // +Z
        face(vc, pose, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, 0, 0, -1, light);  // -Z
        face(vc, pose, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, 1, 0, 0, light);   // +X
        face(vc, pose, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, -1, 0, 0, light);  // -X
        face(vc, pose, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, 0, 1, 0, light);   // +Y
        face(vc, pose, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0, -1, 0, light);  // -Y
    }

    private static void face(VertexConsumer vc, PoseStack.Pose pose,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             float nx, float ny, float nz, int light) {
        Vector3f n = new Vector3f(nx, ny, nz);
        emit(vc, pose, ax, ay, az, 0F, 0F, n, light);
        emit(vc, pose, bx, by, bz, NODE_UV, 0F, n, light);
        emit(vc, pose, cx, cy, cz, NODE_UV, NODE_UV, n, light);
        emit(vc, pose, dx, dy, dz, 0F, NODE_UV, n, light);
    }

    private static Vector3f ring(Vector3f u, Vector3f w, double t) {
        float c = (float) Math.cos(t);
        float s = (float) Math.sin(t);
        return new Vector3f(u.x * c + w.x * s, u.y * c + w.y * s, u.z * c + w.z * s);
    }

    private static void emit(VertexConsumer vc, PoseStack.Pose pose, float x, float y, float z,
                             float uu, float vv, Vector3f normal, int light) {
        vc.addVertex(pose, x, y, z)
          .setColor(1F, 1F, 1F, 1F)
          .setUv(uu, vv)
          .setOverlay(NO)
          .setLight(light)
          .setNormal(pose, normal.x, normal.y, normal.z);
    }

    private static Vector3f perpU(float ax, float ay, float az) {
        float rx, ry, rz;
        if (Math.abs(ay) < 0.99f) {
            rx = 0; ry = 1; rz = 0;
        } else {
            rx = 1; ry = 0; rz = 0;
        }
        return new Vector3f(ay * rz - az * ry, az * rx - ax * rz, ax * ry - ay * rx).normalize();
    }
}
