package com.aeronauticscml.bridge.client;

import com.aeronauticscml.bridge.mod.AeronauticsCmlBridge;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.forms.forms.StructureForm;
import mchorse.bbs_mod.resources.Link;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-side loader/cache for a ship's vanilla structure {@code .nbt} - the SAME
 * file BBS renders, resolved through BBS's own provider. Unlike BBS's parser, this
 * ALSO keeps each block-entity's NBT (the {@code "nbt"} field), which carries Create
 * kinetic {@code Speed} / propeller {@code rotationSpeed}: everything we need to
 * reconstruct a live, spinning block entity at playback.
 *
 * <p>Keyed by the structure file string, re-read when the file's mtime changes (so a
 * re-record is picked up). Disk checks are throttled to once/sec, like
 * {@link RopeDataStore}. See {@link KineticReplayRenderer} for how it is used.</p>
 */
public final class StructureNbtStore {
    private StructureNbtStore() {}

    /** One block from the snapshot: its state, local position, and optional block-entity NBT. */
    public static final class BlockEntry {
        public final BlockPos pos;
        public final BlockState state;
        public final CompoundTag beNbt; // null if the block has no block entity

        BlockEntry(BlockPos pos, BlockState state, CompoundTag beNbt) {
            this.pos = pos;
            this.state = state;
            this.beNbt = beNbt;
        }
    }

    /**
     * A parsed structure: every block + the center-bottom pivot, computed EXACTLY as
     * {@code StructureFormRenderer.calculateRenderInfo} does, so dynamic blocks land
     * on top of BBS's static mesh.
     */
    public static final class Parsed {
        public final List<BlockEntry> blocks;
        public final double pivotX;
        public final double pivotY;
        public final double pivotZ;

        Parsed(List<BlockEntry> blocks, double pivotX, double pivotY, double pivotZ) {
            this.blocks = blocks;
            this.pivotX = pivotX;
            this.pivotY = pivotY;
            this.pivotZ = pivotZ;
        }
    }

    private static final class Entry {
        Parsed parsed;
        long mtime;
        long lastCheck;
    }

    private static final Map<String, Entry> CACHE = new HashMap<>();

    /** Parse (cached) the structure backing this StructureForm, or null if unavailable. */
    public static Parsed get(StructureForm form) {
        String file;
        try {
            file = form.structureFile.get();
        } catch (Throwable t) {
            return null;
        }
        if (file == null || file.isEmpty()) {
            return null;
        }

        long now = System.currentTimeMillis();
        Entry e = CACHE.get(file);
        if (e != null && now - e.lastCheck < 1000L) {
            return e.parsed; // throttle disk checks to once/sec
        }

        try {
            File nbtFile = BBSMod.getProvider().getFile(Link.create(file));
            if (nbtFile == null || !nbtFile.exists()) {
                if (e == null) {
                    e = new Entry();
                    CACHE.put(file, e);
                }
                e.lastCheck = now;
                e.parsed = null;
                return null;
            }

            long mtime = nbtFile.lastModified();
            if (e != null && e.parsed != null && e.mtime == mtime) {
                e.lastCheck = now;
                return e.parsed;
            }

            CompoundTag root = NbtIo.readCompressed(nbtFile.toPath(), NbtAccounter.unlimitedHeap());
            Parsed parsed = parse(root);

            Entry ne = new Entry();
            ne.parsed = parsed;
            ne.mtime = mtime;
            ne.lastCheck = now;
            CACHE.put(file, ne);

            AeronauticsCmlBridge.LOGGER.info("[aeronauticscml] Loaded structure nbt '{}' ({} blocks) for kinetics",
                    file, parsed == null ? 0 : parsed.blocks.size());
            return parsed;
        } catch (Throwable t) {
            AeronauticsCmlBridge.LOGGER.debug("[aeronauticscml] Failed to read structure nbt '{}': {}", file, t.toString());
            return e != null ? e.parsed : null;
        }
    }

    private static Parsed parse(CompoundTag root) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        HolderGetter<Block> blockLookup = mc.level.registryAccess().lookupOrThrow(Registries.BLOCK);

        // Palette -> states.
        List<BlockState> palette = new ArrayList<>();
        ListTag paletteTag = root.getList("palette", Tag.TAG_COMPOUND);
        for (int i = 0; i < paletteTag.size(); i++) {
            palette.add(NbtUtils.readBlockState(blockLookup, paletteTag.getCompound(i)));
        }

        List<BlockEntry> out = new ArrayList<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        ListTag blockList = root.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blockList.size(); i++) {
            CompoundTag b = blockList.getCompound(i);
            ListTag posTag = b.getList("pos", Tag.TAG_INT);
            if (posTag.size() < 3) {
                continue;
            }
            int px = posTag.getInt(0);
            int py = posTag.getInt(1);
            int pz = posTag.getInt(2);
            int stateIdx = b.getInt("state");
            if (stateIdx < 0 || stateIdx >= palette.size()) {
                continue;
            }
            BlockState state = palette.get(stateIdx);
            if (state == null || state.isAir()) {
                continue;
            }
            CompoundTag beNbt = b.contains("nbt", Tag.TAG_COMPOUND) ? b.getCompound("nbt") : null;
            out.add(new BlockEntry(new BlockPos(px, py, pz), state, beNbt));

            if (px < minX) minX = px;
            if (py < minY) minY = py;
            if (pz < minZ) minZ = pz;
            if (px > maxX) maxX = px;
            if (py > maxY) maxY = py;
            if (pz > maxZ) maxZ = pz;
        }

        if (out.isEmpty()) {
            return null;
        }

        // Mirror StructureFormRenderer.calculateRenderInfo EXACTLY:
        //   cx = (min+max)/2 ; cy = min.y ; parity = -0.5 when the span is odd ;
        //   pivot = c - parity.
        float cx = (minX + maxX) / 2F;
        float cz = (minZ + maxZ) / 2F;
        float cy = minY;
        int widthX = maxX - minX + 1;
        int widthZ = maxZ - minZ + 1;
        float parityX = (widthX % 2 == 1) ? -0.5F : 0F;
        float parityZ = (widthZ % 2 == 1) ? -0.5F : 0F;

        return new Parsed(out, cx - parityX, cy, cz - parityZ);
    }
}
