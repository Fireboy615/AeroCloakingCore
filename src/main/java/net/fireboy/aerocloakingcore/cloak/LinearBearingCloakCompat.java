package net.fireboy.aerocloakingcore.cloak;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import net.neoforged.fml.ModList;

import org.joml.Vector3d;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToIntFunction;

/**
 * Optional position-based membership for independent Linear Bearing sublevels.
 * Does not create a parent/child relationship or persistent Sable connection.
 *
 * The caller rescans every second. Sliding out of the host ship's WORLD bounds
 * removes both the borrowed cloak and the extra block-count/capacity load.
 */
public final class LinearBearingCloakCompat {

    // Includes the moving carriage forms as well as the base bearing forms.
    // Resolve from the actual registry, not a defaulted fallback: an unknown
    // name must NEVER become Blocks.AIR and match every candidate sublevel.
    private static final String[] BEARING_BLOCK_NAMES = {
            "linear_bearing",
            "mechanical_linear_bearing",
            "linear_moving",
            "mechanical_linear_moving"
    };

    private LinearBearingCloakCompat() {
    }

    public static void includeInsideBoundingBox(
            SubLevel root,
            Map<UUID, Integer> counts,
            ToIntFunction<SubLevel> countBlocks
    ) {
        if (!ModList.get().isLoaded("linearbearing") || root.isRemoved()) {
            return;
        }

        Set<Block> bearingBlocks = resolveBearingBlocks();
        if (bearingBlocks.isEmpty()) {
            return;
        }

        BoundingBox3dc hostBounds = root.boundingBox();
        for (SubLevel candidate : Sable.HELPER.getAllIntersecting(
                root.getLevel(), hostBounds)) {
            if (candidate == null || candidate.isRemoved()
                    || candidate.getUniqueId().equals(root.getUniqueId())
                    || counts.containsKey(candidate.getUniqueId())) {
                continue;
            }

            if (hasBearingInside(candidate, hostBounds, bearingBlocks)) {
                counts.put(candidate.getUniqueId(), countBlocks.applyAsInt(candidate));
            }
        }
    }

    private static Set<Block> resolveBearingBlocks() {
        Set<Block> blocks = new HashSet<>();
        for (String name : BEARING_BLOCK_NAMES) {
            BuiltInRegistries.BLOCK.getOptional(
                    ResourceLocation.fromNamespaceAndPath("linearbearing", name)
            ).filter(block -> block != Blocks.AIR).ifPresent(blocks::add);
        }
        return blocks;
    }

    private static boolean hasBearingInside(
            SubLevel candidate,
            BoundingBox3dc hostBounds,
            Set<Block> bearingBlocks
    ) {
        Vector3d worldPosition = new Vector3d();

        for (var holder : candidate.getPlot().getLoadedChunks()) {
            LevelChunk chunk = holder.getChunk();
            if (chunk == null) {
                continue;
            }

            LevelChunkSection[] sections = chunk.getSections();
            for (int index = 0; index < sections.length; index++) {
                LevelChunkSection section = sections[index];
                if (section == null || section.hasOnlyAir()) {
                    continue;
                }

                final boolean[] containsBearing = {false};
                section.getStates().count((state, occurrences) -> {
                    if (bearingBlocks.contains(state.getBlock())) {
                        containsBearing[0] = true;
                    }
                });
                if (!containsBearing[0]) {
                    continue;
                }

                int x0 = chunk.getPos().getMinBlockX();
                int z0 = chunk.getPos().getMinBlockZ();
                int y0 = chunk.getSectionYFromSectionIndex(index) * 16;

                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            if (!bearingBlocks.contains(
                                    section.getBlockState(x, y, z).getBlock())) {
                                continue;
                            }

                            worldPosition.set(x0 + x + 0.5,
                                    y0 + y + 0.5, z0 + z + 0.5);
                            candidate.logicalPose().transformPosition(worldPosition);
                            if (hostBounds.contains(worldPosition)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }

        return false;
    }
}
