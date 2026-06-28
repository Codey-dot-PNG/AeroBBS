package com.aeronauticscml.bridge.client;

import com.aeronauticscml.bridge.config.BridgeConfig;
import com.aeronauticscml.bridge.mod.AeronauticsCmlBridge;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.film.BaseFilmController;
import mchorse.bbs_mod.film.Films;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.StructureForm;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.pose.Transform;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/**
 * Draws recorded ship markers + ropes during BBS film playback, in BBS's own
 * world render pass, with no Mixin on a BBS class.
 *
 * <p>Renders as SOLID TEXTURED geometry ({@code RenderType.entitySolid} on a white
 * texture) through {@code context.consumers()} - the buffer the world flushes and
 * that Iris/shaders composite (gbuffers_entities). The earlier {@code RenderType.lines()}
 * version worked without shaders but was dropped by Iris, exactly like BBS forms
 * would be if they used lines. See docs/RENDER_HOOK_INVESTIGATION.md.</p>
 */
public final class ReplayContraptionRenderer {
    private ReplayContraptionRenderer() {}

    private static final ResourceLocation WHITE = ResourceLocation.withDefaultNamespace("textures/misc/white.png");
    private static final int FULL_BRIGHT = LightTexture.FULL_BRIGHT;

    private static long lastReloadMs;
    private static long lastDiagMs;
    private static boolean lastEnabled;

    public static void onWorldRender(WorldRenderContext context) {
        long now = System.currentTimeMillis();

        if (now - lastReloadMs > 1000L) {
            lastReloadMs = now;
            BridgeConfig.reloadFromDisk();
        }

        boolean enabled = BridgeConfig.load().experimentalDynamicRender();
        if (enabled != lastEnabled) {
            lastEnabled = enabled;
            AeronauticsCmlBridge.LOGGER.info("[aeronauticscml] Dynamic render hook is now {}.", enabled ? "ENABLED" : "disabled");
        }
        if (!enabled) {
            return;
        }

        int controllerCount = 0, structureForms = 0, drawn = 0, ropesDrawn = 0;
        String sample = "";
        boolean consumersNull = false;

        try {
            Films films = BBSModClient.getFilms();
            List<BaseFilmController> controllers = films == null ? null : films.getControllers();

            PoseStack stack = context.matrixStack();
            Camera camera = context.camera();
            MultiBufferSource consumers = context.consumers();
            consumersNull = (consumers == null);

            if (controllers != null && !controllers.isEmpty() && stack != null && camera != null && consumers != null) {
                Vec3 cam = camera.getPosition();
                float partial = context.tickCounter().getGameTimeDeltaPartialTick(false);
                VertexConsumer vc = consumers.getBuffer(RenderType.entitySolid(WHITE));
                boolean showMarker = BridgeConfig.load().showDebugMarker();

                for (BaseFilmController controller : controllers) {
                    if (controller == null || controller.film == null) {
                        continue;
                    }
                    controllerCount++;

                    int baseTick = controller.getTick();
                    float td = controller.paused ? 0F : partial;

                    for (Replay replay : controller.film.replays.getList()) {
                        if (replay == null || !replay.enabled.get()) {
                            continue;
                        }
                        Form form = replay.form.get();
                        if (!(form instanceof StructureForm)) {
                            continue;
                        }
                        structureForms++;

                        float tick = replay.getTick(baseTick) + td;
                        Double xObj = replay.keyframes.x.interpolate(tick);
                        Double yObj = replay.keyframes.y.interpolate(tick);
                        Double zObj = replay.keyframes.z.interpolate(tick);
                        if (xObj == null || yObj == null || zObj == null) {
                            continue;
                        }

                        if (showMarker) {
                            Transform transform = interpolateTransform(replay, tick);
                            drawMarker(stack, vc, cam, xObj, yObj, zObj, transform);
                        }
                        drawn++;
                        // Ropes are keyed per ship uuid; draw this ship's ropes at its
                        // replay-local FRACTIONAL tick so they interpolate smoothly.
                        ropesDrawn += drawRopes(stack, consumers, cam, replay.uuid.get(), replay.getTick(baseTick) + td);

                        if (sample.isEmpty()) {
                            sample = String.format("  ship@(%.1f,%.1f,%.1f) cam@(%.1f,%.1f,%.1f)", xObj, yObj, zObj, cam.x, cam.y, cam.z);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            if (now - lastDiagMs > 1000L) {
                lastDiagMs = now;
                AeronauticsCmlBridge.LOGGER.warn("[aeronauticscml] Dynamic render hook error: {}", t.toString());
            }
            return;
        }

        if (now - lastDiagMs > 1000L) {
            lastDiagMs = now;
            AeronauticsCmlBridge.LOGGER.info(
                "[aeronauticscml] render hook: enabled, playingFilms={}, shipStructureForms={}, markersDrawn={}, ropesDrawn={}, consumersNull={}{}",
                controllerCount, structureForms, drawn, ropesDrawn, consumersNull, sample);
        }
    }

    private static Transform interpolateTransform(Replay replay, float tick) {
        try {
            KeyframeChannel<?> channel = replay.properties.properties.get("transform");
            if (channel == null || channel.isEmpty()) {
                return null;
            }
            Object value = channel.interpolate(tick);
            return value instanceof Transform ? (Transform) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void drawMarker(PoseStack stack, VertexConsumer vc, Vec3 cam,
                                   double worldX, double worldY, double worldZ, Transform transform) {
        stack.pushPose();
        stack.translate(worldX - cam.x, worldY - cam.y, worldZ - cam.z);

        // World-up mast (not rotated) - clears the hull into open sky.
        solidBox(stack, vc, -0.15F, 0F, -0.15F, 0.15F, 24F, 0.15F, 1F, 0F, 1F);
        solidBox(stack, vc, -0.4F, -0.4F, -0.4F, 0.4F, 0.4F, 0.4F, 1F, 1F, 1F);

        if (transform != null) {
            stack.mulPose(Axis.ZP.rotation(transform.rotate.z));
            stack.mulPose(Axis.YP.rotation(transform.rotate.y));
            stack.mulPose(Axis.XP.rotation(transform.rotate.x));
            stack.mulPose(Axis.ZP.rotation(transform.rotate2.z));
            stack.mulPose(Axis.YP.rotation(transform.rotate2.y));
            stack.mulPose(Axis.XP.rotation(transform.rotate2.x));
        }
        solidBox(stack, vc, -0.12F, -0.12F, 0F, 0.12F, 0.12F, 6F, 0.2F, 1F, 0.2F); // +Z forward (green)
        solidBox(stack, vc, 0F, -0.12F, -0.12F, 6F, 0.12F, 0.12F, 1F, 0.3F, 0.3F); // +X right (red)

        stack.popPose();
    }

    /**
     * Draw a ship's recorded rope polylines at a fractional playback tick, as textured
     * tubes, interpolating between the two bracketing recorded snapshots so the rope
     * animates smoothly instead of stepping at the record rate. Points are absolute world
     * coordinates (lerped), then offset by the camera.
     */
    private static int drawRopes(PoseStack stack, MultiBufferSource consumers, Vec3 cam, String key, float tickF) {
        RopeDataStore.Frame fr = RopeDataStore.frameAt(key, tickF);
        if (fr == null || fr.ropes0 == null || fr.ropes0.isEmpty()) {
            return 0;
        }
        boolean canBlend = fr.alpha > 0F && fr.ropes1 != null;
        float alpha = fr.alpha;
        VertexConsumer vc = consumers.getBuffer(RenderType.entityCutoutNoCull(RopeMesh.ROPE));

        int count = 0;
        for (int r = 0; r < fr.ropes0.size(); r++) {
            double[] a = fr.ropes0.get(r).points;
            if (a == null || a.length < 6) continue;
            float radius = (float) Math.max(0.04, Math.min(0.5, fr.ropes0.get(r).radius > 0 ? fr.ropes0.get(r).radius : 0.08));

            double[] b = null;
            if (canBlend && r < fr.ropes1.size()) {
                double[] cand = fr.ropes1.get(r).points;
                if (cand != null && cand.length == a.length) b = cand;
            }

            Vector3f prev = worldPoint(a, b, 0, alpha, cam);
            RopeMesh.node(stack, vc, prev, FULL_BRIGHT);
            for (int i = 3; i + 2 < a.length; i += 3) {
                Vector3f cur = worldPoint(a, b, i, alpha, cam);
                RopeMesh.tube(stack, vc, prev, cur, radius, FULL_BRIGHT);
                RopeMesh.node(stack, vc, cur, FULL_BRIGHT);
                prev = cur;
            }
            count++;
        }
        return count;
    }

    /** Absolute world point at index {@code i}, lerped toward the next sample, camera-relative. */
    private static Vector3f worldPoint(double[] a, double[] b, int i, float alpha, Vec3 cam) {
        double x = a[i], y = a[i + 1], z = a[i + 2];
        if (b != null) {
            x += (b[i] - x) * alpha;
            y += (b[i + 1] - y) * alpha;
            z += (b[i + 2] - z) * alpha;
        }
        return new Vector3f((float) (x - cam.x), (float) (y - cam.y), (float) (z - cam.z));
    }

    /** Render a solid axis-aligned box (6 faces) in the current pose, colored, full-bright. */
    private static void solidBox(PoseStack stack, VertexConsumer vc,
                                 float x0, float y0, float z0, float x1, float y1, float z1,
                                 float r, float g, float b) {
        PoseStack.Pose pose = stack.last();
        quad(vc, pose, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1, -1, 0, 0, r, g, b); // -X
        quad(vc, pose, x1, y0, z1, x1, y1, z1, x1, y1, z0, x1, y0, z0, 1, 0, 0, r, g, b);  // +X
        quad(vc, pose, x0, y0, z0, x0, y0, z1, x1, y0, z1, x1, y0, z0, 0, -1, 0, r, g, b); // -Y
        quad(vc, pose, x0, y1, z1, x0, y1, z0, x1, y1, z0, x1, y1, z1, 0, 1, 0, r, g, b);  // +Y
        quad(vc, pose, x1, y0, z0, x1, y1, z0, x0, y1, z0, x0, y0, z0, 0, 0, -1, r, g, b); // -Z
        quad(vc, pose, x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1, 0, 0, 1, r, g, b);  // +Z
    }

    private static void quad(VertexConsumer vc, PoseStack.Pose pose,
                             float x0, float y0, float z0, float x1, float y1, float z1,
                             float x2, float y2, float z2, float x3, float y3, float z3,
                             float nx, float ny, float nz, float r, float g, float b) {
        v(vc, pose, x0, y0, z0, nx, ny, nz, r, g, b);
        v(vc, pose, x1, y1, z1, nx, ny, nz, r, g, b);
        v(vc, pose, x2, y2, z2, nx, ny, nz, r, g, b);
        v(vc, pose, x3, y3, z3, nx, ny, nz, r, g, b);
    }

    private static void v(VertexConsumer vc, PoseStack.Pose pose, float x, float y, float z,
                          float nx, float ny, float nz, float r, float g, float b) {
        vc.addVertex(pose, x, y, z)
          .setColor(r, g, b, 1F)
          .setUv(0F, 0F)
          .setOverlay(OverlayTexture.NO_OVERLAY)
          .setLight(FULL_BRIGHT)
          .setNormal(pose, nx, ny, nz);
    }
}
