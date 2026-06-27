package com.aeronauticscml.bridge.client.bbs;

import com.aeronauticscml.bridge.client.KineticReplayRenderer;
import com.aeronauticscml.bridge.client.RopeReplayRenderer;
import com.aeronauticscml.bridge.mod.AeronauticsCmlBridge;

import com.mojang.blaze3d.vertex.PoseStack;

import mchorse.bbs_mod.forms.forms.StructureForm;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.forms.renderers.StructureFormRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;

import java.lang.reflect.Field;

/**
 * Replaces BBS's {@code StructureFormRenderer} with one that, after BBS's normal
 * static structure render, draws the ship's Create kinetic block entities (spinning
 * shafts/cogs + Aeronautics propellers) in the form's own local space.
 *
 * <p>Because BBS calls form renderers in BOTH the editor preview and in-world
 * playback, this renders the kinetics in the replay editor too - unlike the
 * {@code WorldRenderEvents} hook, which only fires in-world.</p>
 *
 * <p>The BBS jar uses intermediary MC names ({@code class_4587}) that aren't on our
 * mojmap classpath, so {@link FormRenderingContext#stack} is reached reflectively; at
 * runtime (under Sinytra) it is a mojmap {@link PoseStack}. {@code render3D} is
 * entered with the stack already in the form's local/transformed space, so kinetics
 * drawn at {@code (pos - pivot)} land exactly on BBS's static blocks.</p>
 */
public class AeroStructureFormRenderer extends StructureFormRenderer {
    private static Field stackField;

    public AeroStructureFormRenderer(StructureForm form) {
        super(form);
    }

    @Override
    protected void render3D(FormRenderingContext context) {
        super.render3D(context); // BBS's normal static structure render (unchanged)

        try {
            PoseStack stack = stackOf(context);
            if (stack == null) {
                return;
            }
            MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
            float partial = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
            // BBS's per-form light (block<<4 | sky<<20), already day/night-aware - plain
            // int field, no reflection needed.
            int light = context.light;

            KineticReplayRenderer.renderInFormSpace(stack, buffers, this.getForm(), partial, light);

            // Ropes in the editor: BBS's actor entity carries the playback tick (getAge);
            // the rope renderer transforms the recorded absolute polyline into this form's
            // local space using the per-tick anchor pose stored at record time.
            int tick = (context.entity != null) ? context.entity.getAge() : 0;
            RopeReplayRenderer.renderInFormSpace(stack, buffers, this.getForm(), tick, light);

            buffers.endBatch();
        } catch (Throwable t) {
            AeronauticsCmlBridge.LOGGER.debug("[aeronauticscml] kinetic form render error: {}", t.toString());
        }
    }

    /** Reflectively read the intermediary-typed {@code stack} field as a mojmap PoseStack. */
    private static PoseStack stackOf(FormRenderingContext context) throws Exception {
        Field f = stackField;
        if (f == null) {
            f = FormRenderingContext.class.getField("stack");
            stackField = f;
        }
        Object stack = f.get(context);
        return (stack instanceof PoseStack) ? (PoseStack) stack : null;
    }
}
