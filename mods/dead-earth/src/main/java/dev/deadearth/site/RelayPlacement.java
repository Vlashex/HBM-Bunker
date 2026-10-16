package dev.deadearth.site;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class RelayPlacement {
    public record Preview(boolean allowed, String reason, int blocks) {}
    private static final Map<BlockPos, BlockState> BLUEPRINT = blueprint();
    private RelayPlacement() {}
    public static Preview preview(ServerLevel level, BlockPos origin) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Placement outside server thread");
        if (!level.dimension().equals(Level.OVERWORLD)) return denied("overworld_only");
        if (origin.getX() < -29999984 || origin.getX() > 29999975 || origin.getZ() < -29999984 || origin.getZ() > 29999975
                || origin.getY() <= level.getMinBuildHeight() || origin.getY() + 5 >= level.getMaxBuildHeight()) return denied("outside_build_bounds");
        var end = origin.offset(8, 5, 8);
        if (!level.getWorldBorder().isWithinBounds(origin) || !level.getWorldBorder().isWithinBounds(end)) return denied("outside_world_border");
        if (!level.hasChunksAt(origin.below(), end)) return denied("chunks_not_loaded");
        for (var pos : BlockPos.betweenClosed(origin, end)) if (!level.getBlockState(pos).isAir()) return denied("occupied_volume");
        for (int x : new int[] {0, 8}) for (int z : new int[] {0, 8}) {
            var support = origin.offset(x, -1, z);
            if (!level.getBlockState(support).isFaceSturdy(level, support, Direction.UP)) return denied("unsupported_corners");
        }
        var center = Vec3.atCenterOf(origin.offset(4, 1, 4));
        if (level.players().stream().anyMatch(p -> p.distanceToSqr(center) < 1024)) return denied("players_too_close");
        var volume = new AABB(Vec3.atLowerCornerOf(origin), Vec3.atLowerCornerOf(origin.offset(9, 6, 9)));
        if (!level.getEntities(null, volume).isEmpty()) return denied("entities_in_volume");
        return new Preview(true, "ready", BLUEPRINT.size());
    }
    public static int place(ServerLevel level, BlockPos origin) {
        var preview = preview(level, origin);
        if (!preview.allowed()) throw new IllegalStateException("Relay placement refused: " + preview.reason());
        int changed = 0;
        for (var block : BLUEPRINT.entrySet()) {
            var pos = origin.offset(block.getKey());
            if (!level.getBlockState(pos).isAir() || !level.setBlock(pos, block.getValue(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE))
                throw new IllegalStateException("Relay placement interrupted; partial result retained");
            changed++;
        }
        return changed;
    }
    public static Optional<Boolean> intact(ServerLevel level, BlockPos origin) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Validation outside server thread");
        if (!level.hasChunksAt(origin, origin.offset(8, 5, 8))) return Optional.empty();
        for (var block : BLUEPRINT.entrySet())
            if (!level.getBlockState(origin.offset(block.getKey())).is(block.getValue().getBlock())) return Optional.of(false);
        return Optional.of(true);
    }
    private static Preview denied(String reason) { return new Preview(false, reason, BLUEPRINT.size()); }
    private static Map<BlockPos, BlockState> blueprint() {
        var blocks = new LinkedHashMap<BlockPos, BlockState>();
        for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++) blocks.put(new BlockPos(x, 0, z),
                ((x + z) % 5 == 0 ? Blocks.CRACKED_STONE_BRICKS : Blocks.STONE_BRICKS).defaultBlockState());
        for (int x : new int[] {0, 8}) for (int z : new int[] {0, 8}) for (int y = 1; y <= 3; y++)
            blocks.put(new BlockPos(x, y, z), Blocks.MOSSY_STONE_BRICKS.defaultBlockState());
        for (int y = 1; y <= 4; y++) blocks.put(new BlockPos(4, y, 4), Blocks.IRON_BARS.defaultBlockState());
        blocks.put(new BlockPos(4, 5, 4), Blocks.REDSTONE_LAMP.defaultBlockState());
        blocks.put(new BlockPos(2, 1, 4), Blocks.LECTERN.defaultBlockState());
        return Collections.unmodifiableMap(blocks);
    }
}
