package com.aeronauticscml.bridge.aeronautics;

import com.aeronauticscml.bridge.mod.AeronauticsCmlBridge;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import com.aeronauticscml.bridge.config.BridgeConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Saves a ship's blocks as a vanilla .nbt structure file.
 *
 * Files are saved to <world>/generated/minecraft/structures/ so BBS CML's
 * WorldStructuresSourcePack can find them via the "world:" prefix.
 *
 * Uses Sable's plot bounds directly. These bounds are already expressed in
 * the embedded plot world's block coordinates, so adding the plot chunk origin
 * again would point the snapshot at unrelated faraway chunks.
 * This handles ships of any size correctly.
 */
public final class StructureSnapshotService {

    /**
     * Result of a snapshot: the saved .nbt path plus the structure's center-bottom
     * anchor in the ship's local (plot) coordinates. BBS CML renders a StructureForm
     * with its horizontal center and bottom placed at the form origin, so to align
     * the replay we record the world position of THIS local point rather than the
     * ship's physics origin.
     */
    public record Result(Path file, double anchorX, double anchorY, double anchorZ) {}

    @Nullable
    public Result snapshotShip(ServerSubLevel ship) {
        if (ship == null) return null;
        UUID id = ship.getUniqueId();
        if (id == null) return null;
        return snapshotShip(ship, id.toString());
    }

    /**
     * Snapshot a ship to a specific structure file id. Block-update generations use this to
     * write each generation to its own "&lt;fileId&gt;.nbt" while keeping the same ship.
     */
    @Nullable
    public Result snapshotShip(ServerSubLevel ship, String fileId) {
        if (ship == null || fileId == null) return null;
        UUID id = ship.getUniqueId();
        if (id == null) return null;

        try {
            Level subLevelLevel = ship.getLevel();
            if (!(subLevelLevel instanceof ServerLevel serverLevel)) {
                AeronauticsCmlBridge.LOGGER.warn("[aeronauticscml] SubLevel's level is not a ServerLevel - skipping");
                return null;
            }

            MinecraftServer server = serverLevel.getServer();
            if (server == null) {
                AeronauticsCmlBridge.LOGGER.warn("[aeronauticscml] Server is null - skipping");
                return null;
            }

            // Force-update the bounding box to ensure it's current
            ship.updateBoundingBox();

            // Get the plot and its bounds. Sable's plot bounding box is already
            // expressed in the embedded plot world's block coordinates.
            LevelPlot plot = ship.getPlot();
            if (plot == null) {
                AeronauticsCmlBridge.LOGGER.warn("[aeronauticscml] SubLevel {} has no plot - skipping", id);
                return null;
            }

            BoundingBox3ic bounds = plot.getBoundingBox();
            AeronauticsCmlBridge.LOGGER.info("[aeronauticscml] SubLevel {} plot bounds: [{},{},{}] to [{},{},{}]",
                    id, bounds.minX(), bounds.minY(), bounds.minZ(),
                    bounds.maxX(), bounds.maxY(), bounds.maxZ());

            int startX = bounds.minX();
            int startY = bounds.minY();
            int startZ = bounds.minZ();
            int endX = bounds.maxX();
            int endY = bounds.maxY();
            int endZ = bounds.maxZ();

            int sizeX = endX - startX + 1;
            int sizeY = endY - startY + 1;
            int sizeZ = endZ - startZ + 1;

            if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
                AeronauticsCmlBridge.LOGGER.warn("[aeronauticscml] SubLevel {} has invalid snapshot bounds - skipping", id);
                return null;
            }

            ChunkPos chunkMin = plot.getChunkMin();
            ChunkPos chunkMax = plot.getChunkMax();
            if (!plot.contains(new ChunkPos(startX >> 4, startZ >> 4))
                    || !plot.contains(new ChunkPos(endX >> 4, endZ >> 4))) {
                AeronauticsCmlBridge.LOGGER.warn(
                        "[aeronauticscml] SubLevel {} snapshot bounds are outside plot chunks {}..{}: [{},{},{}] to [{},{},{}] - skipping",
                        id, chunkMin, chunkMax, startX, startY, startZ, endX, endY, endZ);
                return null;
            }

            AeronauticsCmlBridge.LOGGER.info("[aeronauticscml] Snapshotting ship {} at world [{},{},{}] size {}x{}x{}",
                    id, startX, startY, startZ, sizeX, sizeY, sizeZ);

            // Save to <world>/generated/minecraft/structures/<uuid>.nbt
            File structuresDir = server.getWorldPath(
                    net.minecraft.world.level.storage.LevelResource.ROOT)
                    .resolve("generated/minecraft/structures").toFile();
            structuresDir.mkdirs();
            Path outFile = structuresDir.toPath().resolve(fileId + ".nbt");

            StructureTemplate template = new StructureTemplate();
            BlockPos worldStart = new BlockPos(startX, startY, startZ);
            BlockPos size = new BlockPos(sizeX, sizeY, sizeZ);

            template.fillFromWorld(serverLevel, worldStart, size, false, null);

            CompoundTag tag = template.save(new CompoundTag());

            boolean hasAnyBlock = tag.contains("blocks", net.minecraft.nbt.Tag.TAG_LIST)
                    && !tag.getList("blocks", net.minecraft.nbt.Tag.TAG_COMPOUND).isEmpty();
            if (!hasAnyBlock) {
                AeronauticsCmlBridge.LOGGER.warn("[aeronauticscml] SubLevel {} snapshot is empty - skipping", id);
                return null;
            }

            // Camo-block compatibility (recording-side fallback): swap Copycats/FramedBlocks
            // blocks for the block they mimic. Skipped when renderCamoBlocks is on - then the
            // client render-hook draws them with model data instead (keeps their shape), and we
            // must leave the camo blocks in the snapshot for it to find.
            if (BridgeConfig.load().substituteCamoBlocks() && !BridgeConfig.load().renderCamoBlocks()) {
                applyCamoSubstitution(tag, serverLevel.registryAccess());
            }

            try (FileOutputStream fos = new FileOutputStream(outFile.toFile())) {
                NbtIo.writeCompressed(tag, fos);
            }

            int blockCount = tag.getList("blocks", net.minecraft.nbt.Tag.TAG_COMPOUND).size();
            AeronauticsCmlBridge.LOGGER.info("[aeronauticscml] Snapshot for ship {} saved to {} ({} blocks, {}x{}x{})",
                    id, outFile, blockCount, sizeX, sizeY, sizeZ);

            // Anchor = horizontal center + bottom of the ACTUAL block bounds (matching how
            // StructureFormRenderer centers: cx=(boundsMin+boundsMax)/2, cy=boundsMin.y),
            // not the plot bounds (which may have empty margin). Block "pos" entries are
            // 0-based relative to worldStart, so add the world start to get plot coords.
            net.minecraft.nbt.ListTag blocks = tag.getList("blocks", net.minecraft.nbt.Tag.TAG_COMPOUND);
            int bminX = Integer.MAX_VALUE, bminY = Integer.MAX_VALUE, bminZ = Integer.MAX_VALUE;
            int bmaxX = Integer.MIN_VALUE, bmaxY = Integer.MIN_VALUE, bmaxZ = Integer.MIN_VALUE;
            for (int i = 0; i < blocks.size(); i++) {
                net.minecraft.nbt.ListTag bpos = blocks.getCompound(i).getList("pos", net.minecraft.nbt.Tag.TAG_INT);
                if (bpos.size() < 3) continue;
                int px = bpos.getInt(0), py = bpos.getInt(1), pz = bpos.getInt(2);
                bminX = Math.min(bminX, px); bminY = Math.min(bminY, py); bminZ = Math.min(bminZ, pz);
                bmaxX = Math.max(bmaxX, px); bmaxY = Math.max(bmaxY, py); bmaxZ = Math.max(bmaxZ, pz);
            }

            double anchorX, anchorY, anchorZ;
            if (bmaxX >= bminX) {
                // BBS's StructureFormRenderer.calculateRenderInfo places structure-local
                // point (cx - parityAuto) at the form origin, where parityAuto = -0.5 for
                // an ODD span (so the origin lands on the CENTER of the middle block, not
                // its corner) and 0 for an even span. The anchor must record the world
                // position of THAT exact point, otherwise odd-width ships sit ~0.5 block
                // off. Mirror it: + 0.5 on X/Z for odd width.
                int widthX = bmaxX - bminX + 1;
                int widthZ = bmaxZ - bminZ + 1;
                double parityX = (widthX % 2 == 1) ? 0.5 : 0.0;
                double parityZ = (widthZ % 2 == 1) ? 0.5 : 0.0;
                anchorX = startX + (bminX + bmaxX) / 2.0 + parityX;
                anchorY = startY + bminY;
                anchorZ = startZ + (bminZ + bmaxZ) / 2.0 + parityZ;
            } else {
                anchorX = (startX + endX) / 2.0;
                anchorY = startY;
                anchorZ = (startZ + endZ) / 2.0;
            }

            return new Result(outFile, anchorX, anchorY, anchorZ);

        } catch (Throwable t) {
            AeronauticsCmlBridge.LOGGER.error("[aeronauticscml] Failed to snapshot SubLevel {}: {}", id, t.toString(), t);
            return null;
        }
    }

    /**
     * Save an arbitrary block map (e.g. a Create contraption's blocks) as a vanilla
     * structure .nbt - the SAME single-palette format {@link #snapshotShip} produces via
     * {@code StructureTemplate.save}, so it can back a BBS {@code StructureForm} on the
     * existing render path.
     *
     * <p>The input {@link StructureTemplate.StructureBlockInfo#pos()} keys are treated as
     * positions in some local frame (for a Create contraption: relative to the contraption
     * anchor). They are normalised so the saved structure starts at (0,0,0).</p>
     *
     * @return the saved file plus the center-bottom anchor expressed in the INPUT (local)
     *         frame - i.e. the point BBS will place at the form origin, in the same
     *         coordinates as the input keys (so the caller can transform it to the world).
     *         Null if there is nothing saveable.
     */
    @Nullable
    public Result snapshotBlockMap(ServerLevel level,
                                   Map<BlockPos, StructureTemplate.StructureBlockInfo> blocks,
                                   UUID fileId) {
        if (level == null || blocks == null || blocks.isEmpty() || fileId == null) return null;
        try {
            MinecraftServer server = level.getServer();
            if (server == null) return null;

            int bminX = Integer.MAX_VALUE, bminY = Integer.MAX_VALUE, bminZ = Integer.MAX_VALUE;
            int bmaxX = Integer.MIN_VALUE, bmaxY = Integer.MIN_VALUE, bmaxZ = Integer.MIN_VALUE;
            for (StructureTemplate.StructureBlockInfo info : blocks.values()) {
                if (info == null || info.pos() == null || info.state() == null) continue;
                if (info.state().isAir()) continue;
                BlockPos p = info.pos();
                bminX = Math.min(bminX, p.getX()); bminY = Math.min(bminY, p.getY()); bminZ = Math.min(bminZ, p.getZ());
                bmaxX = Math.max(bmaxX, p.getX()); bmaxY = Math.max(bmaxY, p.getY()); bmaxZ = Math.max(bmaxZ, p.getZ());
            }
            if (bmaxX < bminX) return null; // no non-air blocks

            int sizeX = bmaxX - bminX + 1, sizeY = bmaxY - bminY + 1, sizeZ = bmaxZ - bminZ + 1;

            // Vanilla single-palette structure NBT: dedupe states into "palette", emit each
            // block as {pos:[x,y,z], state:idx, nbt:?} with positions normalised to bmin.
            LinkedHashMap<BlockState, Integer> palette = new LinkedHashMap<>();
            ListTag blocksTag = new ListTag();
            for (StructureTemplate.StructureBlockInfo info : blocks.values()) {
                if (info == null || info.pos() == null || info.state() == null) continue;
                BlockState state = info.state();
                if (state.isAir()) continue;
                Integer idx = palette.get(state);
                if (idx == null) { idx = palette.size(); palette.put(state, idx); }

                BlockPos p = info.pos();
                CompoundTag b = new CompoundTag();
                ListTag posTag = new ListTag();
                posTag.add(IntTag.valueOf(p.getX() - bminX));
                posTag.add(IntTag.valueOf(p.getY() - bminY));
                posTag.add(IntTag.valueOf(p.getZ() - bminZ));
                b.put("pos", posTag);
                b.putInt("state", idx);
                CompoundTag beNbt = info.nbt();
                if (beNbt != null && !beNbt.isEmpty()) {
                    CompoundTag copy = beNbt.copy();
                    copy.remove("x"); copy.remove("y"); copy.remove("z");
                    b.put("nbt", copy);
                }
                blocksTag.add(b);
            }
            if (blocksTag.isEmpty()) return null;

            ListTag paletteTag = new ListTag();
            for (BlockState state : palette.keySet()) {
                paletteTag.add(NbtUtils.writeBlockState(state));
            }

            ListTag sizeTag = new ListTag();
            sizeTag.add(IntTag.valueOf(sizeX));
            sizeTag.add(IntTag.valueOf(sizeY));
            sizeTag.add(IntTag.valueOf(sizeZ));

            CompoundTag root = new CompoundTag();
            root.put("size", sizeTag);
            root.put("entities", new ListTag());
            root.put("blocks", blocksTag);
            root.put("palette", paletteTag);

            File structuresDir = server.getWorldPath(
                    net.minecraft.world.level.storage.LevelResource.ROOT)
                    .resolve("generated/minecraft/structures").toFile();
            structuresDir.mkdirs();
            Path outFile = structuresDir.toPath().resolve(fileId.toString() + ".nbt");
            try (FileOutputStream fos = new FileOutputStream(outFile.toFile())) {
                NbtIo.writeCompressed(root, fos);
            }

            // Same center-bottom + odd-width parity convention as snapshotShip, but in the
            // input (local) frame (no world start to add).
            double parityX = (sizeX % 2 == 1) ? 0.5 : 0.0;
            double parityZ = (sizeZ % 2 == 1) ? 0.5 : 0.0;
            double anchorX = (bminX + bmaxX) / 2.0 + parityX;
            double anchorY = bminY;
            double anchorZ = (bminZ + bmaxZ) / 2.0 + parityZ;

            AeronauticsCmlBridge.LOGGER.info("[aeronauticscml] Contraption snapshot {} saved ({} blocks, {}x{}x{})",
                    outFile.getFileName(), blocksTag.size(), sizeX, sizeY, sizeZ);
            return new Result(outFile, anchorX, anchorY, anchorZ);
        } catch (Throwable t) {
            AeronauticsCmlBridge.LOGGER.error("[aeronauticscml] Failed to snapshot contraption {}: {}", fileId, t.toString(), t);
            return null;
        }
    }

    /**
     * A cheap order-stable hash of a ship's non-air blocks (position + block-state id),
     * for detecting block changes mid-recording without writing a file. Air is skipped, so
     * additions, removals and changes all alter the hash. Returns 0 on any failure (treated
     * by the caller as "no change").
     */
    public long contentHash(ServerSubLevel ship) {
        try {
            if (ship == null) return 0L;
            ship.updateBoundingBox();
            LevelPlot plot = ship.getPlot();
            if (plot == null) return 0L;
            if (!(ship.getLevel() instanceof ServerLevel level)) return 0L;
            BoundingBox3ic b = plot.getBoundingBox();

            long h = 1125899906842597L;
            BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
            for (int y = b.minY(); y <= b.maxY(); y++) {
                for (int z = b.minZ(); z <= b.maxZ(); z++) {
                    for (int x = b.minX(); x <= b.maxX(); x++) {
                        net.minecraft.world.level.block.state.BlockState st = level.getBlockState(mp.set(x, y, z));
                        if (st.isAir()) continue;
                        long posKey = (((long) x * 73856093L) ^ ((long) y * 19349663L) ^ ((long) z * 83492791L));
                        h = h * 1099511628211L + posKey;
                        h = h * 1099511628211L + net.minecraft.world.level.block.Block.getId(st);
                    }
                }
            }
            return h;
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static final java.util.Set<String> CAMO_NAMESPACES =
            java.util.Set.of("copycats", "extra_copycats", "aerocopycats", "framedblocks", "create");

    /**
     * Replace camo blocks (Copycats/FramedBlocks/etc.) in a saved structure tag with the
     * block state they mimic, so BBS's static render shows the right appearance. Only blocks
     * in known camo namespaces with a non-empty mimic state are changed; everything else is
     * left untouched. Fully guarded.
     */
    private void applyCamoSubstitution(CompoundTag tag, HolderLookup.Provider registries) {
        try {
            if (!tag.contains("blocks", Tag.TAG_LIST) || !tag.contains("palette", Tag.TAG_LIST)) return;
            ListTag palette = tag.getList("palette", Tag.TAG_COMPOUND);
            ListTag blocks = tag.getList("blocks", Tag.TAG_COMPOUND);
            if (palette.isEmpty() || blocks.isEmpty()) return;
            HolderLookup.RegistryLookup<Block> blockLookup = registries.lookupOrThrow(Registries.BLOCK);

            Map<String, Integer> paletteIndex = new HashMap<>();
            for (int i = 0; i < palette.size(); i++) paletteIndex.put(palette.getCompound(i).toString(), i);

            int substituted = 0;
            for (int i = 0; i < blocks.size(); i++) {
                CompoundTag b = blocks.getCompound(i);
                if (!b.contains("nbt", Tag.TAG_COMPOUND)) continue;
                int stateIdx = b.getInt("state");
                if (stateIdx < 0 || stateIdx >= palette.size()) continue;

                BlockState state = NbtUtils.readBlockState(blockLookup, palette.getCompound(stateIdx));
                if (state == null || state.isAir()) continue;
                String ns = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace();
                if (!CAMO_NAMESPACES.contains(ns)) continue;

                ListTag posTag = b.getList("pos", Tag.TAG_INT);
                BlockPos pos = posTag.size() >= 3
                        ? new BlockPos(posTag.getInt(0), posTag.getInt(1), posTag.getInt(2)) : BlockPos.ZERO;
                BlockEntity be = BlockEntity.loadStatic(pos, state, b.getCompound("nbt"), registries);
                if (be == null) continue;
                BlockState camo = extractCamoState(be);
                if (camo == null || camo.isAir() || camo == state) continue;

                CompoundTag camoTag = NbtUtils.writeBlockState(camo);
                String key = camoTag.toString();
                Integer idx = paletteIndex.get(key);
                if (idx == null) {
                    idx = palette.size();
                    palette.add(camoTag);
                    paletteIndex.put(key, idx);
                }
                b.putInt("state", idx);
                b.remove("nbt"); // the mimicked block has no camo block-entity
                substituted++;
            }
            if (substituted > 0) {
                AeronauticsCmlBridge.LOGGER.info("[aeronauticscml] Camo substitution: {} block(s) replaced with their material", substituted);
            }
        } catch (Throwable t) {
            AeronauticsCmlBridge.LOGGER.warn("[aeronauticscml] Camo substitution failed: {}", t.toString());
        }
    }

    /** The block state a camo block-entity is mimicking (Copycats getMaterial / FramedBlocks getCamo), or null. */
    private static BlockState extractCamoState(BlockEntity be) {
        // Copycats / Create copycats: BlockState getMaterial()
        Object material = invokeNoArg(be, "getMaterial");
        if (material instanceof BlockState bs) return bs;
        // FramedBlocks: getCamo().getContent().getState()
        Object container = invokeNoArg(be, "getCamo");
        if (container != null) {
            Object content = invokeNoArg(container, "getContent");
            if (content != null) {
                Object st = invokeNoArg(content, "getState");
                if (st instanceof BlockState bs) return bs;
            }
        }
        return null;
    }

    private static Object invokeNoArg(Object target, String method) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(method);
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }
}
