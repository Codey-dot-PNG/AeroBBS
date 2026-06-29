package com.aeronauticscml.bridge.client;

import com.aeronauticscml.bridge.config.BridgeConfig;
import com.aeronauticscml.bridge.mod.AeronauticsCmlBridge;

import com.mojang.blaze3d.vertex.PoseStack;

import mchorse.bbs_mod.forms.forms.StructureForm;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a recorded ship's Create kinetic block entities (spinning shafts/cogs and
 * Aeronautics propellers). Each kinetic BE is reconstructed from the snapshot NBT
 * (which carries its kinetic {@code Speed}) and drawn through its real
 * {@code BlockEntityRenderer} inside a {@link VirtualStructureLevel} - which Flywheel
 * does not manage, so the kinetic BER actually runs (see that class).
 *
 * <p>This is driven from {@code AeroStructureFormRenderer} (a BBS form renderer), so
 * it runs in BOTH the BBS editor preview and in-world playback. The PoseStack is
 * already in the ship form's local space when {@link #renderInFormSpace} is called, so
 * each BE is drawn at {@code (pos - pivot)} - landing on BBS's static blocks.</p>
 *
 * <p>Shafts/cogs animate for free from their recorded speed via Create's global render
 * clock. Propeller blades use a tick-accumulated angle that does not advance without
 * ticking, so we drive it ourselves from the BE's recorded {@code rotationSpeed}.</p>
 *
 * <p>Create and Aeronautics are NOT on our compile classpath, so their types are
 * resolved reflectively. No-op if Create isn't present.</p>
 */
public final class KineticReplayRenderer {
    private KineticReplayRenderer() {}

    // Create / Aeronautics types, resolved reflectively (not on our compile classpath).
    private static boolean reflectInit;
    private static Class<?> kineticBeClass;        // com.simibubi.create...KineticBlockEntity
    private static Class<?> propellerBeClass;      // dev.eriksonn...BasePropellerBlockEntity
    private static Field propRotationSpeed;        // public float rotationSpeed
    private static Method propSetAngle;            // void setAngle(float)
    private static Method propSetPreviousAngle;    // void setPreviousAngle(float)
    private static Class<?> propBearingBeClass;    // dev.eriksonn...PropellerBearingBlockEntity (the big propeller bearing)
    private static Method propBearingGetRotationSpeed; // public float getRotationSpeed()
    private static Field bearingAngleField;         // MechanicalBearingBlockEntity.angle (protected base field)
    private static Field bearingPrevAngleField;     // PropellerBearingBlockEntity.prevAngle (public)

    // Camo blocks (Copycats+/Extra/Aero, FramedBlocks, Create copycats): no BER - they render
    // via the block model + the block-entity's NeoForge model data, so BBS's model-data-less
    // static mesh shows them empty. We draw the model with model data ourselves (reflective,
    // since the NeoForge model-data API is not on our Fabric compile classpath).
    private static boolean camoReflectTried;
    private static Class<?> modelDataClass;          // net.neoforged.neoforge.client.model.data.ModelData
    private static Method mGetModelData;             // BlockEntity.getModelData() -> ModelData
    private static Method mRenderSingleBlockNF;      // BlockRenderDispatcher.renderSingleBlock(state,ps,buf,light,overlay,ModelData,RenderType)
    private static final java.util.Set<String> CAMO_NS =
            java.util.Set.of("copycats", "extra_copycats", "aerocopycats", "framedblocks", "create");

    private static long lastDiagMs;
    private static long lastErrMs;

    private static void diag(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastErrMs > 1000L) {
            lastErrMs = now;
            AeronauticsCmlBridge.LOGGER.warn("[aeronauticscml] kinetic render: {}", msg);
        }
    }

    private static void initReflection() {
        if (reflectInit) {
            return;
        }
        reflectInit = true;
        try {
            kineticBeClass = Class.forName("com.simibubi.create.content.kinetics.base.KineticBlockEntity");
        } catch (Throwable t) {
            AeronauticsCmlBridge.LOGGER.warn("[aeronauticscml] Create KineticBlockEntity not found; kinetic render unavailable: {}", t.toString());
        }
        try {
            propellerBeClass = Class.forName("dev.eriksonn.aeronautics.content.blocks.propeller.small.BasePropellerBlockEntity");
            propRotationSpeed = propellerBeClass.getField("rotationSpeed");
            propSetAngle = propellerBeClass.getMethod("setAngle", float.class);
            propSetPreviousAngle = propellerBeClass.getMethod("setPreviousAngle", float.class);
        } catch (Throwable t) {
            // Aeronautics propellers are optional; shafts/cogs still work without them.
            propellerBeClass = null;
        }
        try {
            // The big propeller BEARING's rotating "top"/second half: its renderer reads
            // getInterpolatedAngle, which - with no live contraption entity (our reconstructed
            // BE has none) - just returns the `angle` field, so advancing that field spins it.
            propBearingBeClass = Class.forName("dev.eriksonn.aeronautics.content.blocks.propeller.bearing.propeller_bearing.PropellerBearingBlockEntity");
            propBearingGetRotationSpeed = propBearingBeClass.getMethod("getRotationSpeed");
            bearingAngleField = findField(propBearingBeClass, "angle");       // on MechanicalBearingBlockEntity (base)
            bearingPrevAngleField = findField(propBearingBeClass, "prevAngle"); // on PropellerBearingBlockEntity
        } catch (Throwable t) {
            propBearingBeClass = null;
        }
    }

    /** Find a (possibly inherited / non-public) field by walking up the class hierarchy. */
    private static Field findField(Class<?> start, String name) {
        for (Class<?> c = start; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        return null;
    }

    private static double wrap360(double v) {
        return ((v % 360.0) + 360.0) % 360.0;
    }

    private static final class Placed {
        final BlockPos pos;
        final BlockEntity be;

        Placed(BlockPos pos, BlockEntity be) {
            this.pos = pos;
            this.be = be;
        }
    }

    private static final class Model {
        ClientLevel level;
        StructureNbtStore.Parsed builtFrom;
        VirtualStructureLevel virtual;
        final List<Placed> placed = new ArrayList<>();
        final List<Placed> camoPlaced = new ArrayList<>(); // Copycats/FramedBlocks (model-data render)
        double pivotX;
        double pivotY;
        double pivotZ;
    }

    private static final Map<String, Model> MODELS = new HashMap<>();

    /**
     * Draw the kinetic BEs for one ship StructureForm in the CURRENT PoseStack space
     * (BBS has already applied the form's transform). Returns the number drawn.
     */
    public static int renderInFormSpace(PoseStack stack, MultiBufferSource consumers, StructureForm form, float partial, int light) {
        boolean kineticOn = BridgeConfig.load().experimentalKineticRender();
        boolean camoOn = BridgeConfig.load().renderCamoBlocks();
        if (!kineticOn && !camoOn) {
            return 0;
        }
        initReflection();

        Minecraft mc = Minecraft.getInstance();
        ClientLevel client = mc.level;
        if (client == null) {
            return 0;
        }

        String key;
        try {
            key = form.structureFile.get();
        } catch (Throwable t) {
            return 0;
        }
        if (key == null || key.isEmpty()) {
            return 0;
        }

        Model model = ensureModel(key, form, client);
        if (model == null) {
            return 0;
        }

        // Use BBS's computed form light so our blocks match the static hull (day/night).
        model.virtual.setCurrentLight(light);

        long gameTime = client.getGameTime();
        int drawn = 0;

        // Create kinetics (+ optionally any BER block): drawn through their BlockEntityRenderer.
        if (kineticOn && kineticBeClass != null && !model.placed.isEmpty()) {
            BlockEntityRenderDispatcher dispatcher = mc.getBlockEntityRenderDispatcher();
            double scale = BridgeConfig.load().propellerSpeedScale();
            for (Placed p : model.placed) {
                drivePropeller(p.be, scale, gameTime, partial);
                BlockEntityRenderer<BlockEntity> renderer = dispatcher.getRenderer(p.be);
                if (renderer == null) {
                    diag("no BlockEntityRenderer for " + p.be.getClass().getName());
                    continue;
                }
                // Isolated PoseStack so a foreign/buggy BER that leaks a push can NEVER unbalance
                // the shared world PoseStack ("Pose stack not empty" frame crash).
                try {
                    PoseStack isolated = copyStack(stack);
                    isolated.translate(p.pos.getX() - model.pivotX, p.pos.getY() - model.pivotY, p.pos.getZ() - model.pivotZ);
                    renderer.render(p.be, partial, isolated, consumers, light, OverlayTexture.NO_OVERLAY);
                    drawn++;
                } catch (Throwable t) {
                    diag("render failed for " + p.be.getClass().getSimpleName() + ": " + t);
                }
            }
        }

        // Camo blocks (Copycats/FramedBlocks): draw the block model with the BE's model data.
        if (camoOn && !model.camoPlaced.isEmpty()) {
            drawn += renderCamo(stack, consumers, model, light);
        }

        long now = System.currentTimeMillis();
        if (drawn > 0 && now - lastDiagMs > 1000L) {
            lastDiagMs = now;
            AeronauticsCmlBridge.LOGGER.info("[aeronauticscml] form render: {} dynamic block(s) for '{}'", drawn, key);
        }
        return drawn;
    }

    /** A fresh PoseStack carrying the current transform, so foreign renderers can't touch the shared one. */
    private static PoseStack copyStack(PoseStack src) {
        PoseStack out = new PoseStack();
        out.last().pose().set(src.last().pose());
        out.last().normal().set(src.last().normal());
        return out;
    }

    /**
     * Draw camo blocks (Copycats/FramedBlocks) via their block model + the block-entity's
     * NeoForge model data (reflective). BBS renders them empty (no model data), so this is what
     * makes the camo material show, while keeping the block's shape. renderType=null -> all layers.
     */
    private static int renderCamo(PoseStack stack, MultiBufferSource consumers, Model model, int light) {
        initCamoReflection();
        if (mRenderSingleBlockNF == null || mGetModelData == null) {
            return 0;
        }
        net.minecraft.client.renderer.block.BlockRenderDispatcher blockRenderer = Minecraft.getInstance().getBlockRenderer();
        int drawn = 0;
        for (Placed p : model.camoPlaced) {
            try {
                Object md = mGetModelData.invoke(p.be);
                if (md == null) {
                    continue;
                }
                PoseStack isolated = copyStack(stack);
                isolated.translate(p.pos.getX() - model.pivotX, p.pos.getY() - model.pivotY, p.pos.getZ() - model.pivotZ);
                mRenderSingleBlockNF.invoke(blockRenderer, p.be.getBlockState(), isolated, consumers,
                        light, OverlayTexture.NO_OVERLAY, md, null);
                drawn++;
            } catch (Throwable t) {
                diag("camo render failed for " + p.be.getClass().getSimpleName() + ": " + t);
            }
        }
        return drawn;
    }

    private static void initCamoReflection() {
        if (camoReflectTried) {
            return;
        }
        camoReflectTried = true;
        try {
            modelDataClass = Class.forName("net.neoforged.neoforge.client.model.data.ModelData");
            mGetModelData = BlockEntity.class.getMethod("getModelData");
            mRenderSingleBlockNF = net.minecraft.client.renderer.block.BlockRenderDispatcher.class.getMethod(
                    "renderSingleBlock",
                    BlockState.class,
                    PoseStack.class,
                    MultiBufferSource.class,
                    int.class, int.class,
                    modelDataClass,
                    net.minecraft.client.renderer.RenderType.class);
        } catch (Throwable t) {
            modelDataClass = null;
            mGetModelData = null;
            mRenderSingleBlockNF = null;
            AeronauticsCmlBridge.LOGGER.warn("[aeronauticscml] camo block render unavailable (NeoForge model-data API not found): {}", t.toString());
        }
    }

    private static boolean isCamoBlock(BlockState state) {
        try {
            return CAMO_NS.contains(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Advance an Aeronautics propeller blade angle from its recorded rotationSpeed.
     * The angle is normally tick-accumulated (frozen without ticking), so we set both
     * the previous and current angle to a continuous value derived from the render
     * clock; the renderer's interpolation then reads a smooth, advancing angle.
     */
    private static void drivePropeller(BlockEntity be, double scale, long gameTime, float partial) {
        // Small single-block propellers: advance the blade angle from recorded rotationSpeed.
        if (propellerBeClass != null && propellerBeClass.isInstance(be)) {
            try {
                float rotationSpeed = propRotationSpeed.getFloat(be);
                double angle = wrap360(rotationSpeed * scale * (gameTime + partial));
                propSetPreviousAngle.invoke(be, (float) angle);
                propSetAngle.invoke(be, (float) angle);
            } catch (Throwable ignored) {
                // leave the blade at whatever angle it has
            }
            return;
        }

        // Big propeller BEARING: spin its rotating "top" / second half. The blade contraption
        // (a separate captured form) already spins via keyframes, but the bearing block sits in
        // the ship snapshot and its top is BER-driven. With no live contraption entity here,
        // getInterpolatedAngle returns the `angle` field, so we advance it from the recorded
        // rotationSpeed (deg/tick) - matching the blades' rate (no propellerSpeedScale, since the
        // big-prop blades use the exact recorded angle, not the scaled approximation).
        if (propBearingBeClass != null && propBearingBeClass.isInstance(be)) {
            try {
                float rotationSpeed = (float) propBearingGetRotationSpeed.invoke(be);
                double angle = wrap360(rotationSpeed * (gameTime + partial));
                if (bearingAngleField != null) bearingAngleField.setFloat(be, (float) angle);
                if (bearingPrevAngleField != null) bearingPrevAngleField.setFloat(be, (float) angle);
            } catch (Throwable ignored) {
                // leave the bearing top at whatever angle it has
            }
        }
    }

    private static Model ensureModel(String key, StructureForm form, ClientLevel client) {
        StructureNbtStore.Parsed parsed = StructureNbtStore.get(form);
        if (parsed == null) {
            MODELS.remove(key);
            return null;
        }

        Model existing = MODELS.get(key);
        // Reuse unless the parse changed (re-record -> new Parsed instance) or the
        // client level changed (cached virtual level's delegate would be stale).
        if (existing != null && existing.level == client && existing.builtFrom == parsed) {
            return existing;
        }

        Model m = new Model();
        m.level = client;
        m.builtFrom = parsed;
        m.pivotX = parsed.pivotX;
        m.pivotY = parsed.pivotY;
        m.pivotZ = parsed.pivotZ;

        Map<BlockPos, BlockState> stateMap = new HashMap<>();
        Map<BlockPos, BlockEntity> beMap = new HashMap<>();
        for (StructureNbtStore.BlockEntry e : parsed.blocks) {
            stateMap.put(e.pos, e.state);
        }
        // The virtual level shares these maps; BEs added below become visible to it
        // (so a kinetic renderer that queries neighbour BEs sees the whole ship).
        VirtualStructureLevel virtual = new VirtualStructureLevel(client, stateMap, beMap);
        m.virtual = virtual;

        HolderLookup.Provider registries = client.registryAccess();
        // Optionally also draw NON-kinetic BEs that have a renderer (Burnt's Basic flags,
        // chests, signs, ...) so their BER parts show, not just the static model.
        boolean allBEs = BridgeConfig.load().renderAllBlockEntities();
        BlockEntityRenderDispatcher beDispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
        int kineticCount = 0;
        int propellerCount = 0;
        int camoCount = 0;
        int beWithNbt = 0;
        for (StructureNbtStore.BlockEntry e : parsed.blocks) {
            if (e.beNbt == null) {
                continue;
            }
            beWithNbt++;
            try {
                boolean camo = isCamoBlock(e.state); // Copycats/FramedBlocks - rendered via model data
                BlockEntity be = BlockEntity.loadStatic(e.pos, e.state, e.beNbt, registries);
                if (be == null) {
                    continue;
                }
                boolean kinetic = kineticBeClass != null && kineticBeClass.isInstance(be);
                boolean wantBER = kinetic || allBEs;
                if (!wantBER && !camo) {
                    continue;
                }
                be.setLevel(virtual);
                beMap.put(e.pos, be);
                if (camo) {
                    m.camoPlaced.add(new Placed(e.pos, be));
                    camoCount++;
                }
                if (wantBER && (kinetic || beDispatcher.getRenderer(be) != null)) {
                    m.placed.add(new Placed(e.pos, be));
                    kineticCount++;
                    if (propellerBeClass != null && propellerBeClass.isInstance(be)) {
                        propellerCount++;
                    }
                }
            } catch (Throwable ignored) {
                // skip this BE
            }
        }

        MODELS.put(key, m);
        AeronauticsCmlBridge.LOGGER.info(
                "[aeronauticscml] Built render model '{}': {} kinetic BEs ({} propellers), {} camo blocks, of {} BEs / {} blocks",
                key, kineticCount, propellerCount, camoCount, beWithNbt, parsed.blocks.size());
        return m;
    }
}
