package com.aeronauticscml.bridge.aeronautics;

import com.aeronauticscml.bridge.mod.AeronauticsCmlBridge;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
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
            Path outFile = structuresDir.toPath().resolve(id.toString() + ".nbt");

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
}
