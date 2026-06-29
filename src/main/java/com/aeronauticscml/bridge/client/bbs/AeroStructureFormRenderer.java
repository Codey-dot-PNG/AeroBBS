package com.aeronauticscml.bridge.client.bbs;

import com.aeronauticscml.bridge.client.KineticReplayRenderer;
import com.aeronauticscml.bridge.client.RopeReplayRenderer;
import com.aeronauticscml.bridge.mod.AeronauticsCmlBridge;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
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

    /**
     * Our OWN immediate buffer source - NOT Minecraft's shared one. The BBS editor renders
     * the 3D viewport as a UI element, batching into the shared bufferSource that GuiGraphics
     * also uses for panels/tooltips; calling endBatch() on it from inside render3D flushed
     * the UI's pending geometry at the wrong time (the "UI messes up on hover" glitch). A
     * dedicated buffer keeps our flush isolated. Lazily created on the render thread.
     */
    private static MultiBufferSource.BufferSource ourBuffers;

    private static MultiBufferSource.BufferSource buffers() {
        if (ourBuffers == null) {
            ourBuffers = MultiBufferSource.immediate(new ByteBufferBuilder(2048));
        }
        return ourBuffers;
    }

    public AeroStructureFormRenderer(StructureForm form) {
        super(form);
    }

    @Override
    protected void render3D(FormRenderingContext context) {
        super.render3D(context); // BBS's normal static structure render (unchanged)

        // Skip our extra geometry during BBS's PICKING pass (when the mouse hovers the
        // viewport, BBS re-renders forms into a stencil buffer to detect the hovered form).
        // Drawing kinetics/ropes through Minecraft's buffer source + endBatch() mid-stencil
        // corrupts that pass and visibly glitches the editor UI. BBS's own static render
        // (super.render3D) already handles picking correctly; we just stay out of it.
        if (context.isPicking()) {
            return;
        }

        try {
            PoseStack stack = stackOf(context);
            if (stack == null) {
                return;
            }
            MultiBufferSource.BufferSource buffers = buffers();
            float partial = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
            // BBS's per-form light (block<<4 | sky<<20), already day/night-aware - plain
            // int field, no reflection needed.
            int light = context.light;

            KineticReplayRenderer.renderInFormSpace(stack, buffers, this.getForm(), partial, light);

            // Ropes in the editor: BBS's actor entity carries the integer playback tick
            // (getAge) and the context the sub-tick partial (transition); together they give
            // the fractional playback tick. The rope renderer interpolates between recorded
            // snapshots at that fractional tick so the rope animates smoothly (not stepped at
            // the 20 Hz record rate), transformed into this form's local space.
            float ropeTick = (context.entity != null) ? context.entity.getAge() + context.transition : 0F;
            RopeReplayRenderer.renderInFormSpace(stack, buffers, this.getForm(), ropeTick, light);

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
