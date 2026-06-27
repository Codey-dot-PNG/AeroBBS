package com.aeronauticscml.bridge.client.bbs;

import com.aeronauticscml.bridge.mod.AeronauticsCmlBridge;

import mchorse.bbs_mod.events.BBSAddonMod;
import mchorse.bbs_mod.events.Subscribe;
import mchorse.bbs_mod.events.register.RegisterFormsRenderersEvent;
import mchorse.bbs_mod.forms.forms.StructureForm;

/**
 * BBS CML addon entrypoint (declared as {@code "bbs-addon-client"} in
 * fabric.mod.json). BBS discovers it, registers it on {@code BBSMod.events}, then
 * posts the register events - including {@link RegisterFormsRenderersEvent}.
 *
 * <p>We replace BBS's StructureForm renderer with {@link AeroStructureFormRenderer},
 * which draws the ship's Create kinetics on top of BBS's static render. Because BBS
 * runs form renderers in BOTH the editor preview and in-world playback, this makes
 * the kinetics render in the replay editor too (the WorldRenderEvents hook only fired
 * in-world).</p>
 */
public class AeroBBSAddon implements BBSAddonMod {
    @Subscribe
    public void onFormRenderers(RegisterFormsRenderersEvent event) {
        event.registerRenderer(StructureForm.class, AeroStructureFormRenderer::new);
        AeronauticsCmlBridge.LOGGER.info(
                "[aeronauticscml] Registered AeroStructureFormRenderer - ship kinetics will render in the BBS editor + in-world.");
    }
}
