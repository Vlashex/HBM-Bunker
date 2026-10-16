package dev.deadearth.mixin;
import dev.deadearth.climate.SeasonState;
import dev.deadearth.compat.SeasonHooks;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo @Mixin(targets="com.yayuy.overgrownvegetation.corruption.OvergrownBiomeCreep",remap=false)
abstract class ForestCreepMixin {
    @Redirect(method={"sweepInterior","bleedEdges"},at=@At(value="INVOKE",target="Lnet/neoforged/neoforge/common/ModConfigSpec$IntValue;get()Ljava/lang/Object;"))
    private static Object budget(ModConfigSpec.IntValue value) {
        int base=value.get();String key=value.getPath().getLast();
        return key.equals("overgrownBiomeCreepChunksPerTick")||key.equals("overgrownBiomeCreepAttempts")?SeasonHooks.forestBudget(key,base):base;
    }
    @ModifyArg(method="drainDeepen",at=@At(value="INVOKE",target="Lcom/yayuy/overgrownvegetation/corruption/OvergrownBiomeCreep;deepenChunk(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/ChunkPos;Lit/unimi/dsi/fastutil/longs/Long2IntOpenHashMap;JIIDD)V"),index=4)
    private static int deepBudget(int base) {return SeasonHooks.forestBudget("creep_deep",base);}
}
@Pseudo @Mixin(targets={"com.yayuy.overgrownvegetation.worldgen.GiantTreeGrowthQueue","com.yayuy.overgrownvegetation.worldgen.SaplingOfflineGrowth"},remap=false)
abstract class ForestTreeQueueMixin {
    @Redirect(method="onLevelTick",at=@At(value="INVOKE",target="Lnet/neoforged/neoforge/common/ModConfigSpec$IntValue;get()Ljava/lang/Object;"))
    private static Object budget(ModConfigSpec.IntValue value) {
        int base=value.get();String key=value.getPath().getLast();
        return key.equals("giantTreeBlocksPerTick")||key.equals("saplingOfflineGrowthPerTick")?SeasonHooks.forestBudget(key,base):base;
    }
}
@Pseudo @Mixin(targets="com.yayuy.overgrownvegetation.block.CorruptedSaplingBlock",remap=false)
abstract class ForestSaplingMixin {
    @Redirect(method="randomTick",at=@At(value="INVOKE",target="Lnet/minecraft/util/RandomSource;nextInt(I)I"))
    private int roll(RandomSource random,int bound) {
        if(bound!=7 || SeasonState.forest()==1) return random.nextInt(bound);
        return random.nextDouble()<Math.clamp((2d/7)*SeasonState.forest(),0,1)?0:2;
    }
}
@Pseudo @Mixin(targets="com.yayuy.overgrownvegetation.corruption.BlightFernSelfSpread",remap=false)
abstract class ForestFernMixin {
    @Redirect(method="spawnNextGeneration",at=@At(value="INVOKE",target="Lnet/neoforged/neoforge/common/ModConfigSpec$DoubleValue;get()Ljava/lang/Object;"))
    private static Object chance(ModConfigSpec.DoubleValue value) {return value.getPath().getLast().equals("blightFernFinishSpawnChance")
            ?Math.clamp(value.get()*SeasonState.forest(),0,1):value.get();}
}
