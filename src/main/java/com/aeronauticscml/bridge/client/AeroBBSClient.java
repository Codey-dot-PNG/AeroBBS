package com.aeronauticscml.bridge.client;

import com.aeronauticscml.bridge.config.BridgeConfig;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

/**
 * Client entrypoint. Registers the non-Mixin world-render hook used to draw
 * recorded ship replays during BBS film playback.
 *
 * <p>This is the foundation of the "render-hook unlock" (see
 * docs/RENDER_HOOK_INVESTIGATION.md): BBS renders films inside the vanilla world
 * render pass via a Fabric {@code WorldRenderContext}, so we can register our own
 * {@link WorldRenderEvents} callback in the same pass and draw aligned with how
 * BBS draws the static ship - without any Mixin on a BBS class.</p>
 *
 * <p>It is entirely opt-in ({@code experimentalDynamicRender} in the config,
 * default off) and only ever <em>adds</em> rendering during playback; it never
 * touches recording.</p>
 */
public final class AeroBBSClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Load the config at client launch (the server-side initialize() only runs
        // when a world's integrated server starts, which left the client hook reading
        // defaults). The hook also re-reads the file live so toggling works without a restart.
        BridgeConfig.initialize();

        // AFTER_ENTITIES: context.consumers() (the world's shared, shader-composited
        // buffer source) is non-null here and flushed by the renderer afterwards, so
        // our lines draw the same way BBS's forms do - including under Iris shaders.
        WorldRenderEvents.AFTER_ENTITIES.register(ReplayContraptionRenderer::onWorldRender);
    }
}
