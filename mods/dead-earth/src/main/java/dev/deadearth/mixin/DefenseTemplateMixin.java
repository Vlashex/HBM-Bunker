package dev.deadearth.mixin;

import dev.deadearth.content.DefenseContent;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Canonicalizes one exact retired block while native templates load, including new OutpostZero bunkers. */
@Mixin(StructureTemplate.class)
public abstract class DefenseTemplateMixin {
    @Redirect(method="loadPalette",at=@At(value="INVOKE",target="Lnet/minecraft/nbt/NbtUtils;readBlockState(Lnet/minecraft/core/HolderGetter;Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState deadEarth$canonicalDefense(HolderGetter<Block> registry,CompoundTag tag){
        var original=NbtUtils.readBlockState(registry,tag);
        return BuiltInRegistries.BLOCK.getKey(original.getBlock()).toString().equals("outpostzero:steel_block")
            ?DefenseContent.WALL_V1.get().defaultBlockState():original;
    }
}
