package dev.deadearth.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.deadearth.compat.HbmBunkerProtection;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongCollection;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Coerce;

@Pseudo @Mixin(targets="com.hbm.util.ChunkUtil",remap=false)
public abstract class HbmSafeBunkerMixin {
    @WrapMethod(method="carveLocal")
    private static LevelChunkSection safeCarve(ServerLevel level,int cx,int cz,int sub,LevelChunkSection source,
            @Coerce Object mask,LongCollection edge,Long2ObjectMap<BlockState> changed,boolean skipEntities,boolean owned,
            Operation<LevelChunkSection> original) {
        return original.call(level,cx,cz,sub,source,HbmBunkerProtection.mask(level,cx,cz,sub,source,mask),edge,changed,skipEntities,owned);
    }
    @WrapMethod(method="applyToSnapshot")
    private static void safeReplacements(@Coerce Object snapshot,Long2ObjectOpenHashMap<BlockState> changes,
            Long2ObjectOpenHashMap<BlockState> old,Long2ObjectOpenHashMap<BlockState> deferred,Operation<Void> original) {
        original.call(snapshot,HbmBunkerProtection.states(snapshot,changes),old,deferred);
    }
}
