package dev.deadearth.mixin;

import dev.deadearth.starter.SafeBunker;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class SafeBunkerEntityMixin {
    @Inject(method="setPosRaw",at=@At("HEAD"),cancellable=true)
    private void deadEarthMonsterBarrier(double x,double y,double z,CallbackInfo result) {
        var entity=(Entity)(Object)this;
        if(SafeBunker.blocksPosition(entity,x,y,z)){entity.setDeltaMovement(Vec3.ZERO);result.cancel();}
    }
}
