package dev.deadearth.mixin;

import dev.deadearth.starter.SafeBunker;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(StructureTemplate.class)
public abstract class SafeBunkerTemplateMixin {
    @Inject(method="placeInWorld",at=@At("HEAD"))
    private void deadEarthBounds(ServerLevelAccessor level,BlockPos origin,BlockPos reference,StructurePlaceSettings settings,RandomSource random,int flags,CallbackInfoReturnable<Boolean> result) {
        SafeBunker.captureTemplate((StructureTemplate)(Object)this,level,origin,settings);
    }
}
