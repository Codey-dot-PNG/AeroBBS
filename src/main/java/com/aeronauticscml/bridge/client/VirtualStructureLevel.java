package com.aeronauticscml.bridge.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Scoreboard;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A throwaway {@link Level} that exposes a recorded ship's snapshot blocks/BEs to
 * Create's kinetic {@code BlockEntityRenderer}s WITHOUT being managed by Flywheel.
 *
 * <p>Why this exists: Create's {@code KineticBlockEntityRenderer.renderSafe()}
 * CPU-draws the spinning geometry, but it early-returns when
 * {@code VisualizationManager.supportsVisualization(be.getLevel())} is true - which
 * it is for the real client world (Flywheel owns it). BBS renders ship BEs against
 * the real world, so the kinetic renderer no-ops ("shafts don't render"). By giving
 * the BE THIS level instead - which Flywheel does not track -
 * {@code supportsVisualization} returns false and the BER renders + animates from the
 * BE's snapshot speed.</p>
 *
 * <p>Everything heavy (chunk source, light engine, scoreboard, recipes, ...)
 * delegates to the real {@link ClientLevel}; only block/BE lookups are served from
 * the snapshot, and light is forced full-bright. It is never ticked or mutated.</p>
 */
public final class VirtualStructureLevel extends Level {
    private final ClientLevel real;
    private final Map<BlockPos, BlockState> states;
    private final Map<BlockPos, BlockEntity> blockEntities;

    /** Packed lightmap coord BBS computed for the form this frame (block&lt;&lt;4 | sky&lt;&lt;20). */
    private int currentLight = 0xF000F0; // full-bright until set

    /** Set the light the kinetics should use this frame (from FormRenderingContext.light). */
    public void setCurrentLight(int packedLight) {
        this.currentLight = packedLight;
    }

    public VirtualStructureLevel(ClientLevel real,
                                 Map<BlockPos, BlockState> states,
                                 Map<BlockPos, BlockEntity> blockEntities) {
        super(real.getLevelData(), real.dimension(), real.registryAccess(),
                real.dimensionTypeRegistration(), real.getProfilerSupplier(),
                true /* isClientSide */, false /* isDebug */, 0L, 1_000_000);
        this.real = real;
        this.states = states;
        this.blockEntities = blockEntities;
    }

    public ClientLevel real() {
        return this.real;
    }

    // --- snapshot-backed lookups ---------------------------------------------------

    @Override
    public BlockState getBlockState(BlockPos pos) {
        BlockState s = this.states.get(pos);
        return s != null ? s : Blocks.AIR.defaultBlockState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return this.blockEntities.get(pos);
    }

    // --- light: mirror BBS's computed form light so kinetics match the hull --------
    // (block light = bits 4..7, sky light = bits 20..23 of the packed lightmap coord)

    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
        return layer == LightLayer.SKY ? ((this.currentLight >> 20) & 0xF) : ((this.currentLight >> 4) & 0xF);
    }

    @Override
    public int getRawBrightness(BlockPos pos, int ambientDarkening) {
        int block = (this.currentLight >> 4) & 0xF;
        int sky = (this.currentLight >> 20) & 0xF;
        return Math.max(block, sky - ambientDarkening);
    }

    // --- delegate the expensive machinery to the real client level -----------------

    public ChunkSource getChunkSource() {
        return this.real.getChunkSource();
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return this.real.getLightEngine();
    }

    @Override
    public TickRateManager tickRateManager() {
        return this.real.tickRateManager();
    }

    @Override
    public Scoreboard getScoreboard() {
        return this.real.getScoreboard();
    }

    @Override
    public RecipeManager getRecipeManager() {
        return this.real.getRecipeManager();
    }

    @Override
    public PotionBrewing potionBrewing() {
        return this.real.potionBrewing();
    }

    // --- harmless stubs for the rest of Level's abstract surface -------------------

    @Override
    protected LevelEntityGetter<Entity> getEntities() {
        return EMPTY_ENTITY_GETTER;
    }

    @Override
    public Entity getEntity(int id) {
        return null;
    }

    @Override
    public MapItemSavedData getMapData(MapId id) {
        return null;
    }

    @Override
    public void setMapData(MapId id, MapItemSavedData data) {
    }

    @Override
    public MapId getFreeMapId() {
        return null;
    }

    @Override
    public void destroyBlockProgress(int breakerId, BlockPos pos, int progress) {
    }

    @Override
    public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags) {
    }

    @Override
    public void playSeededSound(Player player, double x, double y, double z, Holder<SoundEvent> sound,
                               SoundSource source, float volume, float pitch, long seed) {
    }

    @Override
    public void playSeededSound(Player player, Entity entity, Holder<SoundEvent> sound,
                               SoundSource source, float volume, float pitch, long seed) {
    }

    @Override
    public String gatherChunkSourceStats() {
        return "";
    }

    @Override
    public void gameEvent(Holder<GameEvent> gameEvent, Vec3 position, GameEvent.Context context) {
    }

    @Override
    public void levelEvent(Player player, int type, BlockPos pos, int data) {
    }

    @Override
    public void addParticle(net.minecraft.core.particles.ParticleOptions options, double x, double y, double z,
                            double xSpeed, double ySpeed, double zSpeed) {
    }

    @Override
    public void playSound(Player player, BlockPos pos, SoundEvent sound, SoundSource source, float volume, float pitch) {
    }

    @Override
    public long nextSubTickCount() {
        return this.real.nextSubTickCount();
    }

    @Override
    public net.minecraft.world.ticks.LevelTickAccess<net.minecraft.world.level.block.Block> getBlockTicks() {
        return this.real.getBlockTicks();
    }

    @Override
    public net.minecraft.world.ticks.LevelTickAccess<net.minecraft.world.level.material.Fluid> getFluidTicks() {
        return this.real.getFluidTicks();
    }

    @Override
    public net.minecraft.world.DifficultyInstance getCurrentDifficultyAt(BlockPos pos) {
        return this.real.getCurrentDifficultyAt(pos);
    }

    @Override
    public net.minecraft.server.MinecraftServer getServer() {
        return this.real.getServer();
    }

    @Override
    public net.minecraft.util.RandomSource getRandom() {
        return this.real.getRandom();
    }

    @Override
    public java.util.List<? extends Player> players() {
        return Collections.emptyList();
    }

    @Override
    public net.minecraft.world.flag.FeatureFlagSet enabledFeatures() {
        return this.real.enabledFeatures();
    }

    @Override
    public net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> getUncachedNoiseBiome(int x, int y, int z) {
        return this.real.getUncachedNoiseBiome(x, y, z);
    }

    @Override
    public float getShade(net.minecraft.core.Direction direction, boolean shade) {
        return this.real.getShade(direction, shade);
    }

    @Override
    public int getBlockTint(BlockPos pos, net.minecraft.world.level.ColorResolver resolver) {
        return this.real.getBlockTint(pos, resolver);
    }

    private static final LevelEntityGetter<Entity> EMPTY_ENTITY_GETTER = new LevelEntityGetter<>() {
        @Override
        public Entity get(int id) {
            return null;
        }

        @Override
        public Entity get(UUID uuid) {
            return null;
        }

        @Override
        public Iterable<Entity> getAll() {
            return Collections.emptyList();
        }

        @Override
        public <U extends Entity> void get(EntityTypeTest<Entity, U> test, AbortableIterationConsumer<U> consumer) {
        }

        @Override
        public void get(AABB box, Consumer<Entity> consumer) {
        }

        @Override
        public <U extends Entity> void get(EntityTypeTest<Entity, U> test, AABB box, AbortableIterationConsumer<U> consumer) {
        }
    };
}
